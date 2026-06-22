package dev.local.chromeuabridge;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.chrome".equals(lpparam.packageName)) {
            return;
        }

        String process = lpparam.processName == null ? "" : lpparam.processName;
        if (!"com.android.chrome".equals(process)
                && !"com.android.chrome:privileged_process0".equals(process)) {
            return;
        }

        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        XposedBridge.log("ChromeUaBridge: starting in " + process);
        Thread worker = new Thread(new DevToolsUaKeeper(), "ChromeUaBridge");
        worker.setDaemon(true);
        worker.start();
    }
}
