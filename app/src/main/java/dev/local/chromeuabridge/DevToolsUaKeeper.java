package dev.local.chromeuabridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

final class DevToolsUaKeeper implements Runnable {
    private static final String DEVTOOLS_SOCKET = "chrome_devtools_remote";
    private final SecureRandom random = new SecureRandom();
    private final String packageName;

    DevToolsUaKeeper(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public void run() {
        while (true) {
            try {
                runLoop();
            } catch (Throwable t) {
                XposedBridge.log("ChromeUaBridge: " + t);
                sleep(3000);
            }
        }
    }

    private void runLoop() throws Exception {
        Cdp cdp = connectDevTools();
        Map<String, String> sessions = new HashMap<>();
        int loops = 0;

        while (true) {
            DeviceProfile profile = DeviceProfile.load();
            JSONObject targets = cdp.call("Target.getTargets", null, null);
            JSONArray infos = targets.getJSONObject("result").getJSONArray("targetInfos");

            for (int i = 0; i < infos.length(); i++) {
                JSONObject target = infos.getJSONObject(i);
                if (!"page".equals(target.optString("type"))) {
                    continue;
                }

                String url = target.optString("url");
                if (!url.startsWith("http")) {
                    continue;
                }

                String targetId = target.getString("targetId");
                String sessionId = sessions.get(targetId);
                if (sessionId == null) {
                    JSONObject params = new JSONObject()
                            .put("targetId", targetId)
                            .put("flatten", true);
                    JSONObject attached = cdp.call("Target.attachToTarget", params, null);
                    sessionId = attached.getJSONObject("result").getString("sessionId");
                    sessions.put(targetId, sessionId);
                    XposedBridge.log("ChromeUaBridge: attached " + url);
                }

                cdp.call("Network.enable", new JSONObject(), sessionId);
                JSONObject override = buildOverride(profile);
                cdp.call("Network.setUserAgentOverride", override, sessionId);
                cdp.call("Emulation.setUserAgentOverride", override, sessionId);
                applyTimezone(cdp, profile, sessionId);
            }

            if ((loops++ % 10) == 0) {
                XposedBridge.log("ChromeUaBridge: applied UA to " + sessions.size()
                        + " target(s)");
            }

            sleep(2000);
        }
    }

    private JSONObject buildOverride(DeviceProfile profile) throws Exception {
        String major = profile.chromeMajor();
        String fullVersion = profile.chromeFullVersion();
        JSONArray brands = new JSONArray()
                .put(new JSONObject().put("brand", "Not)A;Brand").put("version", "8"))
                .put(new JSONObject().put("brand", "Chromium").put("version", major))
                .put(new JSONObject().put("brand", "Google Chrome").put("version", major));
        JSONArray fullVersionList = new JSONArray()
                .put(new JSONObject().put("brand", "Not)A;Brand").put("version", "8.0.0.0"))
                .put(new JSONObject().put("brand", "Chromium").put("version", fullVersion))
                .put(new JSONObject().put("brand", "Google Chrome").put("version", fullVersion));

        JSONObject metadata = new JSONObject()
                .put("brands", brands)
                .put("fullVersion", fullVersion)
                .put("fullVersionList", fullVersionList)
                .put("platform", "Android")
                .put("platformVersion", profile.release)
                .put("architecture", "")
                .put("model", profile.model)
                .put("mobile", true)
                .put("bitness", "")
                .put("wow64", false)
                .put("formFactors", new JSONArray().put("Mobile"));

        return new JSONObject()
                .put("userAgent", profile.normalizedUserAgent())
                .put("acceptLanguage", "en-US,en")
                .put("platform", "Linux armv81")
                .put("userAgentMetadata", metadata);
    }

    private Cdp connectDevTools() throws Exception {
        Throwable last = null;
        for (String socket : socketCandidates()) {
            try {
                return Cdp.connect(socket);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("No DevTools socket for " + packageName, last);
    }

    private String[] socketCandidates() {
        if ("com.android.chrome".equals(packageName)
                || packageName.startsWith("com.chrome.")
                || packageName.startsWith("org.chromium.")
                || packageName.startsWith("com.brave.")
                || packageName.startsWith("com.vivaldi.")
                || packageName.startsWith("com.opera.")
                || packageName.startsWith("com.microsoft.emmx")
                || packageName.startsWith("com.kiwibrowser.")
                || packageName.startsWith("com.sec.android.app.sbrowser")
                || packageName.startsWith("com.coccoc.")) {
            return new String[]{
                    "chrome_devtools_remote",
                    packageName + "_devtools_remote",
                    "webview_devtools_remote"
            };
        }
        return new String[]{
                "webview_devtools_remote",
                packageName + "_devtools_remote",
                "chrome_devtools_remote"
        };
    }

    private void applyTimezone(Cdp cdp, DeviceProfile profile, String sessionId) {
        if (profile.timezone == null || profile.timezone.length() == 0) {
            return;
        }

        try {
            cdp.call("Emulation.setTimezoneOverride",
                    new JSONObject().put("timezoneId", profile.timezone),
                    sessionId);
        } catch (Throwable t) {
            XposedBridge.log("ChromeUaBridge: timezone override failed: " + t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Cdp {
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
}
