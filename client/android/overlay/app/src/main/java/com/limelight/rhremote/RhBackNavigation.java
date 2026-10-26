package com.limelight.rhremote;

import android.app.Activity;
import android.os.Build;
import java.util.function.BooleanSupplier;

/** Routes modern system Back through the same page actions as the visible controls. */
final class RhBackNavigation implements AutoCloseable {
    private Runnable unregister = () -> {};

    RhBackNavigation(Activity activity, BooleanSupplier handlePageBack) {
        if (Build.VERSION.SDK_INT >= 33) {
            unregister = Api33.register(activity, handlePageBack);
        }
    }

    @Override public void close() {
        unregister.run();
        unregister = () -> {};
    }

    @android.annotation.TargetApi(33)
    private static final class Api33 {
        static Runnable register(Activity activity, BooleanSupplier handlePageBack) {
            android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback = () -> {
                if (handlePageBack.getAsBoolean()) return;
                if (activity.isTaskRoot()) activity.moveTaskToBack(false);
                else activity.finishAfterTransition();
            };
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }
}
