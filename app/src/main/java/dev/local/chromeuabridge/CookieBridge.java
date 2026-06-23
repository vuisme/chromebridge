package dev.local.chromeuabridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XposedBridge;

/**
 * BroadcastReceiver that handles cookie export/import via ADB commands.
 *
 * <h3>Export (old phone):</h3>
 * <pre>
 * adb shell am broadcast -a dev.local.chromeuabridge.EXPORT_COOKIES
 * adb shell am broadcast -a dev.local.chromeuabridge.EXPORT_COOKIES --es path /sdcard/my_cookies.json
 * adb pull /sdcard/chromeuabridge_cookies.json
 * </pre>
 *
 * <h3>Import (new phone):</h3>
 * <pre>
 * adb push chromeuabridge_cookies.json /sdcard/
 * adb shell am broadcast -a dev.local.chromeuabridge.IMPORT_COOKIES
 * adb shell am broadcast -a dev.local.chromeuabridge.IMPORT_COOKIES --es path /sdcard/my_cookies.json
 * </pre>
 *
 * Cookies are obtained/injected via Chrome DevTools Protocol (already decrypted
 * in memory), so the export file contains plaintext session tokens that bypass
 * hardware Keystore encryption.
 */
public final class CookieBridge extends BroadcastReceiver {
    static final String ACTION_EXPORT = "dev.local.chromeuabridge.EXPORT_COOKIES";
    static final String ACTION_IMPORT = "dev.local.chromeuabridge.IMPORT_COOKIES";
    private static final String DEFAULT_FILE = "chromeuabridge_cookies.json";
    private static final String TAG = "ChromeUaBridge";

    private final String packageName;

    CookieBridge(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Register this receiver in the given application context.
     * Called from the Xposed hook on {@code Application.onCreate}.
     */
    static void register(Context context, String packageName) {
        CookieBridge receiver = new CookieBridge(packageName);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_EXPORT);
        filter.addAction(ACTION_IMPORT);
        context.registerReceiver(receiver, filter);
        XposedBridge.log(TAG + ": cookie bridge registered for " + packageName);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        String path = intent.getStringExtra("path");
        if (path == null || path.isEmpty()) {
            path = new File(Environment.getExternalStorageDirectory(), DEFAULT_FILE)
                    .getAbsolutePath();
        }

        final String filePath = path;
        final PendingResult pending = goAsync();

        new Thread(() -> {
            try {
                if (ACTION_EXPORT.equals(action)) {
                    exportCookies(filePath);
                } else if (ACTION_IMPORT.equals(action)) {
                    importCookies(filePath);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": cookie operation failed: " + t);
            } finally {
                pending.finish();
            }
        }, "CookieBridge").start();
    }

