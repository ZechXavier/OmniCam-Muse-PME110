package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Optional;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * highPixel back-button-stuck diagnostics (PME110, temporary).
 *
 * Root-cause report: pme110-gr-h8gap-highpixel-back-2026-11-15.md (round 6,
 * task 2).
 *
 * In .15, the soft-light panel works in highPixel (e5 softLightIdx=3), but
 * pressing back with the panel open gets permanently stuck: no crash, no ANR,
 * process alive, mode switching works. The full back chain is verified:
 * Camera.onBackPressed -> CameraManager.N7 -> ip/u0.Kg (ALWAYS returns TRUE,
 * i.e. always consumes back) -> lp/r[6] -> cq/a.E -> h0(true,true).
 *
 * Most suspicious mechanism: h0, when e1==1 (filter third-level menu open),
 * reads Lag/v;.P (Lcg/l) field .c DIRECTLY (not the lazy getter c()). The c0
 * filter view is only created in the standard filter-panel async callback,
 * which the highPixel soft-light panel never runs — so P.c is null,
 * Optional.empty makes h.b(26, empty) a complete no-op (panel never hides),
 * but e1 is still set to -1, H0() stays TRUE forever, and every later back
 * press is consumed with no effect. HighPixelFeatureInject made the
 * presenter exist (fixing soft light, no more NPE) but did not create c0 —
 * the same gap changed from ".13 NPE crash" to ".15 silent stuck".
 *
 * Hooks H-B1–H-B6 (log-only, NOT mode-filtered — they record the current
 * mode so common-mode behavior can be compared):
 * H-B1 h0 before — D0, e1, r() null?, P null?, P.c null?, mode (decisive)
 * H-B2 h0 after — e1 + H0() (panel still open?)
 * H-B3 N7 before+after — mode + return value
 * H-B4 Kg before+after — mode + return value
 * H-B5 Lcg/l.c after — c0 created? + mode
 * H-B6 lifecycle h.b before — code 26 + Optional empty?
 *
 * All signatures verified against the 7.006.121 dexdump before writing:
 * - CameraMenuContainerBottom.h0:(ZZ)V
 * - CameraManager.N7:(Lcom/oplus/camera/common/view/v0;)Z (dex 0x219a3c)
 * - Lip/u0;.Kg:(Lcom/oplus/camera/common/view/v0;)Z
 * - Lcg/l;.c:()Lcom/oplus/camera/feature/filter/view/c0;
 * - Landroidx/lifecycle/h;.b:(ILjava/util/Optional;)V (static)
 */
public final class HighPixelBackWatch {
    private static final String TAG = "HighPixelEffects";

    private HighPixelBackWatch() {}

    private static String mode() {
        try {
            return FeatureModeTracker.currentMode();
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

    /** Replicates h0's presenter lookup: FeatureManager.y(activity).r(). */
    private static Object findFilterPresenter(Object container, ClassLoader cl) {
        try {
            Object context = XposedHelpers.callMethod(container, "getContext");
            if (context == null) return null;
            // Walk up ContextWrappers to the Activity if needed.
            Object activity = context;
            for (int i = 0; i < 5; i++) {
                if (activity instanceof android.app.Activity) break;
                try {
                    Object base = XposedHelpers.callMethod(activity, "getBaseContext");
                    if (base == null || base == activity) break;
                    activity = base;
                } catch (Throwable ignored) {
                    break;
                }
            }
            if (!(activity instanceof android.app.Activity)) return null;
            Class<?> fm = XposedHelpers.findClass("gb.e1", cl);
            Object mgr = XposedHelpers.callStaticMethod(fm, "y", activity);
            if (mgr == null) return null;
            return XposedHelpers.callMethod(mgr, "r");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void install(ClassLoader classLoader) {
        int installed = 0;

        // H-B1: h0 before — the decisive hook.
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.ui.newsetting.container.CameraMenuContainerBottom",
                    classLoader, "h0", boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            String d0 = readField(param.thisObject, "D0");
                            String e1 = readField(param.thisObject, "e1");
                            Object presenter = findFilterPresenter(param.thisObject, classLoader);
                            String pStr = "?";
                            String pcStr = "?";
                            if (presenter != null) {
                                try {
                                    Object p = XposedHelpers.getObjectField(presenter, "P");
                                    pStr = p == null ? "null" : "present";
                                    if (p != null) {
                                        Object c = XposedHelpers.getObjectField(p, "c");
                                        pcStr = c == null ? "null" : "present";
                                    }
                                } catch (Throwable ignored) {
                                    // Field names may differ.
                                }
                            } else {
                                pStr = "(no presenter)";
                                pcStr = "(no presenter)";
                            }
                            Log.i(TAG, "back.h0(needAnim=" + param.args[0]
                                    + " isBack=" + param.args[1]
                                    + " mode=" + mode()
                                    + " D0=" + d0 + " e1=" + e1
                                    + " presenter=" + (presenter == null ? "null" : "present")
                                    + " P=" + pStr + " P.c=" + pcStr + ")");
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B1 (h0 before) unavailable", e);
        }

        // H-B2: h0 after — is the panel still open?
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.ui.newsetting.container.CameraMenuContainerBottom",
                    classLoader, "h0", boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            String e1 = readField(param.thisObject, "e1");
                            String h0 = "?";
                            try {
                                h0 = String.valueOf(XposedHelpers.callMethod(param.thisObject, "H0"));
                            } catch (Throwable ignored) {
                                // Method may not be accessible.
                            }
                            Log.i(TAG, "back.h0 after: mode=" + mode()
                                    + " e1=" + e1 + " H0(still open)=" + h0);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B2 (h0 after) unavailable", e);
        }

        // H-B3: CameraManager.N7 before+after.
        try {
            Class<?> v0 = XposedHelpers.findClass(
                    "com.oplus.camera.common.view.v0", classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.camera.CameraManager",
                    classLoader, "N7", v0, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "back.N7: mode=" + mode());
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "back.N7 -> " + param.getResult()
                                    + " mode=" + mode());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B3 (N7) unavailable", e);
        }

        // H-B4: ip/u0.Kg before+after.
        try {
            Class<?> v0 = XposedHelpers.findClass(
                    "com.oplus.camera.common.view.v0", classLoader);
            XposedHelpers.findAndHookMethod("ip.u0", classLoader, "Kg", v0,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "back.Kg: mode=" + mode());
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "back.Kg -> " + param.getResult()
                                    + " mode=" + mode());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B4 (Kg) unavailable", e);
        }

        // H-B5: Lcg/l.c after — was c0 ever created?
        try {
            XposedHelpers.findAndHookMethod("cg.l", classLoader, "c",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "back.c0 created: mode=" + mode()
                                    + " result=" + (param.getResult() == null ? "null" : "present"));
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B5 (cg.l.c) unavailable", e);
        }

        // H-B6: lifecycle h.b before — code 26 + empty Optional?
        try {
            XposedHelpers.findAndHookMethod("androidx.lifecycle.h", classLoader, "b",
                    int.class, Optional.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Integer.valueOf(26).equals(param.args[0])) return;
                            Object opt = param.args[1];
                            boolean empty = true;
                            try {
                                empty = !((Optional<?>) opt).isPresent();
                            } catch (Throwable ignored) {
                                // Not an Optional; treat as unknown.
                            }
                            Log.i(TAG, "back.h.b(26): mode=" + mode()
                                    + " optionalEmpty=" + empty);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "back watch: H-B6 (h.b) unavailable", e);
        }

        Log.i(TAG, "highPixel back watch installed (" + installed + "/6 hooks)");
    }
}
