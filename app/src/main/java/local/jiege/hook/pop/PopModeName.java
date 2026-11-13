package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * POP port (PME110): the SDK's mode-name mapper swallows retro.
 *
 * {@code ApsDataConvert.getApsModeName("retro_camera_mode")} returns "common" (verified by
 * packed-switch: case 25 returns the "common" constant; the return set has no "retroCamera").
 * Every SDK-side {@code AlgoSwitchConfig.getSupportCaptureAlgo} query therefore looks up the
 * common config (basic_tone=0) instead of the retroCamera config (basic_tone=1) that the
 * module's adapted algo_switch provides. GR ("gr_mode" -> "grmode") and professional map
 * correctly; only retro is swallowed.
 *
 * This hook answers "retroCamera" for the retro input so SDK queries hit the right config.
 * It complements PopFaceRetouchGuard (which restores the algo list itself): the list hook
 * fixes the main path (native-published list), this one fixes the SDK fallback path and
 * any other SDK config lookup.
 */
final class PopModeName {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";

    private PopModeName() {}

    static void install(ClassLoader classLoader) {
        Class<?> converter = XposedHelpers.findClass(SDK + "consumer.ApsDataConvert", classLoader);
        XposedHelpers.findAndHookMethod(converter, "getApsModeName", String.class, int.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String input = (String) param.args[0];
                    if (input == null || !"retro_camera_mode".equals(input)) return;
                    if ("retroCamera".equals(param.getResult())) return;
                    param.setResult("retroCamera");
                    Log.i(TAG, "mode name retro_camera_mode -> retroCamera");
                }
            });
        Log.i(TAG, "mode name hook installed");
    }
}
