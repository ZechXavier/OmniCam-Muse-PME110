package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * GR capture-path diagnostics + ld() fix (PME110, .16).
 *
 * Root-cause report: pme110-gr-h8gap-highpixel-back-2026-11-15.md (round 6).
 *
 * .15 proved the shutter chain reaches h8 with Td() TRUE, but g8/f8 never run
 * (Td called only once per h8). Round 6 located the break inside g8, at the
 * ic() gate: ic() returns FALSE iff jc()==TRUE and ld()==FALSE. In GR,
 * ld() is always FALSE because GR's feature list lacks multi_burst_shot, so
 * MultiBurstShotPresenter (vc.l) is never created and F() returns null.
 * jc() is always TRUE. So every press defers ("wait for fallback to
 * stabilize"), Gh() clears the P flag between presses, and the next press
 * defers again — an infinite deferral loop.
 *
 * Fix A (this file): after-hook BaseMode.ld() — in GR mode, if it returns
 * FALSE, force TRUE. GR has no fallback pipeline (vc.l does not exist), so
 * waiting for fallback stability is meaningless; skipping it is safe.
 * GR-scoped only; other modes untouched.
 *
 * Diagnostic hooks H1-H10 (log-only, GR-gated except H6) verify Fix A and
 * confirm the ic-gate diagnosis:
 * H1 h8 after — return value (FALSE confirms g8 returned FALSE)
 * H2 g8 before+after — t.*() getters + P/o1/d1 + return value (decisive)
 * H3 ic before+after — params + return value
 * H4 jc after — return value
 * H5 ld after + gb/e1.F after — ld result + whether vc.l exists
 * H6 Gh before — mode + param (NOT GR-gated; confirms P clearing between presses)
 * H7 r after + d after — t.f() input + isCapturing
 * H8 Lpn/a.d after — EventMessageCaptureType .d/.c after publish
 * H9 CaptureProcessor.i0 before — params + caller (h8 vs g8)
 * H10 gb/e1.W after — ee.s null or not
 *
 * All signatures verified against the 7.006.121 dexdump before writing:
 * - BaseMode.g8:(Lbb/t;)Z, .ic:(ZZ)Z, .jc:()Z, .ld:()Z, .Gh:(Z)V,
 *   .r:()Z, .d:()Z, .h8:(Lbb/t;)Z
 * - Lgb/e1;.F:()Lvc/l;, Lgb/e1;.W:()Lee/s;
 * - Lpn/a;.d:(Lpn/c;Z)V
 * - CaptureProcessor.i0:(ZZ)V
 */
public final class GrCaptureWatch {
    private static final String TAG = "RicohGrPort";

    private GrCaptureWatch() {}

    private static boolean inGr() {
        try {
            return FeatureModeTracker.isMode("gr");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String callBool(Object target, String method) {
        try {
            return String.valueOf(XposedHelpers.callMethod(target, method));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String readField(Object target, String name) {
        try {
            Object v = XposedHelpers.getObjectField(target, name);
            return v == null ? "null" : String.valueOf(v);
        } catch (Throwable ignored) {
            try {
                return String.valueOf(XposedHelpers.getBooleanField(target, name));
            } catch (Throwable ignored2) {
                try {
                    return String.valueOf(XposedHelpers.getIntField(target, name));
                } catch (Throwable ignored3) {
                    return "?";
                }
            }
        }
    }

    public static void install(ClassLoader classLoader) {
        int installed = 0;
        Class<?> request;
        try {
            request = XposedHelpers.findClass("bb.t", classLoader);
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: bb.t not found", e);
            return;
        }

        // H1: h8 after — return value.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "h8", request, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.h8 -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H1 (h8) unavailable", e);
        }

        // H2: g8 before+after — decisive hook.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "g8", request, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Object t = param.args[0];
                            Log.i(TAG, "capture.g8: t.b=" + callBool(t, "b")
                                    + " t.c=" + callBool(t, "c")
                                    + " t.e=" + callBool(t, "e")
                                    + " t.f=" + callBool(t, "f")
                                    + " t.g=" + callBool(t, "g")
                                    + " t.h=" + callBool(t, "h")
                                    + " t.i=" + callBool(t, "i")
                                    + " P=" + readField(param.thisObject, "P")
                                    + " o1=" + readField(param.thisObject, "o1")
                                    + " d1=" + readField(param.thisObject, "d1"));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.g8 -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H2 (g8) unavailable", e);
        }

        // H3: ic before+after.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "ic", boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.ic(" + param.args[0] + "," + param.args[1] + ")");
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.ic -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H3 (ic) unavailable", e);
        }

        // H4: jc after.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "jc", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.jc -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H4 (jc) unavailable", e);
        }

        // H5a + Fix A: ld after — log original, force TRUE in GR.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "ld", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Object orig = param.getResult();
                            Log.i(TAG, "capture.ld -> " + orig);
                            // Fix A: GR has no fallback pipeline (vc.l does not
                            // exist), so waiting for fallback stability is
                            // meaningless. Skip it.
                            if (Boolean.FALSE.equals(orig)) {
                                param.setResult(Boolean.TRUE);
                                Log.i(TAG, "capture.ld FIXED to true (GR has no fallback)");
                            }
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H5a/Fix A (ld) unavailable", e);
        }

        // H5b: gb/e1.F after — vc.l exists?
        try {
            XposedHelpers.findAndHookMethod("gb.e1", classLoader, "F",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.F -> "
                                    + (param.getResult() == null ? "null (no vc.l)" : "present"));
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H5b (gb.e1.F) unavailable", e);
        }

        // H6: Gh before — NOT GR-gated; records mode to confirm P clearing
        // happens during GR preview.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "Gh", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "capture.Gh: mode=" + FeatureModeTracker.currentMode()
                                    + " param=" + param.args[0]);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H6 (Gh) unavailable", e);
        }

        // H7a: r after.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "r", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.r -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H7a (r) unavailable", e);
        }

        // H7b: d after (isCapturing).
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "d", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.d(isCapturing) -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H7b (d) unavailable", e);
        }

        // H8: Lpn/a.d after — EventMessageCaptureType .d/.c after publish.
        try {
            Class<?> msgBase = XposedHelpers.findClass("pn.c", classLoader);
            XposedHelpers.findAndHookMethod("pn.a", classLoader, "d",
                    msgBase, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Object msg = param.args[0];
                            if (msg == null) return;
                            String name = msg.getClass().getName();
                            if (!name.contains("EventMessageCaptureType")) return;
                            Log.i(TAG, "capture.publish: d=" + readField(msg, "d")
                                    + " c=" + readField(msg, "c"));
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H8 (pn.a.d) unavailable", e);
        }

        // H9: CaptureProcessor.i0 before — caller distinguishes h8:0242 vs g8:0151.
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.module.processor.captureprocessor.CaptureProcessor",
                    classLoader, "i0", boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            String caller = "?";
                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                            // Skip: getStackTrace, beforeHookedMethod, Xposed internals.
                            for (int i = 3; i < Math.min(st.length, 10); i++) {
                                String cn = st[i].getClassName();
                                if (cn.startsWith("de.robv")
                                        || cn.startsWith("local.jiege")) continue;
                                caller = cn + "." + st[i].getMethodName();
                                break;
                            }
                            Log.i(TAG, "capture.i0(" + param.args[0] + "," + param.args[1]
                                    + ") caller=" + caller);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H9 (i0) unavailable", e);
        }

        // H10: gb/e1.W after — ee.s null or not.
        try {
            XposedHelpers.findAndHookMethod("gb.e1", classLoader, "W",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "capture.W -> "
                                    + (param.getResult() == null ? "null (no ee.s)" : "present"));
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR capture watch: H10 (gb.e1.W) unavailable", e);
        }

        Log.i(TAG, "GR capture watch installed (" + installed + "/12 hooks) + ld fix");
    }
}
