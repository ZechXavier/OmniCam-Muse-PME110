package local.jiege.hook.gr;

import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * GR port: carries the GR image settings (saturation, tone, contrast, clarity, ...) to preview
 * and capture.
 *
 * Preview: the combined-LUT helper is pointed at the GR LMT tree staged by service.sh.
 * Capture: the GR style panel's encoded parameter struct is attached to the capture stage and
 * the SDK request, where the native half (gr_basictone.cpp) and APS read it.
 *
 * MTK (PME110, Dimensity): the QTI path (a single gr.effect.params byte[] blob) does not work
 * here -- the MTK native layer has no consumer for GR_MODE_PARAM / gr.effect, and a request
 * carrying that unknown key is silently dropped (shutter appears dead). The STOCK
 * GrMode.captureStage attaches that byte[] to the stage request BEFORE this module's
 * after-hook runs, so on MTK the after-hook OVERWRITES the stock GR_MODE_PARAM with an
 * empty Bundle (the SDK then builds an empty GrEffectParam, inert on MTK). On the SDK
 * request the byte[] is skipped and the four gr_mode_* parameters are instead passed as
 * individual UTakePictureKeys Integers (KEY_GR_MODE_CLARITY / VIGNETTE / PARTICLE_SIZE /
 * PARTICLE_AMOUNT), which the MTK doFilterProcess reads directly and which gate the
 * Meishe filter.
 */
final class GrEffectTransport {
    private static final String TAG = "RicohGrPort";
    private static final String GR_LMT_DIR = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";
    // MTK detection, same pattern as SoftLightScreen.kt: the QTI BasicTone backend
    // (libBasicTonePhotoX9.so) is absent on MTK devices.
    private static final boolean IS_MTK = !new java.io.File("/odm/lib64/libBasicTonePhotoX9.so").exists();

