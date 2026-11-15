package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import local.jiege.hook.common.FeatureModeTracker;
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
 *
 * MODE SCOPING (v4.4.4-pme110.14, blocking fix): the injection is restricted to the
 * gr mode only, via FeatureModeTracker. In .13 the unscoped injection (all modes)
 * corrupted FeatureManager's presenter generation and caused
 * CameraMenuContainerBottom.h0() to NPE on every back press (9 crashes in one
 * session) — the filter presenter went missing in some mode. Never inject unscoped.
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
                    // Mode-scoped: only inject when FeatureManager is generating the gr mode.
                    if (!FeatureModeTracker.isMode("gr")) return;
                    Object result = param.getResult();
                    if (!(result instanceof Set)) return;
                    @SuppressWarnings("unchecked")
                    Set<Object> keys = (Set<Object>) result;
                    if (keys.contains(GR_FEATURE)) return;
                    keys.add(GR_FEATURE);
                    Log.i(TAG, "GR feature injected into feature list (mode=gr)");
                }
            });
        Log.i(TAG, "GR feature inject installed");
    }
}
