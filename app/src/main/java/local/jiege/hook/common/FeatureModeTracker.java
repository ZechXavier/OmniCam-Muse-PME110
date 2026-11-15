package local.jiege.hook.common;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * Tracks the camera mode currently being set up by FeatureManager.
 *
 * The feature-inject hooks (GrFeatureInject, PopFeatureInject, HighPixelFeatureInject)
 * must only inject their feature key when the target mode is being generated. Injecting
 * into ALL modes corrupts FeatureManager's presenter generation: in v4.4.4-pme110.13
 * the unscoped injection caused the filter presenter to go missing in some mode, and
 * CameraMenuContainerBottom.h0() NPE'd on every back press (9 crashes in one session).
 *
 * This tracker hooks FeatureManager.generateFeatures (Lgb/e1;.f), whose second
 * argument is the mode name string, and records it in a volatile field. Inject hooks
 * read it before adding their key.
 *
 * Signature confirmed from 7.006.121 dex (classes.txt:1079507):
 *   Lgb/e1;.f(Landroid/app/Activity;Ljava/lang/String;IILcom/oplus/camera/filter/IEffectProcessor;Lcom/oplus/camera/module/BaseMode;Lip/a;)V
 *
 * The method is resolved by name + parameter shape (7 params, param[1] is String)
 * rather than exact parameter types, so obfuscated parameter classes (ip.a) do not
 * need to be loadable by name.
 */
public final class FeatureModeTracker {
    private static final String TAG = "FeatureMode";
    private static volatile String currentMode = "";

    private FeatureModeTracker() {}

    /** Returns the mode name most recently passed to FeatureManager.generateFeatures. */
    public static String currentMode() {
        return currentMode;
    }

    /** True when the mode currently being generated matches {@code mode}. */
    public static boolean isMode(String mode) {
        return mode != null && mode.equals(currentMode);
    }

    public static void install(ClassLoader classLoader) {
        Class<?> featureManager = XposedHelpers.findClass("gb.e1", classLoader);
        Method target = null;
        for (Method m : featureManager.getDeclaredMethods()) {
            if (!"f".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 7) continue;
            if (params[1] != String.class) continue;
            target = m;
            break;
        }
        if (target == null) {
            throw new IllegalStateException("FeatureManager.generateFeatures (gb.e1.f) not found");
        }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object modeArg = param.args[1];
                if (modeArg instanceof String) {
                    currentMode = (String) modeArg;
                }
            }
        });
        Log.i(TAG, "feature mode tracker installed");
    }
}
