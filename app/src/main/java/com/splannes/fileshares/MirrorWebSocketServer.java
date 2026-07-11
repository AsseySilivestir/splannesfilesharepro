package com.splannes.fileshares;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import android.util.Base64;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight WebSocket server for real-time screen mirror streaming.
 * Runs on a separate port from the main HTTP server.
 *
 * Supports:
 * - Standard WebSocket handshake (RFC 6455)
 * - Binary frame sending (server → client) for JPEG and H.264 data
 * - Ping/pong keep-alive
 * - Auto-cleanup of disconnected clients
 * - Cached keyframe sent to new clients immediately
 *
 * FIXES:
 * - When a new client connects, send the cached H.264 keyframe first
 *   so the decoder can initialize without waiting for the next I-frame
 */
public class MirrorWebSocketServer {

    private static final String TAG = "MirrorWS";
    public static final int DEFAULT_WS_PORT = 8989;

    private ServerSocket serverSocket;
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int port;
    private String authToken;
    private Thread acceptThread;

    // Frame type constants
    public static final int FRAME_JPEG = 1;
    public static final int FRAME_H264 = 2;

    // Cached keyframe for new clients (so they don't wait for next I-frame)
    private volatile byte[] cachedKeyFrame = null;

    public MirrorWebSocketServer(int port, String authToken) {
        this.port = port;
        this.authToken = authToken != null ? authToken : "";
    }

