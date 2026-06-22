package dev.local.chromeuabridge;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final String[] SUPPORTED_PACKAGES = {
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.chromium.chrome",
            "org.chromium.chromium",
            "org.chromium.webview_shell",
            "com.android.webview",
            "com.google.android.webview",
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.vivaldi.browser",
            "com.vivaldi.browser.sopranos",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.kiwibrowser.browser",
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
            "com.coccoc.trinhduyet",
            "com.coccoc.trinhduyet_beta",
            "org.lineageos.jelly"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!isSupportedPackage(lpparam.packageName)) {
            return;
        }

        String process = lpparam.processName == null ? "" : lpparam.processName;
        if (!lpparam.packageName.equals(process)
                && !(lpparam.packageName + ":privileged_process0").equals(process)
                && !(lpparam.packageName + ":webview_service").equals(process)) {
            return;
        }

        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        XposedBridge.log("ChromeUaBridge: starting in " + process);
        Thread worker = new Thread(new DevToolsUaKeeper(lpparam.packageName), "ChromeUaBridge");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isSupportedPackage(String packageName) {
        for (String supportedPackage : SUPPORTED_PACKAGES) {
            if (supportedPackage.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
