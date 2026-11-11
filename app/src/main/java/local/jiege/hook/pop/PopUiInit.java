package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP port: diagnostic hook on the retro UI container's setup entry.
 *
 * RetroCameraUIContainer (iq.r) creates its main view `f` (bj/w) only in
 * z9(Activity, protocal.ui.a), called on the normal path by the mode container
 * (Laj/c.L1 -> Leo/a.z9). On PME110 the container is queried (Ub(), Q3()) before
 * z9() has run, so f is null at entry -- see PopUiGuard, which covers the crash.
 *
 * This hook does not call z9() early, deliberately: there is no safe way to do so
 * from the query path. The Activity is available (field x, set in the constructor),
 * but the UI context (field h) is null until z9() itself stores it, and z9() builds
 * the view hierarchy (ViewGroup.addView) so it must run on the UI thread, while
 * Ub() is queried from the APS service thread. Pre-creating f alone would be worse:
 * z9() returns immediately when f != null, so the real setup (attaching f to the
 * parent, creating the filter list, Kg/Ag) would be skipped forever.
 *
 * What this hook does is make the setup visible on device: every z9() call is logged
 * (entry args, whether it threw, whether f exists afterwards). That answers the one
 * question static analysis cannot: does z9() run at all on PME110 entry, and if it
 * throws, where. A throw is logged with its stack and left to propagate -- swallowing
 * it would hide a half-built container. Note the two event subscriptions in z9()'s
 * first half (ui.a.xe()/vb()) are skipped on a fresh container (fields Z/z0 are
 * null), so a first-entry z9() cannot fail there; a throw there can only happen on
 * a repeat entry, and the stack will name the exact call.
 *
 * (Correction to the audit's parenthetical: V0() is not z9()'s caller -- V0() is the
 * show entry and calls Kg(); z9() is invoked through the Leo/a interface from
 * Laj/c.L1(Bundle).)
 */
final class PopUiInit {
    private static final String TAG = "RetroPopGuard";

    private PopUiInit() {}

    static void install(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        final Field frameField = symbols.field("RetroCameraUIContainer.f");
        final Method setup = symbols.method("RetroCameraUIContainer.z9");
        XposedBridge.hookMethod(setup, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object frame = frameField.get(param.thisObject);
                Log.i(TAG, "z9 entry: activity=" + (param.args[0] != null)
                    + " ui=" + (param.args[1] != null) + " frame=" + (frame != null ? "present" : "null"));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Throwable error = param.getThrowable();
                if (error != null) {
                    Log.e(TAG, "z9 threw before frame setup completed", error);
                    return;
                }
                Object frame = frameField.get(param.thisObject);
                Log.i(TAG, "z9 done: frame=" + (frame != null ? "present" : "null"));
            }
        });
        Log.i(TAG, "retro UI setup watch installed (z9)");
    }
}
