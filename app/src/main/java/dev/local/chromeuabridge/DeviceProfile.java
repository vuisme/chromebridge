package dev.local.chromeuabridge;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;

final class DeviceProfile {
    static final String PATH =
            "/data/misc/29314ea0-e7b4-477e-bf38-67f557ef6fc7/prefs/vn.vichanger.app/device.xml";
    private static final String[] CHROME_VERSIONS_ANDROID_10_PLUS = {
            "139.0.7258.158",
            "140.0.7339.155",
            "141.0.7390.122",
            "142.0.7444.175"
    };
    private static final String[] CHROME_VERSIONS_ANDROID_9_OR_OLDER = {
            "138.0.7204.179"
    };

    String brand = "";
    String model = "";
    String release = "";
    String timezone = "";
    String userAgent = "";
    String androidId = "";
    String serial = "";

    static DeviceProfile load() throws Exception {
        File file = new File(PATH);
        DeviceProfile profile = new DeviceProfile();

        FileInputStream input = new FileInputStream(file);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");

            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"string".equals(parser.getName())) {
                    continue;
                }

                String name = parser.getAttributeValue(null, "name");
                String value = parser.nextText();
                if ("brand".equals(name)) {
                    profile.brand = value;
                } else if ("model".equals(name)) {
                    profile.model = value;
                } else if ("release".equals(name)) {
                    profile.release = value;
                } else if ("timezone".equals(name)) {
                    profile.timezone = value;
                } else if ("user_agent".equals(name)) {
                    profile.userAgent = value;
                } else if ("android_id".equals(name)) {
                    profile.androidId = value;
                } else if ("serial".equals(name)) {
                    profile.serial = value;
                }
            }
        } finally {
            input.close();
        }

        if (profile.userAgent == null || profile.userAgent.length() == 0) {
            throw new IllegalStateException("Missing user_agent in " + PATH);
        }
        return profile;
    }

    String chromeMajor() {
        String version = chromeFullVersion();
        int end = version.indexOf('.');
        return end > 0 ? version.substring(0, end) : version;
    }

    String chromeFullVersion() {
        String[] pool = isAndroid9OrOlder()
                ? CHROME_VERSIONS_ANDROID_9_OR_OLDER
                : CHROME_VERSIONS_ANDROID_10_PLUS;
        int index = Math.floorMod(stableKey().hashCode(), pool.length);
        return pool[index];
    }

    String normalizedUserAgent() {
        String version = chromeFullVersion();
        String marker = "Chrome/";
        int start = userAgent.indexOf(marker);
        if (start < 0) {
            return userAgent;
        }

        start += marker.length();
        int end = userAgent.indexOf(' ', start);
        if (end < 0) {
            end = userAgent.length();
        }

        return userAgent.substring(0, start) + version + userAgent.substring(end);
    }

    private boolean isAndroid9OrOlder() {
        try {
            int major = Integer.parseInt(release.split("\\.")[0]);
            return major <= 9;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String stableKey() {
        return brand + "|" + model + "|" + release + "|" + androidId + "|" + serial;
    }
}
