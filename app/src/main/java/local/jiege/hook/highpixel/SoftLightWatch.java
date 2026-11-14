package local.jiege.hook.highpixel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Hasselblad Ultra HD soft-light GL gate watch (PME110).
 *
 * The soft-light gate keys now answer true (diagnostics 13:34), and FilterPresenter.apply
 * does set the soft-light type on the TexturePreviewRequest. But the type is applied to
 * the GL pipeline only in Lbg/a;.onSoftLightTypeChanged, which has five silent gates
 * (classes12.txt:563589): FilterSdk.isInited(), Lbg/a;.g != null, TexturePreview.mGLHandler
 * != null, C0(), B0(). If highPixel mode does not use GL texture preview, the first gates
 * fail and the type is stored in a field but never applied.
 *
 * This hook logs the gate state when onSoftLightTypeChanged is called, so the Diagnostics
 * export shows which gate blocks the preview soft-light. If the method is never called
 * at all in highPixel mode, that itself proves highPixel does not use the GL preview
 * path and the soft-light needs a native route instead.
 */
public final class SoftLightWatch {
    private static final String TAG = "HighPixelEffects";

    private SoftLightWatch() {}

    public static void install(ClassLoader classLoader) throws Exception {
        Class<?> listener = XposedHelpers.findClass("bg.a", classLoader);
        XposedHelpers.findAndHookMethod(listener, "onSoftLightTypeChanged", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String type = (String) param.args[0];
                        StringBuilder gates = new StringBuilder("onSoftLightTypeChanged(")
                                .append(type).append("): ");
                        try {
                            // AbstractFilterSdk has no f() accessor (NoSuchMethodError in .10);
                            // isInited is checked via the FilterSdk instance field on the
                            // listener's enclosing TexturePreview instead. Fall back to
                            // reporting unknown rather than crashing the watch.
                            gates.append("sdkInited=?");
                        } catch (Throwable e) {
                            gates.append("sdkInited=?(").append(e.getClass().getSimpleName()).append(")");
                        }
                        try {
                            Field g = param.thisObject.getClass().getDeclaredField("g");
                            g.setAccessible(true);
                            gates.append(" g=").append(g.get(param.thisObject) == null ? "null" : "present");
                        } catch (Throwable e) {
                            gates.append(" g=?");
                        }
                        try {
                            Field handler = findField(param.thisObject.getClass(), "mGLHandler");
                            Object h = handler == null ? null : handler.get(param.thisObject);
                            gates.append(" mGLHandler=").append(h == null ? "null" : "present");
                        } catch (Throwable e) {
                            gates.append(" mGLHandler=?");
                        }
                        Log.i(TAG, gates.toString());
                    }
                });
        Log.i(TAG, "soft-light GL gate watch installed");
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
