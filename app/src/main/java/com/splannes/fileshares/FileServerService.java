package com.splannes.fileshares;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;

/**
 * Thin service wrapper around FileServer.
 * Manages foreground notification, lifecycle, and broadcast notifications.
 *
 * FIX: Now also installs the share-request handler on the FileServer
 * so that /share-request endpoint works when other devices POST to it.
 */
public class FileServerService extends Service {

    private static final String CHANNEL_ID = "file_server_channel";
    private static final int DEFAULT_PORT = 8988;
    private static final String TAG = "FileServerService";

    private volatile FileServer server;
    private String authToken;

    // FIX: Static reference so MainActivity can access the running service
    private static FileServerService instance;

    // Share request receiver (handles allow/deny from other devices)
    private ShareRequestReceiver shareRequestReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        ArrayList<String> uriStrings = intent.getStringArrayListExtra("files");
        ArrayList<String> folderPaths = intent.getStringArrayListExtra("folders");
        int port = intent.getIntExtra("port", DEFAULT_PORT);
        authToken = intent.getStringExtra("authToken");

        String ip = NetworkUtils.getLocalIpAddress(this);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FileShare Pro Running")
                .setContentText("Serving at http://" + ip + ":" + port)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true);

        startForeground(1, builder.build());

        // FIX: Initialize share request receiver here so it's ready
        if (shareRequestReceiver == null) {
            shareRequestReceiver = new ShareRequestReceiver(this);
            shareRequestReceiver.register();
        }

        new Thread(() -> {
            try {
                if (server != null) server.stop();

                File tempDir = new File(getCacheDir(), "upload_temp");
                if (!tempDir.exists()) tempDir.mkdirs();

                server = new FileServer(this, port, tempDir);
                server.setAuthToken(authToken);
                server.setUploadListener((fileName, filePath, fileSize) ->
                        notifyFileReceived(fileName, filePath, fileSize));

                // FIX: Install the share-request handler so /share-request works
                if (shareRequestReceiver != null) {
                    shareRequestReceiver.installShareRequestRoute(server);
                    Log.d(TAG, "Share request route installed on server");
                }

                // Add files
                if (uriStrings != null) {
                    for (String uriStr : uriStrings) {
                        try {
                            Uri uri = Uri.parse(uriStr);
                            String name = getFileName(uri);
                            server.addFile(name, uri);
                        } catch (Exception e) {
                            Log.e(TAG, "Error adding file: " + uriStr, e);
                        }
                    }
                }

                // Add folders
                if (folderPaths != null) {
                    for (String folderPath : folderPaths) {
                        try {
                            File folder = new File(folderPath);
                            if (folder.exists() && folder.isDirectory()) {
                                server.addFolder(folder.getName(), folder);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error adding folder: " + folderPath, e);
                        }
                    }
                }

                server.start();
                Log.d(TAG, "Server started at " + ip + ":" + port);

            } catch (Exception e) {
                Log.e(TAG, "Server start error", e);
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            // FIX: Remove share request route before stopping
            if (shareRequestReceiver != null) {
                shareRequestReceiver.removeShareRequestRoute(server);
            }
            server.stop();
            server = null;
        }
        if (shareRequestReceiver != null) {
            shareRequestReceiver.shutdown();
            shareRequestReceiver = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Add a file to the running server dynamically.
     */
    public void addFileToServer(String name, Uri uri) {
        if (server != null) {
            server.addFile(name, uri);
        }
    }

    /**
     * Add a folder to the running server dynamically.
     */
    public void addFolderToServer(String name, File directory) {
        if (server != null) {
            server.addFolder(name, directory);
        }
    }

    /**
     * Remove a file or folder from the running server.
     */
    public void removeFromServer(String id) {
        if (server != null) {
            server.removeFile(id);
            server.removeFolder(id);
        }
    }

    public String getAuthToken() {
        return authToken;
    }

    public FileServer getFileServer() {
        return server;
    }

    /**
     * FIX: Static method to get the running FileServerService instance.
     * This allows MainActivity to access the server without binding.
     */
    public static FileServerService getInstance() {
        return instance;
    }

    private void notifyFileReceived(String fileName, String filePath, long fileSize) {
        Intent intent = new Intent("FILE_UPLOADED");
        intent.putExtra("fileName", fileName);
        intent.putExtra("filePath", filePath);
        intent.putExtra("fileSize", fileSize);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, "Broadcast sent for: " + fileName);
    }

    private String getFileName(Uri uri) {
        String name = null;
        try {
            String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {}

        if (name == null) {
            String path = uri.getPath();
            if (path != null) {
                name = path.substring(path.lastIndexOf('/') + 1);
            }
        }
        return name != null ? name : "file_" + System.currentTimeMillis();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "File Server", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public static String getLocalIpAddress(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
                int ip = wifiManager.getConnectionInfo().getIpAddress();
                if (ip != 0) return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return NetworkUtils.getLocalIpAddress(context);
    }
}