    /**
     * Auto-import: check for backup file on startup and import if found.
     * Called from the Xposed hook after registering the receiver.
     */
    static void autoImport(String packageName) {
        File file = new File(Environment.getExternalStorageDirectory(), DEFAULT_FILE);
        if (!file.isFile()) return;

        XposedBridge.log(TAG + ": found cookie backup at " + file.getAbsolutePath()
                + ", starting auto-import...");

        new Thread(() -> {
            try {
                // Wait for DevTools socket to be available (Chrome needs a moment to start)
                Thread.sleep(8000);
                new CookieBridge(packageName).importCookies(file.getAbsolutePath());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": auto-import failed: " + t);
            }
        }, "CookieBridge-AutoImport").start();
    }

    // ── Export ────────────────────────────────────────────────────────────

    private void exportCookies(String outputPath) throws Exception {
        XposedBridge.log(TAG + ": exporting cookies from " + packageName + " ...");

        Cdp cdp = DevToolsUaKeeper.connectDevTools(packageName);
        try {
            // Get a page target to attach to
            String sessionId = attachToAnyPage(cdp);

            // Enable Network domain so getAllCookies works
            cdp.call("Network.enable", new JSONObject(), sessionId);

            // Get ALL cookies (decrypted, from Chrome's in-memory store)
            JSONObject response = cdp.call("Network.getAllCookies", new JSONObject(), sessionId);
            JSONArray cookies = response.getJSONObject("result").getJSONArray("cookies");

            // Build output
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault());

            JSONObject output = new JSONObject();
            output.put("exportTime", sdf.format(new Date()));
            output.put("browser", packageName);
            output.put("cookieCount", cookies.length());
            output.put("cookies", cookies);

            // Write to file
            writeFile(outputPath, output.toString(2));

            XposedBridge.log(TAG + ": exported " + cookies.length()
                    + " cookies to " + outputPath);
            XposedBridge.log(TAG + ": ⚠ WARNING: file contains plaintext session tokens! "
                    + "Delete after import.");

        } finally {
            cdp.close();
        }
    }

    // ── Import ───────────────────────────────────────────────────────────

    private void importCookies(String inputPath) throws Exception {
        XposedBridge.log(TAG + ": importing cookies into " + packageName + " ...");

        String json = readFile(inputPath);
        JSONObject input = new JSONObject(json);
        JSONArray cookies = input.getJSONArray("cookies");

        if (cookies.length() == 0) {
            XposedBridge.log(TAG + ": no cookies found in " + inputPath);
            return;
        }

        Cdp cdp = DevToolsUaKeeper.connectDevTools(packageName);
        try {
            String sessionId = attachToAnyPage(cdp);
            cdp.call("Network.enable", new JSONObject(), sessionId);

            // Import cookies in batches to avoid overly large CDP messages
            int batchSize = 50;
            int imported = 0;

            for (int i = 0; i < cookies.length(); i += batchSize) {
                JSONArray batch = new JSONArray();
                for (int j = i; j < Math.min(i + batchSize, cookies.length()); j++) {
                    JSONObject cookie = cookies.getJSONObject(j);
                    batch.put(normalizeCookieForImport(cookie));
                }

                JSONObject params = new JSONObject().put("cookies", batch);
                cdp.call("Network.setCookies", params, sessionId);
                imported += batch.length();

                XposedBridge.log(TAG + ": imported " + imported + "/" + cookies.length()
                        + " cookies...");
            }

            XposedBridge.log(TAG + ": ✓ import complete: " + imported + " cookies injected into "
                    + packageName);

            // Rename the backup file so auto-import does not repeat
            File src = new File(inputPath);
            File dst = new File(inputPath + ".imported");
            if (src.renameTo(dst)) {
                XposedBridge.log(TAG + ": renamed " + inputPath + " → " + dst.getName());
            }

        } finally {
            cdp.close();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Attach to the first available HTTP page target.
     * Returns the session ID.
     */
    private String attachToAnyPage(Cdp cdp) throws Exception {
        JSONObject targets = cdp.call("Target.getTargets", null, null);
        JSONArray infos = targets.getJSONObject("result").getJSONArray("targetInfos");

        for (int i = 0; i < infos.length(); i++) {
            JSONObject target = infos.getJSONObject(i);
            if (!"page".equals(target.optString("type"))) continue;

            String targetId = target.getString("targetId");
            JSONObject params = new JSONObject()
                    .put("targetId", targetId)
                    .put("flatten", true);
            JSONObject attached = cdp.call("Target.attachToTarget", params, null);
            return attached.getJSONObject("result").getString("sessionId");
        }

        throw new IllegalStateException("No page target available. Open a tab in Chrome first.");
    }

    /**
     * Normalize a cookie from getAllCookies format to setCookie format.
     * CDP setCookies expects slightly different field names/types.
     */
    private JSONObject normalizeCookieForImport(JSONObject src) throws Exception {
        JSONObject cookie = new JSONObject();
        cookie.put("name", src.getString("name"));
        cookie.put("value", src.getString("value"));
        cookie.put("domain", src.getString("domain"));
        cookie.put("path", src.getString("path"));

        // expires: CDP getAllCookies returns as float (epoch seconds), setCookie expects the same
        if (src.has("expires") && src.getDouble("expires") > 0) {
            cookie.put("expires", src.getDouble("expires"));
        }

        if (src.has("httpOnly")) cookie.put("httpOnly", src.getBoolean("httpOnly"));
        if (src.has("secure")) cookie.put("secure", src.getBoolean("secure"));

        // sameSite: CDP uses "Strict", "Lax", "None"
        if (src.has("sameSite")) {
            cookie.put("sameSite", src.getString("sameSite"));
        }

        // size, session, priority, sameParty are informational — not needed for setCookie

        return cookie;
    }

    private static void writeFile(String path, String content) throws Exception {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private static String readFile(String path) throws Exception {
        File file = new File(path);
        byte[] data = new byte[(int) file.length()];
        FileInputStream fis = new FileInputStream(file);
        try {
            int off = 0;
            while (off < data.length) {
                int read = fis.read(data, off, data.length - off);
                if (read < 0) break;
                off += read;
            }
        } finally {
            fis.close();
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
