package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Hasselblad soft-light click-path diagnostics (PME110, temporary).
 *
 * In highPixel mode the soft-light menu tap never reaches FilterPresenter.e5():
 * u()/e5()/A3() are never called after entering highPixel. This watch logs the
 * menu click handler (Lpe/d0;.g) and the presenter dispatch (Lkc/t;.h1) so one
 * soft-light tap settles whether the click reaches the presenter at all, and
 * what mode name the presenter sees.
 */
public final class SoftLightClickWatch {
    private static final String TAG = "HighPixelEffects";

    private SoftLightClickWatch() {}

    public static void install(ClassLoader classLoader) {
        // 1. Lpe/d0;.g(boolean) — FaceBeautyUIManager softLightMenuClick.
        try {
            XposedHelpers.findAndHookMethod("pe.d0", classLoader, "g", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "softLightMenuClick: " + param.args[0]);
                        }
                    });
            Log.i(TAG, "soft-light menu click watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "soft-light menu click watch unavailable", e);
        }
        // 2. Lkc/t;.h1(String, fg$b, Object) — presenter dispatch.
        try {
            Class<?> fgB = XposedHelpers.findClass("com.oplus.camera.fg$b", classLoader);
            XposedHelpers.findAndHookMethod("kc.t", classLoader, "h1",
                    String.class, fgB, Object.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            String mode = "?";
                            try {
                                Object q = XposedHelpers.getObjectField(param.thisObject, "q");
                                if (q instanceof String) mode = (String) q;
                            } catch (Throwable ignored) {
                                // Field may not exist on all presenter subclasses.
                            }
                            Log.i(TAG, "h1() called: mode=" + mode + " key=" + param.args[0]);
                        }
                    });
            Log.i(TAG, "soft-light h1() watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "soft-light h1() watch unavailable", e);
        }
    }
}