    private GrEffectTransport() {}

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "GR preview LUT", () -> hookPreviewLut(symbols, classLoader));
        Log.guard(TAG, "GR capture stage", () -> hookCaptureStage(symbols, classLoader));
        Log.guard(TAG, "GR SDK request", () -> hookSdkRequest(symbols, classLoader));
    }

    private static void hookPreviewLut(final Symbols symbols, ClassLoader classLoader) {
        final ThreadLocal<Boolean> loadingGrLut = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                boolean gr = "gr".equals(param.args[2]);
                // Entering or leaving GR invalidates the cached LUT (index = -1).
                if (gr != "gr".equals(symbols.get("Lut3d.mode", param.thisObject))) {
                    symbols.set("Lut3d.index", param.thisObject, -1);
                }
                loadingGrLut.set(gr);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                loadingGrLut.remove();
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(loadingGrLut.get())) param.args[0] = GR_LMT_DIR;
                }
            });
    }

    private static void hookCaptureStage(final Symbols symbols, final ClassLoader classLoader) {
        final Method putBundle = symbols.method("StageRequest.putBundle");
        XposedBridge.hookMethod(symbols.method("GrMode.captureStage"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                GrState.currentMode = param.thisObject;
                // MTK (PME110): the STOCK GrMode.captureStage already attached the
                // gr.effect.params byte[] to the stage request BEFORE this after-hook
                // runs (Lgm/k;.Eg offset 0x0054: stage.D.j(CameraParameter.GR_MODE_PARAM,
                // bundle)). MTK has no consumer for GR_MODE_PARAM / gr.effect, so a
                // request carrying that key is silently dropped and the shutter appears
                // dead. Overwrite the stock bundle with an EMPTY Bundle (never null --
                // the StageRequest impl may NPE on null): the SDK then gets null from
                // getByteArray("gr.effect.params") and builds an empty GrEffectParam,
                // inert on MTK. The four KEY_GR_MODE_* Integers are attached on the SDK
                // request instead (hookSdkRequest), the only channel MTK reads.
                // This must NOT depend on GrState.isGrMode(): isGrMode() reads the
                // DataManager mode-name key and static analysis cannot confirm the mode
                // switch writes "gr" there. This hook is on GrMode.captureStage, which
                // only runs while GR mode is active, so the overwrite is safe whenever
                // the stage runs.
                if (IS_MTK) {
                    try {
                        Object key = XposedHelpers.getStaticObjectField(
                            XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", classLoader), "GR_MODE_PARAM");
                        Object request = symbols.get("Stage.request", param.args[0]);
                        if (request == null) {
                            Log.i(TAG, "MTK: GR capture stage has no request builder");
                            return;
                        }
                        putBundle.invoke(request, key, new Bundle());
                        Log.i(TAG, "MTK: overwrote stock GR_MODE_PARAM with empty bundle");
                    } catch (Throwable error) {
                        Log.e(TAG, "MTK: GR_MODE_PARAM overwrite failed", error);
                    }
                    return;
                }
                if (!GrState.isGrMode()) return;
                try {
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) {
                        Log.i(TAG, "GR effect encoder unavailable");
                        return;
                    }
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", classLoader), "GR_MODE_PARAM");
                    Object request = symbols.get("Stage.request", param.args[0]);
                    if (request == null) {
                        Log.i(TAG, "GR capture stage has no request builder");
                        return;
                    }
                    putBundle.invoke(request, key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect stage attach failed", error);
                }
            }
        });
    }

    private static void hookSdkRequest(final Symbols symbols, final ClassLoader classLoader) {
        Class<?> grCapMode = XposedHelpers.findClass("com.oplus.ocs.camera.producer.mode.GRCapMode", classLoader);
        XposedBridge.hookAllMethods(grCapMode, "updateCaptureRequestTag", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !GrState.isGrMode()) return;
                // MTK (PME110): do NOT attach the gr.effect.params byte[] blob. The MTK native
                // layer has no consumer for it and a request carrying that unknown key is
                // silently dropped at the SDK/APS boundary (shutter does nothing). Instead
                // attach the four gr_mode_* parameters as individual Integers, which the MTK
                // doFilterProcess reads directly (any of clarity/vignette/particle_amount
                // non-zero runs the Meishe filter for grain/vignette/clarity).
                if (IS_MTK) {
                    try {
                        attachGrModeInts(symbols, classLoader, param.args[0]);
                    } catch (Throwable error) {
                        Log.e(TAG, "GR MTK param attach failed", error);
                    }
                    return;
                }
                try {
                    Object builder = param.args[0];
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UPreviewKeys", classLoader), "KEY_GR_MODE_PARAM");
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(builder, "containCustomKey", key))) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect != null && effect.length != 0) XposedHelpers.callMethod(builder, "set", key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request injection failed", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !GrState.isGrMode()) return;
                // MTK (PME110): the GrEffectParam tag carries the same byte[] blob, which the
                // MTK pipeline does not consume. Skip it; the four KEY_GR_MODE_* Integers
                // attached in beforeHookedMethod are the MTK channel.
                if (IS_MTK) return;
                try {
                    Object tag = param.args[1];
                    Object current = XposedHelpers.getObjectField(tag, "mGrEffectParam");
                    if (current != null && XposedHelpers.callMethod(current, "getGrEffectParams") != null) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) return;
                    Object holder = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.oplus.ocs.camera.consumer.apsAdapter.adapter.GrEffectParam", classLoader));
                    XposedHelpers.callMethod(holder, "setGrEffectParams", new Class<?>[] {byte[].class}, effect);
                    XposedHelpers.setObjectField(tag, "mGrEffectParam", holder);
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request tag attach failed", error);
                }
            }
        });
    }

    /**
     * MTK (PME110): attach the four gr_mode_* parameters as individual Integers on the
     * capture-request builder. The keys are UTakePictureKeys.KEY_GR_MODE_* (PreviewKey),
     * verified present in the PME110 camera APK's SDK (com.oplus.camera.unit.sdk.jar).
     * Values come from the GR style panel's GrEffect (see grEffectInts). Each set() is
     * guarded individually so one missing key does not drop the other three.
     */
    private static void attachGrModeInts(Symbols symbols, ClassLoader classLoader, Object builder) throws Throwable {
        int[] values = grEffectInts(symbols);
        if (values == null) {
            Log.i(TAG, "MTK: no GR effect values, skipping KEY_GR_MODE_* attach");
            return;
        }
        String[] keys = {"KEY_GR_MODE_CLARITY", "KEY_GR_MODE_VIGNETTE", "KEY_GR_MODE_PARTICLE_SIZE", "KEY_GR_MODE_PARTICLE_AMOUNT"};
        Class<?> takeKeys = XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UTakePictureKeys", classLoader);
        for (int i = 0; i < keys.length; i++) {
            try {
                Object key = XposedHelpers.getStaticObjectField(takeKeys, keys[i]);
                XposedHelpers.callMethod(builder, "set", key, values[i]);
            } catch (Throwable error) {
                Log.e(TAG, "MTK: set " + keys[i] + " failed", error);
            }
        }
        Log.i(TAG, "MTK: KEY_GR_MODE_* clarity=" + values[0] + " vignette=" + values[1]
            + " particleSize=" + values[2] + " particleAmount=" + values[3]);
    }

    /**
     * Splits the four Meishe parameters out of the GR style panel's GrEffect, in the order
     * the MTK doFilterProcess expects them: clarity, vignette, particle size, particle amount.
     *
     * How the values are read (verified against the PME110 camera APK, 7.006.121):
     * the GrEffect object holds its parameters in a nested table object (GrEffect.a()),
     * whose superclass declares six int fields (b, c, d, e, f, g) in declaration order.
     * The GR settings are listed in that same order in the module (saturation, tone,
     * contrast, clarity, vignette, grain), so clarity is the 4th int, vignette the 5th,
     * and the grain value the 6th.
     *
     * ASSUMPTION TO VERIFY ON DEVICE (the field names are obfuscated single letters, so
     * the mapping cannot be proven statically): clarity = ints[3], vignette = ints[4],
     * grain = ints[5]. Particle size and particle amount are both driven from the single
     * grain value -- the panel exposes one grain control and the native size/amount split
     * is approximated by using the same value for both. The attached log line prints all
     * six ints, so a single logcat run shows whether the mapping is right; if GR photos
     * come out with the wrong grain/vignette/clarity, adjust the indices here.
     * Returns null when the GrEffect cannot be read at all (caller then skips the attach
     * rather than sending made-up values, except that a null here only skips the Meishe
     * params -- the shutter itself is already fixed by dropping the byte[] blob).
     */
    private static int[] grEffectInts(Symbols symbols) {
        try {
            Object effect = grEffectObject(symbols);
            if (effect == null) return null;
            Object table = null;
            try {
                table = XposedHelpers.callMethod(effect, "a");
            } catch (Throwable ignored) {
                table = XposedHelpers.getObjectField(effect, "a");
            }
            if (table == null) {
                Log.i(TAG, "MTK: GrEffect table object is null");
                return null;
            }
            // The six ints live on the table's superclass; read them sorted by field name.
            // getDeclaredFields() order is not guaranteed, but the fields are named b..g
            // (single letters, confirmed in the 7.006.121 dex), so sorting by name recovers
            // declaration order deterministically.
            java.lang.reflect.Field[] fields = table.getClass().getSuperclass().getDeclaredFields();
            java.util.Arrays.sort(fields, (x, y) -> x.getName().compareTo(y.getName()));
            java.util.ArrayList<Integer> ints = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : fields) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    ints.add(f.getInt(table));
                }
            }
            Log.i(TAG, "MTK: GrEffect table ints=" + ints);
            if (ints.size() < 6) {
                Log.i(TAG, "MTK: GrEffect table has only " + ints.size() + " ints, expected 6");
                return null;
            }
            int clarity = ints.get(3);
            int vignette = ints.get(4);
            int grain = ints.get(5);
            return new int[]{clarity, vignette, grain, grain};
        } catch (Throwable error) {
            Log.e(TAG, "MTK: grEffectInts failed", error);
            return null;
        }
    }

    /** The current GrEffect: the style panel's, or the default effect table's. */
    private static Object grEffectObject(Symbols symbols) throws Throwable {
        Object presenter = GrModeHooks.captureParams(symbols);
        if (presenter == null) return null;
        Object panel = Reflection.findFieldByType(presenter, symbols.cls("GrStylePanel").getName());
        if (panel != null) return symbols.call("GrStylePanel.effect", panel);
        Class<?> tableType = symbols.cls("GrEffectTable");
        Object table = Reflection.findFieldByType(presenter, tableType.getName());
        if (table == null) table = XposedHelpers.newInstance(tableType);
        return XposedHelpers.newInstance(symbols.cls("GrEffect"), table);
    }

    private static Bundle effectBundle(byte[] effect) {
        Bundle bundle = new Bundle();
        bundle.putByteArray("gr.effect.params", effect);
        return bundle;
    }

    /** Encodes the GR style panel state or, if the panel is absent, the default effect table. */
    private static byte[] loadEffectBytes(Symbols symbols) throws Throwable {
        Object presenter = GrModeHooks.captureParams(symbols);
        if (presenter == null) return null;
        Object panel = Reflection.findFieldByType(presenter, symbols.cls("GrStylePanel").getName());
        if (panel != null) return structBytes(symbols, symbols.call("GrStylePanel.effect", panel));
        Class<?> tableType = symbols.cls("GrEffectTable");
        Object table = Reflection.findFieldByType(presenter, tableType.getName());
        if (table == null) table = XposedHelpers.newInstance(tableType);
        return structBytes(symbols, XposedHelpers.newInstance(symbols.cls("GrEffect"), table));
    }

    private static byte[] structBytes(Symbols symbols, Object effect) throws Throwable {
        Object table = symbols.call("GrEffect.table", effect);
        ByteBuffer buffer = ((ByteBuffer) symbols.call("GrEffectTable.buffer", table)).duplicate();
        buffer.clear();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
