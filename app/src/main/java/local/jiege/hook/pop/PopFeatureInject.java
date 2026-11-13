package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import local.jiege.hook.common.Log;

/**
 * POP port (PME110): the retro feature presenter is never created because the adapted
 * feature protobuf is missing the {@code retro_camera_mode} mode-table entry.
 *
 * The chain: FeatureManager ({@code gb/e1.f}) normalizes the mode name to
 * "retro_camera_mode", looks up the mode config, and calls
 * {@code FeatureConfigureAdapter.getFeatureInfoList(entryType)} to get the feature-key
 * set. Without a mode-table entry the SDK falls back to the common config, whose
 * feature list lacks "com.oplus.camera.feature.retro_camera", so the FeatureFactory
 * never creates {@code Laj/c} (the retro presenter), {@code L1} never runs, {@code z9()}
 * never runs, the Polaroid frame is never built, and every post-capture UI-restore
 * call ({@code Q3(true)}) is skipped by PopUiGuard — the UI stays in "processing"
 * forever. This is the POP post-capture freeze.
 *
 * This hook injects the retro feature key into the returned set, so the factory
 * creates the presenter and the z9()/showUI path works unmodified. The factory only
 * creates {@code Laj/c} when the key is requested, and the FeatureManager only
 * iterates the list for the active mode, so the injection is safe.
 *
 * (The proper follow-up is to copy the mode-table entries into the adapted protobuf;
 * this hook is the robust primary fix.)
 */
final class PopFeatureInject {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String RETRO_FEATURE = "com.oplus.camera.feature.retro_camera";

    private PopFeatureInject() {}

    static void install(ClassLoader classLoader) {
        Class<?> adapter = XposedHelpers.findClass(SDK + "config.FeatureConfigureAdapter", classLoader);
        XposedHelpers.findAndHookMethod(adapter, "getFeatureInfoList", int.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof Set)) return;
                    @SuppressWarnings("unchecked")
                    Set<Object> keys = (Set<Object>) result;
                    if (keys.contains(RETRO_FEATURE)) return;
                    keys.add(RETRO_FEATURE);
                    Log.i(TAG, "retro feature injected into feature list");
                }
            });
        Log.i(TAG, "retro feature inject installed");
    }
}
