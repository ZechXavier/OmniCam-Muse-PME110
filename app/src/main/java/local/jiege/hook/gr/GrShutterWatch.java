package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * GR shutter-path diagnostics (PME110, temporary — remove once GR shutter works).
 *
 * Root-cause report: pme110-gr-shutter-rootcause5-2026-11-15.md
 *
 * GrMode is created and the GR presenter (lg.a) is created, but a shutter press
 * in GR produces no Yf dispatch at all. Static analysis traced the full click
 * chain (two complementary paths l1 touch-up and f1 click, converging at
 * BaseMode.h8 -> Td -> g8 -> f8 -> Yf) and narrowed the most suspicious single
 * point to Td() (isReadyToCapture) returning FALSE because m8() finds the APS
 * preview decision result (d1) invalid — but the camera's own Lab/b logs are
 * NOT in the diagnostics package, so .14 cannot distinguish "click never
 * reached i()/e()" from "click reached but blocked by Td()".
 *
 * These 10 hooks (log-only, no behavior change) settle it in one run:
 * 1. lp.h0$g.i — f1 click publish reached?
 * 2. lp.h0$g.e — l1 touch-up publish reached?
 * 3. gm.k.Jj — l1 subscription callback reached? (message.d + Ce result)
 * 4. gm.q.Ce / gm.q.C1 / BaseMode.getSupportFunction("func_support_touch_down_capture")
 *    — which Ce sub-item fails?
 * 5. gm.q.Gf — f1 reached Gf? early-returned?
 * 6. BaseMode.Td — isReadyToCapture result?
 * 7. BaseMode.Jg — APS decision result delivered to GR? (null?)
 * 8. BaseMode.m8 — decision check result?
 * 9. BaseMode.Ub — is this GrMode instance the current mode?
 * 10. BaseMode.h8 — click reached shared downstream?
 *
 * All hooks log only while the current mode is "gr" (via FeatureModeTracker),
 * so preview-rate calls (Jg) don't spam the log in other modes.
 *
 * All method signatures were verified against the 7.006.121 dexdump before
 * writing this file (see report §10):
 * - Llp/h0$g;.i:(ILcom/oplus/camera/control/ShutterButton;)V
 * - Lcom/oplus/camera/control/ShutterButton$i;.e:(Lcom/oplus/camera/control/ShutterButton;ZZI)V
 * - Lgm/k;.Jj:(Lcom/oplus/camera/protocal/ui/control/CameraShutterButtonEventSource$CameraShutterButtonEventMessage;)V
 * - Lgm/q;.Ce:()Z, Lgm/q;.C1:()Z, Lgm/q;.Gf:(I)Z (override of BaseMode.Gf)
 * - Lcom/oplus/camera/module/BaseMode;.Td:(ZZZZ)Z, .Jg:(Lbb/e;)V,
 *   .m8:(Lbb/e;ZZ)Z, .Ub:()Z, .h8:(Lbb/t;)Z, .getSupportFunction:(Ljava/lang/String;)Z
 */
public final class GrShutterWatch {
    private static final String TAG = "RicohGrPort";

    private GrShutterWatch() {}

