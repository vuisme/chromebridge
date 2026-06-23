package dev.local.chromeuabridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Minimal Chrome DevTools Protocol client over a local abstract Unix socket.
 * Speaks the WebSocket framing expected by {@code chrome_devtools_remote}.
 */
final class Cdp implements Closeable {
    private final LocalSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private final SecureRandom random = new SecureRandom();
    private int nextId = 1;

    private Cdp(LocalSocket socket) throws Exception {
        this.socket = socket;
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    static Cdp connect(String abstractName) throws Exception {
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(
                abstractName, LocalSocketAddress.Namespace.ABSTRACT));
        Cdp cdp = new Cdp(socket);
        cdp.handshake();
        return cdp;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private void handshake() throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request = "GET /devtools/browser HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String response = readHttpHeader();
        if (!response.contains(" 101 ")) {
            throw new IllegalStateException("WebSocket handshake failed: " + response);
        }
    }

    JSONObject call(String method, JSONObject params, String sessionId) throws Exception {
        int id = nextId++;
        JSONObject request = new JSONObject()
                .put("id", id)
                .put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        if (sessionId != null) {
            request.put("sessionId", sessionId);
        }

        sendText(request.toString());
        while (true) {
            JSONObject message = new JSONObject(readText());
            if (message.optInt("id") == id) {
                if (message.has("error")) {
                    throw new IllegalStateException(message.getJSONObject("error").toString());
                }
                return message;
            }
        }
    }

    private String readHttpHeader() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int matched = 0;
        int[] end = new int[]{'\r', '\n', '\r', '\n'};
        while (true) {
            int b = input.read();
            if (b < 0) {
                throw new IllegalStateException("EOF reading HTTP header");
            }
            out.write(b);
            matched = (b == end[matched]) ? matched + 1 : 0;
            if (matched == end.length) {
                return out.toString("US-ASCII");
            }
        }
    }

    private void sendText(String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);

        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xffff) {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xff);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(0x80 | 127);
            ByteBuffer len = ByteBuffer.allocate(8).putLong(payload.length);
            frame.write(len.array());
        }

        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }

        output.write(frame.toByteArray());
        output.flush();
    }

    private String readText() throws Exception {
        ByteArrayOutputStream message = new ByteArrayOutputStream();

        while (true) {
            int b0 = input.read();
            int b1 = input.read();
            if (b0 < 0 || b1 < 0) {
                throw new IllegalStateException("EOF reading websocket frame");
            }

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;

            if (len == 126) {
                len = ((long) input.read() << 8) | input.read();
            } else if (len == 127) {
                byte[] bytes = readExactly(8);
                len = ByteBuffer.wrap(bytes).getLong();
            }

            byte[] mask = masked ? readExactly(4) : null;
            byte[] payload = readExactly((int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }

            if (opcode == 0x8) {
                throw new IllegalStateException("WebSocket closed");
            }
            if (opcode == 0x1 || opcode == 0x0) {
                message.write(payload);
                if (fin) {
                    return message.toString("UTF-8");
                }
            }
        }
    }

    private byte[] readExactly(int len) throws Exception {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int read = input.read(data, off, len - off);
            if (read < 0) {
                throw new IllegalStateException("EOF");
            }
            off += read;
        }
        return data;
    }
}
