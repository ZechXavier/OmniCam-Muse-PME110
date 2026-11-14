package local.jiege.hook.highpixel;

import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Hasselblad soft-light type-list diagnostics (PME110, temporary).
 *
 * The soft-light type arrives at the GL callback as "default". e5 sets "default" when the
 * gate fails (diagnostics prove it returns true) or when the index is out of bounds for
 * the A3() list. This watch logs the u() mode argument and returned list size, the e5
 * soft-light index, and the A3() returned list size, so one soft-light tap settles whether
 * the list is empty, the mode name is wrong, or the index is out of range.
 */
public final class SoftLightListWatch {
    private static final String TAG = "HighPixelEffects";

    private SoftLightListWatch() {}

    public static void install(ClassLoader classLoader) {
        // 1. Lzf/c;.u(int, String) — typesForMode. Log mode arg + returned list size.
        try {
            XposedHelpers.findAndHookMethod("zf.c", classLoader, "u", int.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "u() called: cameraId=" + param.args[0]
                                    + " mode=" + param.args[1]);
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            List<?> result = (List<?>) param.getResult();
                            Log.i(TAG, "u() returned: size="
                                    + (result == null ? "null" : result.size()));
                        }
                    });
            Log.i(TAG, "soft-light u() watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "soft-light u() watch unavailable", e);
        }
        // 2. Lag/v;.e5(int, int, int, int) — FilterPresenter.apply. Log the indices.
        try {
            XposedHelpers.findAndHookMethod("ag.v", classLoader, "e5",
                    int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "e5() called: filterIdx=" + param.args[0]
                                    + " softLightIdx=" + param.args[1]);
                        }
                    });
            Log.i(TAG, "soft-light e5() watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "soft-light e5() watch unavailable", e);
        }
        // 3. Lag/v;.A3() — the soft-light type list. Log returned size.
        try {
            XposedHelpers.findAndHookMethod("ag.v", classLoader, "A3",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            List<?> result = (List<?>) param.getResult();
                            Log.i(TAG, "A3() returned: size="
                                    + (result == null ? "null" : result.size()));
                        }
                    });
            Log.i(TAG, "soft-light A3() watch installed");
        } catch (Throwable e) {
            Log.e(TAG, "soft-light A3() watch unavailable", e);
        }
    }
}