    public void start() {
        if (running.get()) return;
        running.set(true);

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.d(TAG, "WebSocket server started on port " + port);

                while (running.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        ClientHandler handler = new ClientHandler(client);
                        clients.add(handler);
                        handler.start();
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Accept error", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not start WebSocket server on port " + port, e);
            }
        }, "WS-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        for (ClientHandler client : clients) {
            client.close();
        }
        clients.clear();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        cachedKeyFrame = null;
        Log.d(TAG, "WebSocket server stopped");
    }

    /**
     * Cache a keyframe for sending to new clients.
     * Called by ScreenMirrorService when a keyframe is produced.
     */
    public void cacheKeyFrame(byte[] keyFrameData) {
        cachedKeyFrame = keyFrameData;
    }

    /**
     * Broadcast a binary frame (JPEG or H.264) to all connected clients.
     * Includes a frame type prefix byte so the client knows the format.
     *
     * Frame format: [1 byte type] [4 bytes timestamp] [N bytes payload]
     *   type: 1 = JPEG, 2 = H.264
     *   timestamp: big-endian int64 millis
     */
    public void broadcastFrame(int frameType, byte[] payload) {
        if (clients.isEmpty()) return;

        // Build frame with header: type(1) + timestamp(8) + payload
        long timestamp = System.currentTimeMillis();
        byte[] frame = new byte[1 + 8 + payload.length];
        frame[0] = (byte) frameType;
        ByteBuffer.wrap(frame, 1, 8).putLong(timestamp);
        System.arraycopy(payload, 0, frame, 9, payload.length);

        for (ClientHandler client : clients) {
            client.sendBinaryFrame(frame);
        }
    }

    public int getClientCount() {
        return clients.size();
    }

    public boolean isRunning() {
        return running.get();
    }

    // ==========================================
    // CLIENT HANDLER
    // ==========================================

    private class ClientHandler extends Thread {
        private final Socket socket;
        private volatile OutputStream out;
        private volatile boolean connected = false;

        ClientHandler(Socket socket) {
            this.socket = socket;
            setName("WS-Client-" + socket.getRemoteSocketAddress());
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();

                // 1. Read HTTP upgrade request
                String request = readHttpRequest(in);
                if (request == null) {
                    close();
                    return;
                }

                // 2. Validate auth token (check URL path)
                if (!validateRequest(request)) {
                    sendHttpForbidden(out);
                    close();
                    return;
                }

                // 3. Compute WebSocket accept key
                String wsKey = extractHeader(request, "Sec-WebSocket-Key");
                if (wsKey == null) {
                    close();
                    return;
                }

                // 4. Send handshake response
                String acceptKey = computeAcceptKey(wsKey);
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                        "\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                connected = true;

                Log.d(TAG, "WebSocket client connected: " + socket.getRemoteSocketAddress());

                // 5. Send cached keyframe to new client immediately
                // This allows the H.264 decoder to initialize without waiting
                // for the next I-frame interval
                byte[] keyFrame = cachedKeyFrame;
                if (keyFrame != null && keyFrame.length > 0) {
                    broadcastFrameToThisClient(FRAME_H264, keyFrame);
                    Log.d(TAG, "Sent cached keyframe to new client (" + keyFrame.length + " bytes)");
                }

                // 6. Read client messages (ping, close, etc.)
                while (connected && running.get()) {
                    int firstByte = in.read();
                    if (firstByte == -1) break;

                    int opcode = firstByte & 0x0F;
                    boolean fin = (firstByte & 0x80) != 0;

                    // Read payload length
                    int secondByte = in.read();
                    if (secondByte == -1) break;
                    boolean masked = (secondByte & 0x80) != 0;
                    long payloadLength = secondByte & 0x7F;

                    if (payloadLength == 126) {
                        payloadLength = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                    } else if (payloadLength == 127) {
                        payloadLength = 0;
                        for (int i = 0; i < 8; i++) {
                            payloadLength = (payloadLength << 8) | (in.read() & 0xFF);
                        }
                    }

                    // Read masking key (client frames MUST be masked per RFC 6455)
                    byte[] maskKey = null;
                    if (masked) {
                        maskKey = new byte[4];
                        in.read(maskKey);
                    }

                    // Read payload
                    byte[] payload = new byte[(int) payloadLength];
                    int totalRead = 0;
                    while (totalRead < payload.length) {
                        int read = in.read(payload, totalRead, payload.length - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }

                    // Unmask if needed
                    if (masked && maskKey != null) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= maskKey[i % 4];
                        }
                    }

                    // Handle opcode
                    switch (opcode) {
                        case 0x8: // Close
                            sendCloseFrame();
                            connected = false;
                            break;
                        case 0x9: // Ping → Pong
                            sendPongFrame(payload);
                            break;
                        case 0xA: // Pong
                            break;
                        case 0x1: // Text
                            String msg = new String(payload, StandardCharsets.UTF_8);
                            Log.d(TAG, "Received text: " + msg);
                            // Client can request keyframe with "keyframe" message
                            if ("keyframe".equalsIgnoreCase(msg) && cachedKeyFrame != null) {
                                broadcastFrameToThisClient(FRAME_H264, cachedKeyFrame);
                            }
                            break;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Client handler error: " + e.getMessage());
            } finally {
                connected = false;
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
                Log.d(TAG, "WebSocket client disconnected");
            }
        }

        /**
         * Send a frame to ONLY this client (used for cached keyframe on connect).
         */
        private void broadcastFrameToThisClient(int frameType, byte[] payload) {
            if (!connected || out == null) return;
            try {
                long timestamp = System.currentTimeMillis();
                byte[] frame = new byte[1 + 8 + payload.length];
                frame[0] = (byte) frameType;
                ByteBuffer.wrap(frame, 1, 8).putLong(timestamp);
                System.arraycopy(payload, 0, frame, 9, payload.length);

                ByteBuffer wsFrame = encodeWebSocketFrame((byte) 0x02, frame);
                synchronized (out) {
                    out.write(wsFrame.array());
                    out.flush();
                }
            } catch (IOException e) {
                connected = false;
                clients.remove(this);
            }
        }

        /**
         * Send a binary WebSocket frame to the client.
         * Server → client frames are NOT masked per RFC 6455.
         */
        void sendBinaryFrame(byte[] data) {
            if (!connected || out == null) return;
            try {
                ByteBuffer frame = encodeWebSocketFrame((byte) 0x02, data);
                synchronized (out) {
                    out.write(frame.array());
                    out.flush();
                }
            } catch (IOException e) {
                connected = false;
                clients.remove(this);
            }
        }

        private void sendPongFrame(byte[] payload) throws IOException {
            if (out == null) return;
            ByteBuffer frame = encodeWebSocketFrame((byte) 0x0A, payload);
            synchronized (out) {
                out.write(frame.array());
                out.flush();
            }
        }

        private void sendCloseFrame() throws IOException {
            if (out == null) return;
            ByteBuffer frame = encodeWebSocketFrame((byte) 0x08, new byte[0]);
            synchronized (out) {
                out.write(frame.array());
                out.flush();
            }
        }

        private ByteBuffer encodeWebSocketFrame(byte opcode, byte[] payload) {
            int headerSize = 2;
            if (payload.length > 65535) {
                headerSize = 10;
            } else if (payload.length > 125) {
                headerSize = 4;
            }

            ByteBuffer buffer = ByteBuffer.allocate(headerSize + payload.length);
            buffer.put((byte) (0x80 | opcode)); // FIN + opcode

            if (payload.length <= 125) {
                buffer.put((byte) payload.length);
            } else if (payload.length <= 65535) {
                buffer.put((byte) 126);
                buffer.putShort((short) payload.length);
            } else {
                buffer.put((byte) 127);
                buffer.putLong(payload.length);
            }

            buffer.put(payload);
            buffer.flip();
            return buffer;
        }

        void close() {
            connected = false;
            clients.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
        }

        private String readHttpRequest(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[4096];
            int totalRead = 0;

            while (totalRead < 8192) {
                int available = in.available();
                if (available <= 0) available = 1;
                if (available > buffer.length) available = buffer.length;

                int read = in.read(buffer, 0, available);
                if (read == -1) return null;

                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                totalRead += read;

                // Check if we've received the full headers
                String s = sb.toString();
                if (s.contains("\r\n\r\n")) {
                    return s;
                }
            }
            return sb.toString();
        }

        private boolean validateRequest(String request) {
            if (authToken.isEmpty()) return true;

            // Check URL path for token: /ws?token=XXXX
            String firstLine = request.split("\r\n")[0];
            if (firstLine.contains("token=" + authToken)) return true;

            // Also check Sec-WebSocket-Protocol header for token
            String protocol = extractHeader(request, "Sec-WebSocket-Protocol");
            if (protocol != null && protocol.contains(authToken)) return true;

            Log.w(TAG, "WebSocket auth failed");
            return false;
        }

        private String extractHeader(String request, String headerName) {
            for (String line : request.split("\r\n")) {
                if (line.toLowerCase().startsWith(headerName.toLowerCase() + ":")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
            return null;
        }

        private void sendHttpForbidden(OutputStream out) throws IOException {
            String response = "HTTP/1.1 403 Forbidden\r\n" +
                    "Content-Length: 9\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "Forbidden";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private String computeAcceptKey(String wsKey) {
            try {
                String guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] hash = sha1.digest((wsKey + guid).getBytes(StandardCharsets.UTF_8));
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
