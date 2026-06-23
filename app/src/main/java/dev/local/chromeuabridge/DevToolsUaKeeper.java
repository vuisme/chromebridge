package dev.local.chromeuabridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

final class DevToolsUaKeeper implements Runnable {
    private final String packageName;
    private String loggedProfilePath = "";

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
            if (!profile.sourcePath.equals(loggedProfilePath)) {
                loggedProfilePath = profile.sourcePath;
                XposedBridge.log("ChromeUaBridge: using vichanger profile " + loggedProfilePath);
            }
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
        return connectDevTools(packageName);
    }

    static Cdp connectDevTools(String packageName) throws Exception {
        Throwable last = null;
        for (String socket : socketCandidates(packageName)) {
            try {
                return Cdp.connect(socket);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("No DevTools socket for " + packageName, last);
    }

    static String[] socketCandidates(String packageName) {
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
}
