package local.jiege.hook.pop;

import local.jiege.hook.common.FeatureModeTracker;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP port: the retro camera (POP) mode. Its settings, LUTs and Polaroid assets are staged by the
 * KSU module; the native half (pop_path_remap.cpp) redirects ODM reads to them.
 */
public final class PopPort {
    private static final String TAG = "PopPort";

    private PopPort() {}

    /** @param mainProcess resources are only needed in the camera's main process */
    public static void installCamera(ClassLoader classLoader, boolean mainProcess) {
        if (mainProcess) {
            try {
                PopResources.install(classLoader);
            } catch (Throwable error) {
                Log.e(TAG, "POP resource hooks unavailable", error);
            }
        }
        try {
            PopShutterGeometry.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP shutter geometry fix unavailable", error);
        }
        try {
            PopFaceRetouchGuard.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP face retouch guard unavailable", error);
        }
        // NOTE (2026-11-15): PopModeName is DISABLED. Its hypothesis (SDK queries
        // retroCamera AlgoSwitchConfig, HAL rejects) was disproven: the HAL never
        // sees the algo list (app-side APS only), and .13 with PopModeName off
        // still crashed. The real HAL crash cause is the mode name in the
        // session Parameter (see PopModeNameForHal below).
        // try {
        //     PopModeName.install(classLoader);
        // } catch (Throwable error) {
        //     Log.e(TAG, "POP mode name hook unavailable", error);
        // }
        Log.guard(TAG, "HAL mode name rewrite", () -> PopModeNameForHal.install(classLoader));
        Log.guard(TAG, "feature mode tracker", () -> FeatureModeTracker.install(classLoader));
        Log.guard(TAG, "POP feature inject", () -> PopFeatureInject.install(classLoader));
        try {
            PopHighPixel.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP high pixel hook unavailable", error);
        }
        try {
            PopSdrStyle.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP SDR style hook unavailable", error);
        }
        try {
            PopUiGuard.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP UI guard unavailable", error);
        }
        try {
            PopUiInit.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP UI setup watch unavailable", error);
        }
    }
}
