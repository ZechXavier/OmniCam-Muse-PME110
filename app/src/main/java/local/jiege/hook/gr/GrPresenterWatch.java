package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;

/**
 * GR presenter-creation diagnostics (PME110, temporary — remove once GR works).
 *
 * GrMode is created and GrFeatureInject injects gr_photo into the feature list, but
 * the GR presenter (Llg/a) is never created and the shutter/Std stay dead. The
 * factory (Lgb/e0;.c) is verified correct (case 80 → new Llg/a), but there is an
 * unresolved contradiction: the gate Lgb/e0;.d delegates to
 * DeprecatedFeaturesForAllDevice (Lgb/a;.b), which only returns true for
 * (panorama + rear + face_detect) — by that logic no presenter should ever be
 * created, yet common mode works fine. Static analysis cannot resolve it.
 *
 * This watch hooks three points to settle it in one run:
 * 1. Factory Lgb/e0;.c — logs featureKey + returned presenter class name.
 * 2. Gate Lgb/e0;.d — logs (entryType, modeName, featureKey) + return value.
 * 3. Llg/a constructor — logs whether a GR presenter is ever new'd.
 *
 * Interpretation:
 * - Factory called with gr_photo and returns Llg/a, but gate returns false
 *   → the gate is the culprit; bypass or fix the gate.
 * - Factory never called with gr_photo → the problem is upstream of the factory
 *   (generateFeatures iteration).
 * - Llg/a constructed → presenter exists; the problem is downstream (binding).
 */
public final class GrPresenterWatch {
    private static final String TAG = "RicohGrPort";

    private GrPresenterWatch() {}

    public static void install(ClassLoader classLoader) {
        try {
            // 1. Factory: Lgb/e0;.c — find by name, log args + result class.
            Class<?> factory = XposedHelpers.findClass("gb.e0", classLoader);
            for (Method m : factory.getDeclaredMethods()) {
                if (!"c".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        String resultName = result == null ? "null" : result.getClass().getName();
                        StringBuilder args = new StringBuilder();
                        for (Object a : param.args) {
                            if (args.length() > 0) args.append(", ");
                            args.append(a);
                        }
                        Log.i(TAG, "factory.c(" + args + ") -> " + resultName);
                    }
                });
            }
            // 2. Gate: Lgb/e0;.d — log (entryType, modeName, featureKey) + return.
            for (Method m : factory.getDeclaredMethods()) {
                if (!"d".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 3) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "gate.d(entryType=" + param.args[0]
                                + " mode=" + param.args[1]
                                + " key=" + param.args[2]
                                + ") -> " + param.getResult());
                    }
                });
            }
            // 3. Llg/a constructor — log every GR presenter creation.
            Class<?> grPresenter = XposedHelpers.findClass("lg.a", classLoader);
            XposedBridge.hookAllConstructors(grPresenter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "GR presenter constructed: lg.a");
                }
            });
            Log.i(TAG, "GR presenter watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "GR presenter watch unavailable", e);
        }
    }
}
