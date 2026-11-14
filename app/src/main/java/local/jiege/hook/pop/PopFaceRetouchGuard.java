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
 * NOTE (2026-11-14): the .10 filter append hung the native pipeline because only the
 * algo-list entry was added while KEY_FILTER_ENABLE stayed false and the filter type
 * stayed "default". The .12 fix sets ALL filter parameters together: the algo-list
 * entry + KEY_FILTER_ENABLE=true + KEY_FILTER_OPEN=1 + KEY_FILTER_TYPE=<valid LUT>
 * + KEY_FILTER_INTENSITY=100. The native pipeline supports filter (aps_algo_filter +
 * filterProcessMeishe exist in MTK libs, LUT path redirected to pop_port).
 */
final class PopFaceRetouchGuard {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String FACE_RETOUCH = "aps_algo_facebase_retouch";
    private static final String BASIC_TONE = "aps_algo_basic_tone";
    private static final String FILTER = "aps_algo_filter";
    /** Default POP filter LUT (payload has it, MTK native knows it). */
    private static final String DEFAULT_FILTER_LUT = "ccd_normal_cold.bin";

    /** Map a UI retro filter name to its LUT file. Returns null if unknown. */
    private static String mapRetroFilterToLut(String retroType) {
        if (retroType == null) return null;
        String t = retroType.toLowerCase();
        if (t.contains(".bin") || t.contains(".mslut") || t.contains(".cube")) return retroType;
        switch (t) {
            case "ccd": return "ccd_normal_cold.bin";
            case "polaroid": return "Polaroid_normal.bin";
            case "glow": return "glow_sdr_gen_0_1.bin";
            case "emerald": return "Emerald.cube.rgb.bin";
            default: return null;
        }
    }

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
                if (!kept.contains(BASIC_TONE)) {
                    kept.add(BASIC_TONE);
                    changed = true;
                }
                // POP filter: set ALL filter parameters together, or the native pipeline
                // hangs (the .10 bug: only the algo-list entry was added). Read the
                // retro filter type from the tag; fall back to the default LUT.
                String filterType = null;
                try {
                    Object tagFilterType = XposedHelpers.getObjectField(tag, "mFilterType");
                    if (tagFilterType instanceof String) filterType = (String) tagFilterType;
                } catch (Throwable ignored) {
                    // Tag has no mFilterType field; use the default.
                }
                String lut = mapRetroFilterToLut(filterType);
                if (lut == null) lut = DEFAULT_FILTER_LUT;
                Log.i(TAG, "POP filter type: tag=" + filterType + " lut=" + lut);
                try {
                    Class<?> keys = XposedHelpers.findClass(
                            SDK + "common.util.ParameterKeys", classLoader);
                    Object enableKey = XposedHelpers.getStaticObjectField(keys, "KEY_FILTER_ENABLE");
                    Object openKey = XposedHelpers.getStaticObjectField(keys, "KEY_FILTER_OPEN");
                    Object typeKey = XposedHelpers.getStaticObjectField(keys, "KEY_FILTER_TYPE");
                    XposedHelpers.callMethod(meta, "setParameter", enableKey, Boolean.TRUE);
                    XposedHelpers.callMethod(meta, "setParameter", openKey, Integer.valueOf(1));
                    XposedHelpers.callMethod(meta, "setParameter", typeKey, lut);
                    try {
                        Object intensityKey = XposedHelpers.getStaticObjectField(
                                keys, "KEY_FILTER_INTENSITY");
                        XposedHelpers.callMethod(meta, "setParameter", intensityKey,
                                Integer.valueOf(100));
                    } catch (Throwable ignored) {
                        // KEY_FILTER_INTENSITY may not exist; filter still works.
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "POP filter parameter setup failed", e);
                }
                if (!kept.contains(FILTER)) {
                    kept.add(FILTER);
                    changed = true;
                }
                if (!changed) return;
                XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) kept.toArray(new String[0]));
                Log.i(TAG, "rear POP algorithms restored " + kept);
            }
        });
    }
}
