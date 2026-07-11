package com.splannes.fileshares;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe shared buffer between ScreenMirrorService and FileServer/WebSocket.
 *
 * Supports both JPEG and H.264 frame types:
 * - JPEG: For HTTP polling fallback (legacy compatibility)
 * - H.264: For WebSocket streaming (primary real-time path)
 *
 * Frame distribution:
 * 1. WebSocket (primary): Real-time push to browser — no polling, no latency
 * 2. HTTP /frame (fallback): Single JPEG frame for browsers without WebSocket
 * 3. HTTP /stream (legacy): MJPEG stream — broken in Chrome, kept for old browsers
 */
public class MirrorFrameBuffer {

    private static volatile boolean streaming = false;
    private static volatile byte[] latestJpeg;
    private static volatile byte[] latestH264Frame;
    private static volatile boolean latestH264IsKeyFrame;

    // H.264 SPS/PPS config data (sent with each keyframe for decoder init)
    private static volatile byte[] h264ConfigData;

    private static final List<FrameListener> listeners = new CopyOnWriteArrayList<>();

    public interface FrameListener {
        void onFrame(byte[] mjpegChunk);
    }

    // ==========================================
    // JPEG FRAMES (for HTTP polling fallback)
    // ==========================================

    /**
     * Push a new JPEG frame. Called by ScreenMirrorService.
     * Formats the JPEG as an MJPEG chunk for HTTP streaming fallback,
     * and stores it for the /frame polling endpoint.
     */
    public static void putFrame(byte[] jpeg) {
        if (jpeg == null || !streaming) return;
        latestJpeg = jpeg;

        // Format as MJPEG chunk for legacy HTTP streaming:
        // --frame\r\n
        // Content-Type: image/jpeg\r\n
        // Content-Length: NNN\r\n
        // \r\n
        // <jpeg bytes>
        // \r\n
        try {
            String header = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n";
            byte[] headerBytes = header.getBytes("UTF-8");
            byte[] chunk = new byte[headerBytes.length + jpeg.length + 2];
            System.arraycopy(headerBytes, 0, chunk, 0, headerBytes.length);
            System.arraycopy(jpeg, 0, chunk, headerBytes.length, jpeg.length);
            chunk[chunk.length - 2] = '\r';
            chunk[chunk.length - 1] = '\n';

            for (FrameListener l : listeners) {
                l.onFrame(chunk);
            }
        } catch (Exception e) {
            // Ignore encoding errors
        }
    }

    public static byte[] getLatestFrame() {
        return latestJpeg;
    }

    // ==========================================
    // H.264 FRAMES (for WebSocket streaming)
    // ==========================================

    /**
     * Store an H.264 frame for potential HTTP access.
     * Primary delivery is via WebSocket (handled by MirrorWebSocketServer).
     */
    public static void putH264Frame(byte[] h264Data, boolean isKeyFrame) {
        if (h264Data == null || !streaming) return;
        latestH264Frame = h264Data;
        latestH264IsKeyFrame = isKeyFrame;
    }

    public static byte[] getLatestH264Frame() {
        return latestH264Frame;
    }

    public static boolean isLatestH264KeyFrame() {
        return latestH264IsKeyFrame;
    }

    /**
     * Store H.264 SPS/PPS config data.
     * This is prepended to keyframes so the decoder can initialize.
     */
    public static void setH264Config(byte[] config) {
        h264ConfigData = config;
    }

    public static byte[] getH264Config() {
        return h264ConfigData;
    }

    // ==========================================
    // COMMON
    // ==========================================

    public static void addListener(FrameListener l) {
        listeners.add(l);
    }

    public static void removeListener(FrameListener l) {
        listeners.remove(l);
    }

    public static boolean isStreaming() {
        return streaming;
    }

    public static void setStreaming(boolean s) {
        streaming = s;
        if (!s) {
            latestJpeg = null;
            latestH264Frame = null;
            h264ConfigData = null;
        }
    }

    /**
     * InputStream that reads MJPEG frames from the buffer.
     * Each browser client gets its own instance.
     * Used by the legacy /stream endpoint.
     */
    public static class MjpegInputStream extends InputStream implements FrameListener {
        private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(3);
        private volatile boolean active = true;
        private ByteArrayInputStream currentChunk;

        @Override
        public void onFrame(byte[] mjpegChunk) {
            // Non-blocking offer: if queue is full, drop oldest frame
            if (!frameQueue.offer(mjpegChunk)) {
                frameQueue.poll(); // Drop oldest
                frameQueue.offer(mjpegChunk);
            }
        }

        public void closeStream() {
            active = false;
            frameQueue.offer(new byte[0]); // Poison pill to unblock
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n > 0 ? b[0] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (active) {
                // If we have data in current chunk, read it
                if (currentChunk != null) {
                    int n = currentChunk.read(b, off, len);
                    if (n > 0) return n;
                    currentChunk = null; // Chunk exhausted, get next
                }

                // Wait for next frame
                try {
                    byte[] chunk = frameQueue.poll(2, TimeUnit.SECONDS);
                    if (chunk == null) {
                        // Timeout — check if still active and streaming
                        if (!streaming) return -1;
                        continue;
                    }
                    if (chunk.length == 0) return -1; // Poison pill
                    currentChunk = new ByteArrayInputStream(chunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            active = false;
            MirrorFrameBuffer.removeListener(this);
            frameQueue.clear();
            super.close();
        }
    }
}
