package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * POP port: rear POP captures crash the camera when a face is present. The face-base retouch node
 * (aps_algo_facebase_retouch -> libOPAlgoCamAiUnifySkin, calculateMeanSkinColor /
 * fp16Tofp32_neon_accelerate_convert) reads past the POP rear capture buffer (8-bit SDR). Stock
 * X9U has no POP mode, so this combination was never tuned; front POP is unaffected.
 * The node is removed from the capture algorithm list of rear POP requests.
 *
 * PME110: the native preview pipeline has no retro capmode, so it publishes a fallback
 * (common) algo list that lacks basic_tone — the retro look never reaches the photo.
 * basic_tone is appended here when missing (PopModeName already fixed the SDK side so
 * the native list usually has it; this is a belt-and-braces fallback).
 *
 * NOTE (2026-11-14): do NOT append aps_algo_filter. The native list has no filter because
 * mbFilterOpen=false for POP (the retro look is a mode attribute, not a user filter), so
 * the SDK writes KEY_FILTER_ENABLE=false. Appending filter to the algo list while
 * FILTER_ENABLE is false makes the native pipeline hang waiting for filter parameters
 * that never come — the capture never completes and the camera process dies ~9s later.
 * BasicTone alone provides the retro look; filter is not needed.
 */
final class PopFaceRetouchGuard {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String FACE_RETOUCH = "aps_algo_facebase_retouch";
    private static final String BASIC_TONE = "aps_algo_basic_tone";

    private PopFaceRetouchGuard() {}

    static void install(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader), "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object tag = param.args[0];
                Object mode = XposedHelpers.getObjectField(tag, "mCaptureMode");
                if (!(mode instanceof String) || !((String) mode).toLowerCase().contains("retro")) return;
                if (XposedHelpers.getIntField(tag, "mRearFrontCameraId") != 0) return;
                Object meta = param.getResult();
                String[] algorithms = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                Log.i(TAG, "rear POP algorithms " + Arrays.toString(algorithms));
                if (algorithms == null) return;
                List<String> kept = new ArrayList<>();
                for (String algorithm : algorithms) if (!FACE_RETOUCH.equals(algorithm)) kept.add(algorithm);
                boolean changed = kept.size() != algorithms.length;
                // Restore basic_tone if the native fallback list dropped it (retro look).
                // Do NOT append filter: with mbFilterOpen=false the SDK writes
                // KEY_FILTER_ENABLE=false, and a filter entry in the algo list with the
                // enable flag off hangs the native pipeline (capture never completes).
                if (!kept.contains(BASIC_TONE)) {
                    kept.add(BASIC_TONE);
                    changed = true;
                }
                if (!changed) return;
                XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) kept.toArray(new String[0]));
                Log.i(TAG, "rear POP algorithms restored " + kept);
            }
        });
    }
}
