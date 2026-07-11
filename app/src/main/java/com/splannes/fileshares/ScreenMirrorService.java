package com.splannes.fileshares;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen Mirroring Service with H.264 encoding via MediaCodec.
 *
 * Architecture:
 * - MediaProjection captures screen via VirtualDisplay
 * - Mode 1 (H.264): VirtualDisplay → MediaCodec encoder Surface → H.264 NAL units → WebSocket
 * - Mode 2 (JPEG fallback): VirtualDisplay → ImageReader → Bitmap → JPEG → WebSocket
 * - Frames are pushed to MirrorWebSocketServer for real-time delivery
 * - Browser decodes H.264 using WebCodecs API (or JPEG as fallback)
 *
 * FIXES:
 * - Don't send SPS/PPS config-only frames over WebSocket (they crash WebCodecs decoder)
 * - SPS/PPS is prepended to keyframes only
 * - JPEG fallback runs alongside H.264 for HTTP polling clients
 * - Cached keyframe sent to new WebSocket clients immediately
 */
public class ScreenMirrorService extends Service {

    private static final String TAG = "ScreenMirror";
    private static final String CHANNEL_ID = "screen_mirror_channel";
    private static final int NOTIFICATION_ID = 2;

    // Configuration
    private static final int JPEG_QUALITY = 50;
    private static final int VIRTUAL_DISPLAY_DPI = 120;
    private static final int H264_BITRATE = 2_000_000; // 2 Mbps
    private static final int H264_FRAME_RATE = 24;
    private static final int H264_IFRAME_INTERVAL = 2; // seconds (reduced for faster keyframes)
    private static final int JPEG_FALLBACK_INTERVAL_MS = 500; // Produce JPEG every 500ms for HTTP polling

    // State
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay h264VirtualDisplay;
    private VirtualDisplay jpegVirtualDisplay; // Separate VD for JPEG fallback
    private ImageReader imageReader; // JPEG fallback
    private Handler handler;

    // H.264 encoder
    private MediaCodec h264Encoder;
    private Surface encoderSurface;
    private Thread encoderThread;
    private final AtomicBoolean encoderRunning = new AtomicBoolean(false);

    private int resultCode;
    private Intent resultData;
    private String authToken;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // WebSocket server for frame delivery
    private MirrorWebSocketServer wsServer;

    // JPEG fallback runner
    private Runnable jpegFallbackRunnable;

    // ==========================================
    // SERVICE LIFECYCLE
    // ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            resultCode = intent.getIntExtra("resultCode", 0);
            resultData = intent.getParcelableExtra("resultData");
            authToken = intent.getStringExtra("authToken");

            if (resultData == null) {
                Log.e(TAG, "resultData is null — cannot start MediaProjection. Stopping service.");
                stopSelf();
                return START_NOT_STICKY;
            }

            try {
                startForeground(NOTIFICATION_ID, buildNotification());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }
            startScreenCapture();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopScreenCapture();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==========================================
    // SCREEN CAPTURE
    // ==========================================

    private void startScreenCapture() {
        if (resultData == null) {
            Log.e(TAG, "No result data — cannot start projection");
            MirrorFrameBuffer.setStreaming(false);
            return;
        }

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null — permission denied?");
                MirrorFrameBuffer.setStreaming(false);
                return;
            }

            // Get screen dimensions
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;

            // Scale down for streaming performance (max 720p)
            float scale = 1.0f;
            if (screenWidth > 1280) {
                scale = 1280f / screenWidth;
            }
            int captureWidth = Math.round(screenWidth * scale);
            int captureHeight = Math.round(screenHeight * scale);

            // Ensure dimensions are even (required by H.264 encoder)
            captureWidth = captureWidth & ~1;
            captureHeight = captureHeight & ~1;

            // Start WebSocket server for frame delivery
            startWebSocketServer();

            // Start H.264 encoding (primary)
            boolean h264Started = startH264Encoding(captureWidth, captureHeight);

            // Start JPEG fallback (for HTTP polling and browsers without WebCodecs)
            startJPEGFallback(captureWidth, captureHeight);

