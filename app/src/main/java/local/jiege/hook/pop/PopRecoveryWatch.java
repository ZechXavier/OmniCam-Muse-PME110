package local.jiege.hook.pop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP post-capture recovery watch (PME110).
 *
 * The post-capture UI recovery chain is:
 *   Laj/c.i2(true) -> Liq/r;.Q3(true) -> Lk9/i;.d(0) -> Lnn/c.run case 9 -> f.l()/f.e()
 * The nn/c case 9 task is double-gated (classes8.txt:325880): it returns silently unless
 * both Liq/r;.j is true AND Lia/m0;.C() is true (current mode == "retroCamera"). Q3 itself
 * also returns early when j is false. The j flag is cleared to false DURING the recovery
 * task (nn/c 0083), so a second capture after a first recovery can never schedule a new
 * one — and if the first recovery is interrupted after clearing j, the UI is stuck in
 * "processing" forever with no thread blocked (matches the 2026-11-13 pop_dump).
 *
 * This hook does two things:
 * 1. Logs the gate state at Q3 time (j value, frame presence) so the Diagnostics export
 *    shows whether the recovery was blocked.
 * 2. Proactively forces j = true before Q3 schedules, so a stale false (left over from
 *    a previous recovery) cannot block the next one. Setting j is safe: it only means
 *    "recovery needed", and Q3 re-checks the frame before scheduling.
 */
public final class PopRecoveryWatch {
    private static final String TAG = "RetroPopGuard";

    private PopRecoveryWatch() {}

    public static void install(Symbols symbols) throws Exception {
        Method q3 = symbols.method("RetroCameraUIContainer.Q3");
        Field frameField = symbols.field("RetroCameraUIContainer.f");
        // The j flag is a boolean field named "j" in 7.006.121 (Liq/r). No symbol: there
        // are several boolean fields in the class and only the name distinguishes it.
        Field jField = q3.getDeclaringClass().getDeclaredField("j");
        jField.setAccessible(true);
        XposedBridge.hookMethod(q3, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                boolean show = (Boolean) param.args[0];
                if (!show) return;
                boolean j = jField.getBoolean(param.thisObject);
                Object frame = frameField.get(param.thisObject);
                Log.i(TAG, "Q3(true): j=" + j + " frame=" + (frame == null ? "null" : "present"));
                if (!j) {
                    jField.setBoolean(param.thisObject, true);
                    Log.i(TAG, "Q3(true): forced j=true (was false, recovery would have been skipped)");
                }
            }
        });
        Log.i(TAG, "POP recovery watch installed");
    }
}
