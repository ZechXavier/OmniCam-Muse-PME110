package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * GR mode-creation diagnostics (PME110, temporary — remove once GR is confirmed working).
 *
 * The .10 diagnostics showed zero GR activity logs, but the user never pressed the shutter
 * in GR mode during the diagnostic window, so the logs cannot prove GrMode was not created.
 * This watch logs the factory call, the Q() result and the GrMode construction so one
 * GR entry + one shutter press in the next test settles whether GrMode is created.
 */
public final class GrCreationWatch {
    private static final String TAG = "RicohGrPort";

    private GrCreationWatch() {}

    public static void install(ClassLoader classLoader, Symbols symbols) {
        // 1. Factory: Lem/r3;.a(String) -> BaseMode. Log mode name + result class.
        try {
            XposedHelpers.findAndHookMethod("em.r3", classLoader, "a", String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            Log.i(TAG, "factory.a(" + param.args[0] + ") -> "
                                    + (result == null ? "null" : result.getClass().getName()));
                        }
                    });
            Log.i(TAG, "GR factory watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "GR factory watch unavailable", e);
        }
        // 2. Q() result: confirm the force-TRUE hook actually returns true.
        try {
            XposedBridge.hookMethod(symbols.method("GrSupport.supported"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Q() -> " + param.getResult());
                }
            });
            Log.i(TAG, "GR Q() watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "GR Q() watch unavailable", e);
        }
        // 3. GrMode constructor: confirm the object is actually built.
        try {
            Class<?> grMode = symbols.cls("GrMode");
            XposedBridge.hookAllConstructors(grMode, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "GrMode constructed: " + param.thisObject.getClass().getName());
                }
            });
            Log.i(TAG, "GR constructor watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "GR constructor watch unavailable", e);
        }
    }
}
