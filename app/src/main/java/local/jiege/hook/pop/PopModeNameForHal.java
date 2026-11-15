package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * POP HAL crash fix (PME110, .18).
 *
 * Root cause (verified across .12/.13/.15 diagnostics): the SDK writes the
 * mode name into the session/capture Parameter as vendor tag
 * "com.oplus.camera.mode" (UConfigureKeys.KEY_CAMERA_MODE). The MTK HAL reads
 * it as m_cameraModeName and selects a retro-specific TRAW pipeline variant
 * that configures TIMGO_T1 for 3840x1440 with stride 3840 — too small for the
 * 10-bit RAW format (needs 4800) — so the TILE driver rejects it with
 * TIMGO_T1_STRIDE_ERROR and camerahalserver dies. Stock PME110 has no retro
 * mode, so this HAL path was never validated.
 *
 * Fix: rewrite "retro_camera_mode" -> "photo_mode" (the stock common mode
 * name) when the KEY_CAMERA_MODE key is set on any Parameter builder. The HAL
 * then configures the pipeline exactly like a common capture. The app-side
 * retro look is unaffected: the APS reads the mode from
 * ApsRequestTag.mModeName (set in createRequestTag, an independent path), so
 * BasicTone LUT + Meishe filter still apply.
 *
 * Do NOT hook BaseMode.getModeName() — the APS uses it too; changing it would
 * drop the retro look. Only the Parameter layer is rewritten.
 *
 * Signatures verified against the SDK jar dexdump:
 * - Parameter$BaseBuilder.set(RequestKey, Object) -> Parameter$BaseBuilder
 * - UConfigureKeys.KEY_CAMERA_MODE = ConfigureKey("com.oplus.camera.mode")
 */
public final class PopModeNameForHal {
    private static final String TAG = "PopPort";
    private static final String RETRO = "retro_camera_mode";
    private static final String PHOTO = "photo_mode";
    private static final String MODE_TAG = "com.oplus.camera.mode";

    private PopModeNameForHal() {}

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> keyClass = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.metadata.RequestKey", classLoader);
            Class<?> keysClass = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.metadata.UConfigureKeys", classLoader);
            final Object cameraModeKey = XposedHelpers.getStaticObjectField(
                    keysClass, "KEY_CAMERA_MODE");
            XposedHelpers.findAndHookMethod(
                    "com.oplus.ocs.camera.metadata.parameter.Parameter$BaseBuilder",
                    classLoader, "set", keyClass, Object.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!RETRO.equals(param.args[1])) return;
                            // Reference equality: KEY_CAMERA_MODE is a static
                            // final singleton. Fall back to a name check in
                            // case a different classloader loaded the keys.
                            boolean isModeKey = param.args[0] == cameraModeKey
                                    || String.valueOf(param.args[0]).contains(MODE_TAG);
                            if (isModeKey) {
                                param.args[1] = PHOTO;
                                Log.i(TAG, "HAL mode name rewritten retro_camera_mode -> photo_mode");
                            }
                        }
                    });
            Log.i(TAG, "HAL mode-name rewrite installed");
        } catch (Throwable e) {
            Log.e(TAG, "HAL mode-name rewrite unavailable", e);
        }
    }
}
