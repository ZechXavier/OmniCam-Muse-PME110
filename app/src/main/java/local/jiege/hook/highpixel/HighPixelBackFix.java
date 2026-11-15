package local.jiege.hook.highpixel;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;

/**
 * highPixel soft-light back-button-stuck FIX (PME110, .18/.19).
 *
 * Root cause (dex-verified, 2026-11-15): pressing back with the soft-light
 * level panel open runs h0 -> h.b(26, c0) -> c0.k(). c0.k() (classes12.txt:
 * 593826) silently returns without publishing the close event when
 * isSoftLight==TRUE && isLighting==FALSE — exactly the state of the open
 * soft-light level panel. No event -> no height animation -> z0 stays
 * TEMP_HIDDEN -> H0() stays TRUE -> Kg consumes every back press while h0
 * no-ops on e1==-1. No crash, no ANR, process alive.
 *
 * .18 lesson: the h0-after fix never triggered in the .18 test (0 "back.fix
 * forced close" logs) even though the user reproduced the stuck state. The
 * back press may never reach h0 (consumed earlier in the chain), so .19 adds
 * a second trigger at ip/u0.Kg (the back router, always on the path) plus
 * minimal h0 logging (back presses only) to show whether presses reach h0.
 *
 * Fix (two triggers, same force-close):
 *  - h0-after: on a back press where H0() is still TRUE, re-check after
 *    400ms (normal close animation takes ~250ms). If still stuck, force-close.
 *  - Kg-after: Kg always returns TRUE (consumes the press). If the container
 *    is known and H0() is still TRUE 400ms after Kg ran, force-close. This
 *    covers presses that never reach h0.
 * Force-close (three layers, each independently guarded):
 *  (1) clear the c0.k() gate (Lcg/l.g(false)) and re-run c0.k() so the
 *      native close path runs;
 *  (2) force D0=FALSE (covers a stuck height-anim flag);
 *  (3) guarantee: setViewState(SHOWN) + hide the level panel view, so the
 *      next back press exits the camera normally.
 *
 * All signatures verified against the 7.006.121 dexdump:
 * - CameraMenuContainerBottom.h0:(ZZ)V, .H0:()Z, .setViewState(ViewState) [private]
 * - ip/u0.Kg:(Lcom/oplus/camera/common/view/v0;)Z
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
    /** Last container seen by the h0 hook (h0's thisObject). Volatile: the
     * Kg hook may run on a different thread. */
    private static volatile Object lastContainer;

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

    private static boolean isOpen(Object container) {
        try {
            return (boolean) XposedHelpers.callMethod(container, "H0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Schedule a stuck-check 400ms after a back press; force-close if H0() is
     * still TRUE. Shared by the h0 and Kg triggers. */
    private static void scheduleStuckCheck(final Object container, final ClassLoader cl,
                                           final String trigger) {
        new Handler(Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override public void run() {
                        if (!isOpen(container)) return; // closed normally
                        forceClose(container, cl, trigger);
                    }
                }, STUCK_CHECK_DELAY_MS);
    }

    private static void forceClose(final Object container, final ClassLoader cl,
                                   String trigger) {
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
        Log.i(TAG, "back.fix forced close via " + trigger + " (mode=" + mode() + ")");
    }

    public static void install(final ClassLoader classLoader) {
        int installed = 0;
        // Trigger 1: h0-after (existing). Also records the container for the
        // Kg trigger and logs back-press h0 calls (minimal visibility: the
        // .18 test proved we cannot tell whether presses reach h0 at all).
        try {
            XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "h0",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            lastContainer = param.thisObject;
                            if (!Boolean.TRUE.equals(param.args[1])) return; // back only
                            boolean stillOpen = isOpen(param.thisObject);
                            Log.i(TAG, "back.h0 isBack=true H0=" + stillOpen
                                    + " (mode=" + mode() + ")");
                            if (!stillOpen) return;
                            scheduleStuckCheck(param.thisObject, classLoader, "h0");
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "highPixel back fix: h0 hook unavailable", e);
        }
        // Trigger 2: Kg-after. Kg is the back router (onBackPressed -> N7 ->
        // Kg -> ... -> h0) and always returns TRUE. If the press never reaches
        // h0, this is the only trigger that fires.
        try {
            Class<?> v0Class = XposedHelpers.findClass(
                    "com.oplus.camera.common.view.v0", classLoader);
            XposedHelpers.findAndHookMethod("ip.u0", classLoader, "Kg", v0Class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.getResult())) return;
                            Object container = lastContainer;
                            if (container == null) return;
                            if (!isOpen(container)) return;
                            scheduleStuckCheck(container, classLoader, "Kg");
                        }
                    });
            installed++;
        } catch (Throwable e) {
            Log.e(TAG, "highPixel back fix: Kg hook unavailable", e);
        }
        Log.i(TAG, "highPixel back fix installed (" + installed + "/2 triggers)");
    }
}
