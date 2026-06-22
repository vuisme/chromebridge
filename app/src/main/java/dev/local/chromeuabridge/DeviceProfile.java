package dev.local.chromeuabridge;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;

final class DeviceProfile {
    static final String PATH =
            "/data/misc/29314ea0-e7b4-477e-bf38-67f557ef6fc7/prefs/vn.vichanger.app/device.xml";

    String brand = "";
    String model = "";
    String release = "";
    String timezone = "";
    String userAgent = "";

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
        String marker = "Chrome/";
        int start = userAgent.indexOf(marker);
        if (start < 0) {
            return "101";
        }

        start += marker.length();
        int end = userAgent.indexOf('.', start);
        if (end < 0) {
            end = userAgent.length();
        }

        String major = userAgent.substring(start, end);
        return major.length() == 0 ? "101" : major;
    }

    String chromeFullVersion() {
        String marker = "Chrome/";
        int start = userAgent.indexOf(marker);
        if (start < 0) {
            return "101.0.4951.61";
        }

        start += marker.length();
        int end = userAgent.indexOf(' ', start);
        if (end < 0) {
            end = userAgent.length();
        }

        return userAgent.substring(start, end);
    }
}