    private static boolean inGr() {
        try {
            return FeatureModeTracker.isMode("gr");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void install(ClassLoader classLoader) {
        int installed = 0;

        // 1. f1 click publish: lp.h0$g.i(int, ShutterButton)
        try {
            Class<?> shutterButton = XposedHelpers.findClass(
                    "com.oplus.camera.control.ShutterButton", classLoader);
            XposedHelpers.findAndHookMethod("lp.h0$g", classLoader, "i",
                    int.class, shutterButton, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.i: dispatchType=" + param.args[0]
                                    + " button=" + param.args[1]);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 1 (h0$g.i) unavailable", e);
        }

        // 2. l1 touch-up publish: lp.h0$g.e(ShutterButton, boolean, boolean, int)
        try {
            Class<?> shutterButton = XposedHelpers.findClass(
                    "com.oplus.camera.control.ShutterButton", classLoader);
            XposedHelpers.findAndHookMethod("lp.h0$g", classLoader, "e",
                    shutterButton, boolean.class, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.e: cancel=" + param.args[1]
                                    + " flag=" + param.args[2]
                                    + " dispatchType=" + param.args[3]);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 2 (h0$g.e) unavailable", e);
        }

        // 3. l1 subscription callback: gm.k.Jj(CameraShutterButtonEventMessage)
        try {
            Class<?> msg = XposedHelpers.findClass(
                    "com.oplus.camera.protocal.ui.control.CameraShutterButtonEventSource$CameraShutterButtonEventMessage",
                    classLoader);
            XposedHelpers.findAndHookMethod("gm.k", classLoader, "Jj", msg,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            String d = "?";
                            try {
                                d = String.valueOf(XposedHelpers.getBooleanField(param.args[0], "d"));
                            } catch (Throwable ignored) {
                                // Field name may differ.
                            }
                            Log.i(TAG, "shutter.Jj: message.d=" + d);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 3 (gm.k.Jj) unavailable", e);
        }

        // 4a. gm.q.Ce() after — l1 gate result.
        try {
            XposedHelpers.findAndHookMethod("gm.q", classLoader, "Ce",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.Ce -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 4a (gm.q.Ce) unavailable", e);
        }

        // 4b. gm.q.C1() after — RAW-control sub-item of Ce.
        try {
            XposedHelpers.findAndHookMethod("gm.q", classLoader, "C1",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.C1 -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 4b (gm.q.C1) unavailable", e);
        }

        // 4c. BaseMode.getSupportFunction — only the touch-down-capture key.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "getSupportFunction", String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            if (!"func_support_touch_down_capture".equals(param.args[0])) return;
                            Log.i(TAG, "shutter.getSupportFunction(func_support_touch_down_capture) -> "
                                    + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 4c (getSupportFunction) unavailable", e);
        }

        // 5. gm.q.Gf(int) before/after — f1 reached Gf?
        try {
            XposedHelpers.findAndHookMethod("gm.q", classLoader, "Gf", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.Gf: dispatchType=" + param.args[0]);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.Gf -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 5 (gm.q.Gf) unavailable", e);
        }

        // 6. BaseMode.Td(boolean,boolean,boolean,boolean) after — isReadyToCapture.
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "Td", boolean.class, boolean.class,
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.Td(" + param.args[0] + "," + param.args[1]
                                    + "," + param.args[2] + "," + param.args[3]
                                    + ") -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 6 (BaseMode.Td) unavailable", e);
        }

        // 7. BaseMode.Jg(bb.e) before — APS decision delivered?
        try {
            Class<?> decision = XposedHelpers.findClass("bb.e", classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "Jg", decision, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Object d = param.args[0];
                            if (d == null) {
                                Log.i(TAG, "shutter.Jg: decision=null");
                                return;
                            }
                            String allow = "?";
                            try {
                                allow = String.valueOf(XposedHelpers.getBooleanField(d, "a"));
                            } catch (Throwable ignored) {
                                // Field name may differ.
                            }
                            Log.i(TAG, "shutter.Jg: decision=present allowToCapture=" + allow);
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 7 (BaseMode.Jg) unavailable", e);
        }

        // 8. BaseMode.m8(bb.e, boolean, boolean) after — decision check.
        try {
            Class<?> decision = XposedHelpers.findClass("bb.e", classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "m8", decision, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.m8(decision="
                                    + (param.args[0] == null ? "null" : "present")
                                    + ") -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 8 (BaseMode.m8) unavailable", e);
        }

        // 9. BaseMode.Ub() after — is this the current mode?
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "Ub", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.Ub -> " + param.getResult());
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 9 (BaseMode.Ub) unavailable", e);
        }

        // 10. BaseMode.h8(bb.t) before — click reached shared downstream?
        try {
            Class<?> request = XposedHelpers.findClass("bb.t", classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.camera.module.BaseMode",
                    classLoader, "h8", request, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inGr()) return;
                            Log.i(TAG, "shutter.h8: reached");
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "GR shutter watch: hook 10 (BaseMode.h8) unavailable", e);
        }

        Log.i(TAG, "GR shutter watch installed (" + installed + "/12 hooks)");
    }
}
