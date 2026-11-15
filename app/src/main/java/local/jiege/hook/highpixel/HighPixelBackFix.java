package local.jiege.hook.highpixel;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * highPixel soft-light back-button-stuck FIX (PME110, .18).
 *
 * Root cause (dex-verified, 2026-11-15): pressing back with the soft-light
 * level panel open runs h0 -> h.b(26, c0) -> c0.k(). c0.k() (classes12.txt:
 * 593826) silently returns without publishing the close event when
 * isSoftLight==TRUE && isLighting==FALSE — exactly the state of the open
 * soft-light level panel. No event -> no height animation -> z0 stays
 * TEMP_HIDDEN -> H0() stays TRUE -> Kg consumes every back press while h0
 * no-ops on e1==-1. No crash, no ANR, process alive.
 *
 * Fix: h0-after hook. On a back press where H0() is still TRUE, re-check
 * after 400ms (normal close animation takes ~250ms). If still stuck:
 *  (1) clear the c0.k() gate (Lcg/l.g(false)) and re-run c0.k() so the
 *      native close path runs;
 *  (2) force D0=FALSE (covers a stuck height-anim flag);
 *  (3) guarantee: setViewState(SHOWN) + hide the level panel view, so the
 *      next back press exits the camera normally.
 *
 * All signatures verified against the 7.006.121 dexdump:
 * - CameraMenuContainerBottom.h0:(ZZ)V, .H0:()Z, .setViewState(ViewState) [private]
 * - Lcg/l;.c:()Lc0; [PUBLIC FINAL], .g:(Z)V [PUBLIC FINAL]
 * - c0.k:()Z [PUBLIC FINAL], .setPaletteValuePanelVisible:(Z)V [private]
 * - ViewState enum: com.oplus.camera.ui.newsetting.container
 *   .CameraMenuContainerBottom$ViewState, constant SHOWN.
 */
public final class HighPixelBackFix {
    private static final String TAG = "HighPixelEffects";
    private static final String CONTAINER =
            "com.oplus.camera.ui.newsetting.container.CameraMenuContainerBottom";
    /** Normal close animation takes ~250ms; re-check after 400ms. */
    private static final long STUCK_CHECK_DELAY_MS = 400;

    private HighPixelBackFix() {}

    /** Replicates h0's presenter lookup: FeatureManager.y(activity).r(). */
    private static Object findFilterPresenter(Object container, ClassLoader cl) {
        try {
            Object context = XposedHelpers.callMethod(container, "getContext");
            if (context == null) return null;
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

    private static Object findC0(Object container, ClassLoader cl) {
        try {
            Object presenter = findFilterPresenter(container, cl);
            if (presenter == null) return null;
            Object holder = XposedHelpers.getObjectField(presenter, "P"); // Lcg/l
            if (holder == null) return null;
            return XposedHelpers.callMethod(holder, "c"); // lazy getter, creates c0
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mode() {
        try {
            return FeatureModeTracker.currentMode();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static void forceClose(final Object container, final ClassLoader cl) {
        // Step 1: clear the c0.k() gate and re-trigger the native close.
        try {
            Object presenter = findFilterPresenter(container, cl);
            if (presenter != null) {
                Object holder = XposedHelpers.getObjectField(presenter, "P");
                if (holder != null) {
                    XposedHelpers.callMethod(holder, "g", false); // setIsSoftLight(false)
                    Object c0 = XposedHelpers.callMethod(holder, "c");
                    if (c0 != null) {
                        XposedHelpers.callMethod(c0, "k"); // gate A now open
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the guarantee steps.
        }
        // Step 2: force the height-anim flag clear (covers a stuck D0).
        try {
            XposedHelpers.setBooleanField(container, "D0", false);
        } catch (Throwable ignored) {
        }
        // Step 3 (guarantee): force state SHOWN + hide the level panel view.
        try {
            Class<?> vs = XposedHelpers.findClass(CONTAINER + "$ViewState", cl);
            Object shown = XposedHelpers.getStaticObjectField(vs, "SHOWN");
            XposedHelpers.callMethod(container, "setViewState", shown);
        } catch (Throwable ignored) {
        }
        try {
            Object c0 = findC0(container, cl);
            if (c0 != null) {
                XposedHelpers.callMethod(c0, "setPaletteValuePanelVisible", false);
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "back.fix forced close (mode=" + mode() + ")");
    }

    public static void install(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "h0",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.args[1])) return; // back only
                            boolean stillOpen;
                            try {
                                stillOpen = (boolean) XposedHelpers.callMethod(
                                        param.thisObject, "H0");
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (!stillOpen) return;
                            // Panel still open right after h0. A close
                            // animation may be in progress (~250ms), so
                            // re-check after 400ms before forcing anything.
                            final Object container = param.thisObject;
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    new Runnable() {
                                        @Override public void run() {
                                            boolean stuck;
                                            try {
                                                stuck = (boolean) XposedHelpers.callMethod(
                                                        container, "H0");
                                            } catch (Throwable ignored) {
                                                return;
                                            }
                                            if (!stuck) return; // closed normally
                                            forceClose(container, classLoader);
                                        }
                                    }, STUCK_CHECK_DELAY_MS);
                        }
                    });
            Log.i(TAG, "highPixel back fix installed (h0-after + 400ms stuck check)");
        } catch (Throwable e) {
            Log.e(TAG, "highPixel back fix unavailable", e);
        }
    }
}
