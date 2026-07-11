package com.splannes.fileshares;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles incoming share requests from other devices on the local network.
 *
 * <p>When another device wants to share files with this device, the sender
 * POSTs a share-request to this device's FileServer endpoint.  This class
 * is responsible for:</p>
 *
 * <ul>
 *   <li>Receiving the share request via the FileServer API</li>
 *   <li>Showing a notification with ALLOW / DENY actions</li>
 *   <li>If allowed – opening the browser to the sender's file server URL</li>
 *   <li>If denied – sending an HTTP rejection response back to the sender</li>
 * </ul>
 *
 * <p>Thread-safe.  All notification and callback work is dispatched to the
 * main thread via a {@link Handler}.</p>
 *
 * <h3>Integration with FileServer</h3>
 * <p>Call {@link #installShareRequestRoute(FileServer)} after the FileServer
 * is started.  This injects a {@code /share-request} POST endpoint that
 * other devices can call.</p>
 *
 * <h3>Notification action flow</h3>
 * <pre>
 *   [Sender] --POST /share-request--> [This device FileServer]
 *                                          |
 *                                   ShareRequestReceiver
 *                                          |
 *                                   Show notification
 *                                     /           \
 *                               [ALLOW]          [DENY]
 *                                 |                 |
 *                          Open browser       POST /share-response
 *                          to sender URL      (rejected) to sender
 * </pre>
 */
public class ShareRequestReceiver {

    private static final String TAG = "ShareRequestReceiver";

    // Notification constants
    private static final String CHANNEL_ID = "share_request_channel";
    private static final AtomicInteger NOTIFICATION_ID_GENERATOR = new AtomicInteger(2000);

    // Intent action strings
    public static final String ACTION_ALLOW = "com.example.fileshares.SHARE_ALLOW";
    public static final String ACTION_DENY  = "com.example.fileshares.SHARE_DENY";

    // Intent extra keys
    private static final String EXTRA_REQUEST_ID    = "request_id";
    private static final String EXTRA_SENDER_NAME   = "sender_name";
    private static final String EXTRA_SENDER_IP     = "sender_ip";
    private static final String EXTRA_SENDER_PORT   = "sender_port";
    private static final String EXTRA_FILE_COUNT    = "file_count";
    private static final String EXTRA_CALLBACK_URL  = "callback_url";

    // ---------------------------------------------------------------
    // Inner data classes
    // ---------------------------------------------------------------

    /**
     * Represents an incoming share request from a remote device.
     */
    public static class ShareRequest {
        /** Unique ID for this request (used as notification tag). */
        public final String id;
        /** Friendly name of the sending device. */
        public final String senderName;
        /** IP address of the sending device. */
        public final String senderIp;
        /** Port of the sender's file server. */
        public final int senderPort;
        /** Number of files the sender wants to share. */
        public final int fileCount;
        /** URL to POST a response back to the sender (optional). */
        public final String callbackUrl;
        /** Timestamp when the request was received. */
        public final long timestamp;

        public ShareRequest(String id, String senderName, String senderIp,
                            int senderPort, int fileCount, String callbackUrl) {
            this.id = id;
            this.senderName = senderName;
            this.senderIp = senderIp;
            this.senderPort = senderPort;
            this.fileCount = fileCount;
            this.callbackUrl = callbackUrl;
            this.timestamp = System.currentTimeMillis();
        }

        /** Build the HTTP URL pointing at the sender's file server. */
        public String getSenderUrl() {
            return "http://" + senderIp + ":" + senderPort;
        }

        @Override
        public String toString() {
            return senderName + " (" + senderIp + ":" + senderPort + ") – "
                    + fileCount + " file(s)";
        }
    }

    /**
     * Listener for share-request lifecycle events.
     * All callbacks are invoked on the main thread.
     */
    public interface OnShareRequestListener {
        /** Called when a new share request is received. */
        void onRequestReceived(ShareRequest request);

        /** Called when the user allows the share request. */
        void onRequestAllowed(ShareRequest request);

        /** Called when the user denies the share request. */
        void onRequestDenied(ShareRequest request);
    }

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final Context context;
    private final Handler mainHandler;
    private final NotificationManager notificationManager;

    /** Pending requests keyed by request ID. */
    private final ConcurrentHashMap<String, ShareRequest> pendingRequests =
            new ConcurrentHashMap<>();

    /** Map from request ID to the actual notification ID used when showing the notification. */
    private final ConcurrentHashMap<String, Integer> requestNotificationIds =
            new ConcurrentHashMap<>();

    private OnShareRequestListener listener;
    private boolean channelCreated = false;
    private boolean receiverRegistered = false;

    // The broadcast receiver that handles ALLOW / DENY notification actions
    private final NotificationActionReceiver actionReceiver = new NotificationActionReceiver();

    // ---------------------------------------------------------------
    // Construction & lifecycle
    // ---------------------------------------------------------------

    public ShareRequestReceiver(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.notificationManager = (NotificationManager)
                this.context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Set the listener for share-request events.
     */
    public void setListener(OnShareRequestListener listener) {
        this.listener = listener;
    }

    /**
     * Register the notification-action BroadcastReceiver.
     * Must be called (e.g. in Activity.onCreate / Service.onCreate)
     * before any notifications can be acted upon.
     */
    public void register() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ALLOW);
        filter.addAction(ACTION_DENY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(actionReceiver, filter);
        }
        receiverRegistered = true;
        Log.d(TAG, "Notification action receiver registered");
    }

    /**
     * Unregister the BroadcastReceiver.
     * Call in Activity.onDestroy / Service.onDestroy.
     */
    public void unregister() {
        if (!receiverRegistered) return;
        try {
            context.unregisterReceiver(actionReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver was not registered", e);
        }
        receiverRegistered = false;
    }

    /**
     * Full cleanup: unregister receiver, cancel notifications, clear state.
     */
    public void shutdown() {
        unregister();
        cancelAllNotifications();
        pendingRequests.clear();
        listener = null;
    }

    // ---------------------------------------------------------------
    // Public API – handle an incoming request
    // ---------------------------------------------------------------

    /**
     * Called when the FileServer receives a share-request POST.
     * Shows a notification and stores the request as pending.
     *
     * @param senderName  Friendly name of the sending device.
     * @param senderIp    IP address of the sender.
     * @param senderPort  Port of the sender's file server.
     * @param fileCount   Number of files being offered.
     * @param callbackUrl Optional URL to POST a rejection response to.
     * @return the generated request ID.
     */
    public String handleIncomingRequest(String senderName, String senderIp,
                                        int senderPort, int fileCount,
                                        String callbackUrl) {
        String requestId = "sr_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);

        ShareRequest request = new ShareRequest(
                requestId, senderName, senderIp, senderPort, fileCount, callbackUrl);

        pendingRequests.put(requestId, request);
        Log.d(TAG, "Incoming share request: " + request);

        showRequestNotification(request);

        notifyListener(() -> {
            if (listener != null) listener.onRequestReceived(request);
        });

        return requestId;
    }

    // ---------------------------------------------------------------
    // ALLOW / DENY handling
    // ---------------------------------------------------------------

    /**
     * Allow a pending share request: open the sender's URL in the browser.
     */
    public void allowRequest(String requestId) {
        ShareRequest request = pendingRequests.remove(requestId);
        if (request == null) {
            Log.w(TAG, "allowRequest: unknown request ID " + requestId);
            return;
        }

        cancelNotification(requestId);

        // Open the sender's file server URL in the browser
        String url = request.getSenderUrl();
        Log.d(TAG, "Allowing share request – opening " + url);

        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open browser for " + url, e);
        }

        notifyListener(() -> {
            if (listener != null) listener.onRequestAllowed(request);
        });
    }

    /**
     * Deny a pending share request: send a rejection response if a
     * callback URL was provided.
     */
    public void denyRequest(String requestId) {
        ShareRequest request = pendingRequests.remove(requestId);
        if (request == null) {
            Log.w(TAG, "denyRequest: unknown request ID " + requestId);
            return;
        }

        cancelNotification(requestId);
        Log.d(TAG, "Denying share request from " + request.senderName);

        // Send rejection in the background if a callback URL was supplied
        if (request.callbackUrl != null && !request.callbackUrl.isEmpty()) {
            sendRejectionAsync(request);
        }

        notifyListener(() -> {
            if (listener != null) listener.onRequestDenied(request);
        });
    }

    // ---------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------

    /**
     * Get all pending (not yet acted upon) share requests.
     */
    public Map<String, ShareRequest> getPendingRequests() {
        return new HashMap<>(pendingRequests);
    }

    /**
     * Check whether a specific request is still pending.
     */
    public boolean isRequestPending(String requestId) {
        return pendingRequests.containsKey(requestId);
    }

    // ---------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------

    private void ensureChannelCreated() {
        if (channelCreated) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Share Requests",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Incoming file share requests from nearby devices");
            channel.enableVibration(true);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
        channelCreated = true;
    }

    private void showRequestNotification(ShareRequest request) {
        ensureChannelCreated();

        int notificationId = NOTIFICATION_ID_GENERATOR.incrementAndGet();

        // ALLOW action
        Intent allowIntent = new Intent(ACTION_ALLOW);
        allowIntent.setPackage(context.getPackageName());
        allowIntent.putExtra(EXTRA_REQUEST_ID, request.id);
        allowIntent.putExtra(EXTRA_SENDER_NAME, request.senderName);
        allowIntent.putExtra(EXTRA_SENDER_IP, request.senderIp);
        allowIntent.putExtra(EXTRA_SENDER_PORT, request.senderPort);
        allowIntent.putExtra(EXTRA_FILE_COUNT, request.fileCount);
        allowIntent.putExtra(EXTRA_CALLBACK_URL, request.callbackUrl);

        PendingIntent allowPending = PendingIntent.getBroadcast(
                context,
                notificationId,
                allowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // DENY action
        Intent denyIntent = new Intent(ACTION_DENY);
        denyIntent.setPackage(context.getPackageName());
        denyIntent.putExtra(EXTRA_REQUEST_ID, request.id);
        denyIntent.putExtra(EXTRA_CALLBACK_URL, request.callbackUrl);

        PendingIntent denyPending = PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = request.senderName + " wants to share files";
        String text = request.fileCount + " file(s) from " + request.senderIp;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(request.senderName + " (" + request.senderIp + ":"
                                + request.senderPort + ") wants to share "
                                + request.fileCount + " file(s) with you."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_view, "Allow", allowPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", denyPending);

        if (notificationManager != null) {
            notificationManager.notify(request.id, notificationId, builder.build());
            // Store the actual notification ID so cancelNotification can use it
            requestNotificationIds.put(request.id, notificationId);
            Log.d(TAG, "Notification shown for request " + request.id + " (notifId=" + notificationId + ")");
        }
    }

    private void cancelNotification(String requestId) {
        if (notificationManager != null) {
            // Use the stored notification ID — the generator's current value is wrong
            Integer notifId = requestNotificationIds.remove(requestId);
            if (notifId != null) {
                notificationManager.cancel(requestId, notifId);
            }
            // Also try cancelling by tag only (some Android versions)
            notificationManager.cancel(requestId, 0);
        }
    }

    private void cancelAllNotifications() {
        for (String requestId : pendingRequests.keySet()) {
            cancelNotification(requestId);
        }
    }

    // ---------------------------------------------------------------
    // Send rejection back to sender
    // ---------------------------------------------------------------

    /**
     * POST a rejection response to the sender's callback URL on a background thread.
     */
    private void sendRejectionAsync(ShareRequest request) {
        new Thread(() -> {
            try {
                URL url = new URL(request.callbackUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Content-Type", "application/json");

                String payload = "{\"status\":\"denied\",\"requestId\":\""
                        + request.id + "\",\"device\":\""
                        + request.senderName + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Rejection sent to " + request.callbackUrl
                        + " – response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send rejection to " + request.callbackUrl, e);
            }
        }, "ShareReject-" + request.id).start();
    }

    // ---------------------------------------------------------------
    // Broadcast Receiver for notification actions
    // ---------------------------------------------------------------

    /**
     * Static-friendly BroadcastReceiver that handles ALLOW and DENY
     * notification actions.  Delegates to the singleton/active
     * ShareRequestReceiver instance.
     *
     * <p>Because this is registered dynamically (not in the manifest),
     * it only works while the app process is alive – which is the
     * desired behaviour for interactive notifications.</p>
     */
    public class NotificationActionReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(@NonNull Context ctx, @NonNull Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
            if (requestId == null) {
                Log.w(TAG, "Notification action with no request ID");
                return;
            }

            Log.d(TAG, "Notification action: " + action + " for request " + requestId);

            switch (action) {
                case ACTION_ALLOW:
                    allowRequest(requestId);
                    break;

                case ACTION_DENY:
                    denyRequest(requestId);
                    break;

                default:
                    Log.w(TAG, "Unknown notification action: " + action);
            }
        }
    }

    // ---------------------------------------------------------------
    // FileServer integration
    // ---------------------------------------------------------------

    /**
     * Install a {@code /share-request} POST route into the given FileServer.
     *
     * <p>The expected POST body is a JSON object:</p>
     * <pre>
     * {
     *   "senderName": "Alice's Phone",
     *   "senderIp":   "192.168.1.42",
     *   "senderPort": 8988,
     *   "fileCount":  3,
     *   "callbackUrl": "http://192.168.1.42:8988/share-response"
     * }
     * </pre>
     *
     * <p>On success the server returns HTTP 200 with a JSON body
     * containing the generated {@code requestId}.</p>
     *
     * @param server the running FileServer instance.
     */
    public void installShareRequestRoute(FileServer server) {
        if (server == null) {
            Log.w(TAG, "Cannot install route: FileServer is null");
            return;
        }
        server.setShareRequestHandler((senderName, senderIp, senderPort, fileCount, callbackUrl) -> {
            String requestId = handleIncomingRequest(senderName, senderIp, senderPort, fileCount, callbackUrl);
            return requestId;
        });
        Log.d(TAG, "Share request route installed on FileServer");
    }

    /**
     * Remove the share-request route from the FileServer.
     */
    public void removeShareRequestRoute(FileServer server) {
        if (server != null) {
            server.setShareRequestHandler(null);
            Log.d(TAG, "Share request route removed from FileServer");
        }
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private void notifyListener(Runnable r) {
        mainHandler.post(r);
    }
}
//