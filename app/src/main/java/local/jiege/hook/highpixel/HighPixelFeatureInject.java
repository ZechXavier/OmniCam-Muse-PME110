package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * Hasselblad high-pixel soft-light (PME110): the FilterPresenter never sees the
 * highPixel mode name because the adapted feature protobuf has no highPixel
 * mode-table entry — the same defect as GR/POP (see GrFeatureInject,
 * PopFeatureInject). FeatureManager falls back to the common config, so the
 * FilterPresenter's mode name stays "common", hookSoftLightTypes (which checks
 * mode=="highPixel") never triggers, and u() is never called with mode=highPixel.
 *
 * This hook injects the filter feature key into the highPixel mode's feature set
 * so the FilterPresenter is created with the highPixel mode name. The filter
 * feature key is "com.oplus.camera.feature.filter" (the same key
 * FeatureManager.r() looks up — see rootcause4 §1.2).
 *
 * MODE SCOPING (mandatory): restricted to the highPixel mode only, via
 * FeatureModeTracker. Unscoped injection corrupted FeatureManager's presenter
 * generation in .13 and caused CameraMenuContainerBottom.h0() to NPE on every
 * back press. Never inject unscoped.
 */
final class HighPixelFeatureInject {
    private static final String TAG = "HighPixelEffects";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String FILTER_FEATURE = "com.oplus.camera.feature.filter";

    private HighPixelFeatureInject() {}

    static void install(ClassLoader classLoader) {
        Class<?> adapter = XposedHelpers.findClass(SDK + "config.FeatureConfigureAdapter", classLoader);
        XposedHelpers.findAndHookMethod(adapter, "getFeatureInfoList", int.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // Mode-scoped: only inject when FeatureManager is generating highPixel.
                    if (!FeatureModeTracker.isMode("highPixel")) return;
                    Object result = param.getResult();
                    if (!(result instanceof Set)) return;
                    @SuppressWarnings("unchecked")
                    Set<Object> keys = (Set<Object>) result;
                    if (keys.contains(FILTER_FEATURE)) return;
                    keys.add(FILTER_FEATURE);
                    Log.i(TAG, "filter feature injected for highPixel mode");
                }
            });
        Log.i(TAG, "highPixel feature inject installed");
    }
}
