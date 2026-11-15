package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import local.jiege.hook.common.Log;

/**
 * GR port (PME110): the GR feature presenter is never created because the adapted
 * feature protobuf has no {@code gr} mode-table entry — the same defect as POP's
 * missing retro entry (see PopFeatureInject).
 *
 * The chain: FeatureManager looks up the gr mode config, falls back to common, and
 * the common feature list lacks "com.oplus.camera.feature.gr_photo", so the
 * FeatureFactory never creates {@code Llg/a} (the GR presenter, case 80). Without
 * the presenter the Std button has no handler and the shutter may depend on
 * presenter-initialised state — both dead even though GrMode itself is created.
 *
 * This hook injects the gr_photo feature key into the returned set, so the factory
 * creates the presenter. Same pattern as PopFeatureInject (proven working).
 */
final class GrFeatureInject {
    private static final String TAG = "RicohGrPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String GR_FEATURE = "com.oplus.camera.feature.gr_photo";

    private GrFeatureInject() {}

    static void install(ClassLoader classLoader) {
        Class<?> adapter = XposedHelpers.findClass(SDK + "config.FeatureConfigureAdapter", classLoader);
        XposedHelpers.findAndHookMethod(adapter, "getFeatureInfoList", int.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof Set)) return;
                    @SuppressWarnings("unchecked")
                    Set<Object> keys = (Set<Object>) result;
                    if (keys.contains(GR_FEATURE)) return;
                    keys.add(GR_FEATURE);
                    Log.i(TAG, "GR feature injected into feature list");
                }
            });
        Log.i(TAG, "GR feature inject installed");
    }
}
