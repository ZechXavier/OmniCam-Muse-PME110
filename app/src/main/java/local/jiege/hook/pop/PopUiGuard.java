package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP port: guards the retro UI container against its main view being null.
 *
 * RetroCameraUIContainer (iq.r) builds its main view `f` (a bj/w frame view) lazily in
 * z9(Activity, protocal.ui.a), but on PME110 the container is queried before z9() has run:
 * CameraUIManager calls Ub() during mode-switch layout, and Ub() dereferences f without a
 * null check (View.getParent() on null -> NPE, the entry crash). The delayed showUI task
 * (nn/c case 9, scheduled by Q3) dereferences f the same way.
 *
 * R2 (Ub): if f is null, answer -1. That matches Ub()'s existing "no parent container
 * returns -1" convention, so CameraUIManager treats the container as "not laid out" and
 * the entry proceeds; z9() then runs on the normal path and builds f.
 * R3 (Q3): if f is null, do not schedule the showUI task at all -- it would NPE on
 * f.l(int) the moment it runs. Once f exists, Q3 schedules normally.
 *
 * Both hooks are narrow: only this container's Ub/Q3, never the shared nn/c.run switch
 * Runnable (used by SuperText and others) and never the generic k9/i scheduler.
 */
final class PopUiGuard {
    private static final String TAG = "RetroPopGuard";

    private PopUiGuard() {}

    static void install(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        final Field frameField = symbols.field("RetroCameraUIContainer.f");
        final Method ub = symbols.method("RetroCameraUIContainer.Ub");
        final Method scheduleShowUi = symbols.method("RetroCameraUIContainer.Q3");
        XposedBridge.hookMethod(ub, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (frameField.get(param.thisObject) == null) {
                    Log.i(TAG, "Ub() with null frame, returning -1");
                    param.setResult(-1);
                }
            }
        });
        XposedBridge.hookMethod(scheduleShowUi, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (frameField.get(param.thisObject) == null) {
                    Log.i(TAG, "Q3(show=" + param.args[0] + ") with null frame, skipping showUI schedule");
                    param.setResult(null);
                }
            }
        });
        Log.i(TAG, "retro UI guard installed (Ub + Q3)");
    }
}
