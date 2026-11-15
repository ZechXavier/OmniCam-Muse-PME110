package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * GR shutter-dispatch diagnostics (PME110, temporary).
 *
 * GrMode is created but the shutter press produces no GR activity. This watch logs
 * every BaseMode.Yf stage dispatch (mode class + stage w) so one shutter press in
 * GR settles whether the event reaches the mode at all, and whether w==3 (capture
 * stage) is delivered.
 */
public final class GrDispatchWatch {
    private static final String TAG = "RicohGrPort";

    private GrDispatchWatch() {}

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> stage = XposedHelpers.findClass(
                    "com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlRequest.EventMessageCaptureRequestStage", classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.module.BaseMode", classLoader, "Yf", stage,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            int w = -999;
                            try {
                                w = XposedHelpers.getIntField(param.args[0], "w");
                            } catch (Throwable ignored) {
                                // Field name may differ; -999 marks unknown.
                            }
                            Log.i(TAG, "Yf: mode=" + param.thisObject.getClass().getName()
                                    + " w=" + w);
                        }
                    });
            Log.i(TAG, "GR dispatch watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "GR dispatch watch unavailable", e);
        }
    }
}