            isRunning.set(true);
            MirrorFrameBuffer.setStreaming(true);
            Log.d(TAG, "Screen capture started: " + captureWidth + "x" + captureHeight +
                    " (H.264: " + h264Started + ", JPEG fallback: active)");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped — cleaning up");
                    isRunning.set(false);
                    MirrorFrameBuffer.setStreaming(false);
                }
            }, handler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start screen capture", e);
            isRunning.set(false);
            MirrorFrameBuffer.setStreaming(false);
        }
    }

    // ==========================================
    // H.264 ENCODING (Primary — hardware accelerated)
    // ==========================================

    /**
     * Start H.264 encoding using MediaCodec.
     * Creates a Surface from the encoder and feeds it to VirtualDisplay.
     * The encoder produces H.264 NAL units which are sent over WebSocket.
     *
     * KEY FIX: SPS/PPS config-only frames are NOT sent individually.
     * They are stored and prepended to keyframes so the WebCodecs
     * decoder receives complete, decodable frames.
     */
    private boolean startH264Encoding(int width, int height) {
        try {
            h264Encoder = MediaCodec.createEncoderByType("video/avc");
            if (h264Encoder == null) {
                Log.e(TAG, "Could not create H.264 encoder");
                return false;
            }

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, H264_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, H264_FRAME_RATE);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, H264_IFRAME_INTERVAL);

            // Low latency settings for real-time streaming
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0);
            }

            h264Encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = h264Encoder.createInputSurface();
            h264Encoder.start();

            // Create VirtualDisplay with the encoder's input surface
            h264VirtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenMirror-H264",
                    width, height, VIRTUAL_DISPLAY_DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    encoderSurface,
                    null, handler
            );

            // Start encoder output reader thread
            encoderRunning.set(true);
            encoderThread = new Thread(() -> readH264Output(), "H264-Encoder");
            encoderThread.setDaemon(true);
            encoderThread.start();

            Log.d(TAG, "H.264 encoder started: " + width + "x" + height +
                    " @ " + H264_BITRATE + " bps");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start H.264 encoding", e);
            cleanupH264Encoder();
            return false;
        }
    }

    /**
     * Read H.264 NAL units from the MediaCodec encoder output
     * and broadcast them via WebSocket.
     *
     * KEY FIX: Config-only frames (SPS/PPS) are stored but NOT sent
     * as standalone WebSocket frames. They are only prepended to keyframes.
     * Sending SPS/PPS as standalone frames causes WebCodecs decoder errors.
     */
    private void readH264Output() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        byte[] cachedConfig = null;

        while (encoderRunning.get() && isRunning.get()) {
            try {
                int outputBufferIndex = h264Encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex < 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = h264Encoder.getOutputFormat();
                        Log.d(TAG, "H.264 format changed: " + newFormat);
                    }
                    continue;
                }

                ByteBuffer outputBuffer = h264Encoder.getOutputBuffer(outputBufferIndex);
                if (outputBuffer == null) {
                    h264Encoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }

                // Extract the H.264 data
                if (bufferInfo.size > 0) {
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.get(outData);

                    // Determine frame type
                    boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                    if (isConfig) {
                        // Store SPS/PPS config for prepending to keyframes
                        MirrorFrameBuffer.setH264Config(outData);
                        cachedConfig = outData;

                        // IMPORTANT FIX: Do NOT send config-only frames as standalone frames.
                        // SPS/PPS NAL units are not decodable video frames and cause
                        // WebCodecs VideoDecoder errors when treated as such.
                        // The config is prepended to keyframes below.
                        Log.d(TAG, "H.264 config stored (SPS/PPS), NOT sending as standalone frame");
                        h264Encoder.releaseOutputBuffer(outputBufferIndex, false);
                        continue; // Skip sending this frame
                    }

                    if (isKeyFrame) {
                        // Prepend SPS/PPS config to keyframes so decoder can initialize
                        byte[] config = cachedConfig != null ? cachedConfig : MirrorFrameBuffer.getH264Config();
                        if (config != null && config.length > 0) {
                            byte[] combined = new byte[config.length + outData.length];
                            System.arraycopy(config, 0, combined, 0, config.length);
                            System.arraycopy(outData, 0, combined, config.length, outData.length);
                            outData = combined;
                        }

                        // Cache the keyframe for new WebSocket clients
                        if (wsServer != null) {
                            wsServer.cacheKeyFrame(outData);
                        }
                    }

                    // Send H.264 frame via WebSocket
                    if (wsServer != null && wsServer.getClientCount() > 0) {
                        wsServer.broadcastFrame(MirrorWebSocketServer.FRAME_H264, outData);
                    }

                    // Also update MirrorFrameBuffer for HTTP polling fallback
                    MirrorFrameBuffer.putH264Frame(outData, isKeyFrame);
                }

                h264Encoder.releaseOutputBuffer(outputBufferIndex, false);

            } catch (IllegalStateException e) {
                // Encoder was stopped
                Log.d(TAG, "H.264 encoder stopped");
                break;
            } catch (Exception e) {
                Log.e(TAG, "H.264 encoder output error", e);
            }
        }

        Log.d(TAG, "H.264 encoder thread finished");
    }

    // ==========================================
    // JPEG FALLBACK (for HTTP polling clients)
    // ==========================================

    /**
     * Start a JPEG capture using a second VirtualDisplay at lower resolution.
     * This provides frames for the /frame HTTP polling endpoint used by
     * browsers that don't support WebCodecs or when WebSocket fails.
     *
     * Uses a separate VirtualDisplay with a low-framerate ImageReader
     * to minimize resource impact alongside H.264 encoding.
     */
    private void startJPEGFallback(int width, int height) {
        // Use lower resolution for JPEG fallback to save resources
        int jpegWidth = Math.min(width, 640);
        int jpegHeight = Math.round((float) height * jpegWidth / width);
        jpegWidth = jpegWidth & ~1;
        jpegHeight = jpegHeight & ~1;

        // Final copies for lambda (jpegWidth/jpegHeight are reassigned above so not effectively final)
        final int finalJpegWidth = jpegWidth;
        final int finalJpegHeight = jpegHeight;

        imageReader = ImageReader.newInstance(jpegWidth, jpegHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (!isRunning.get()) return;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    captureAndBroadcastJPEG(image, finalJpegWidth, finalJpegHeight);
                }
            } catch (Exception e) {
                Log.e(TAG, "JPEG frame error", e);
            } finally {
                if (image != null) image.close();
            }
        }, handler);

        try {
            jpegVirtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenMirror-JPEG",
                    jpegWidth, jpegHeight, VIRTUAL_DISPLAY_DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, handler
            );
            Log.d(TAG, "JPEG fallback VirtualDisplay started: " + jpegWidth + "x" + jpegHeight);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start JPEG VirtualDisplay", e);
        }
    }

    /**
     * Capture a frame as JPEG and store in MirrorFrameBuffer.
     * Only sends via WebSocket if there are no H.264 clients (pure JPEG fallback).
     */
    private void captureAndBroadcastJPEG(Image image, int width, int height) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;

            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            Bitmap croppedBitmap = (rowPadding == 0) ? bitmap :
                    Bitmap.createBitmap(bitmap, 0, 0, width, height);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            byte[] jpegData = baos.toByteArray();

            if (croppedBitmap != bitmap) croppedBitmap.recycle();
            bitmap.recycle();

            // Send JPEG via WebSocket (for clients in JPEG mode)
            if (wsServer != null && wsServer.getClientCount() > 0) {
                wsServer.broadcastFrame(MirrorWebSocketServer.FRAME_JPEG, jpegData);
            }

            // Push to MirrorFrameBuffer for HTTP polling fallback
            MirrorFrameBuffer.putFrame(jpegData);

        } catch (Exception e) {
            Log.e(TAG, "JPEG capture error", e);
        }
    }

    // ==========================================
    // WEBSOCKET SERVER
    // ==========================================

    private void startWebSocketServer() {
        try {
            wsServer = new MirrorWebSocketServer(MirrorWebSocketServer.DEFAULT_WS_PORT, authToken);
            wsServer.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebSocket server", e);
        }
    }

    private void stopWebSocketServer() {
        if (wsServer != null) {
            wsServer.stop();
            wsServer = null;
        }
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    private void stopScreenCapture() {
        isRunning.set(false);
        encoderRunning.set(false);
        MirrorFrameBuffer.setStreaming(false);

        // FIX: Release VirtualDisplays FIRST (they are producers that write to surfaces).
        // Only release consumers (encoder, ImageReader) AFTER producers are stopped.
        if (h264VirtualDisplay != null) {
            h264VirtualDisplay.release();
            h264VirtualDisplay = null;
        }

        if (jpegVirtualDisplay != null) {
            jpegVirtualDisplay.release();
            jpegVirtualDisplay = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        // Now safe to release consumers (no more data will be produced)
        cleanupH264Encoder();

        // Stop JPEG capture
        if (jpegFallbackRunnable != null) {
            handler.removeCallbacks(jpegFallbackRunnable);
            jpegFallbackRunnable = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        // Stop WebSocket server
        stopWebSocketServer();

        Log.d(TAG, "Screen capture stopped");
    }

    private void cleanupH264Encoder() {
        encoderRunning.set(false);

        if (encoderThread != null) {
            try {
                encoderThread.interrupt();
                encoderThread.join(1000);
            } catch (InterruptedException ignored) {}
            encoderThread = null;
        }

        if (h264Encoder != null) {
            try {
                h264Encoder.stop();
                h264Encoder.release();
            } catch (Exception ignored) {}
            h264Encoder = null;
        }

        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
    }

    // ==========================================
    // NOTIFICATION
    // ==========================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Mirror Active")
                .setContentText("H.264 screen sharing in progress")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }
}
