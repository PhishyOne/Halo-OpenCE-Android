package com.halo.decomp;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Window lifecycle for app-designed panels, including video and history.
 * All registry access is on the main thread; no Activity is retained. */
class HaloWindow extends Dialog {
    private static final ArrayList<WeakReference<HaloWindow>> OPEN = new ArrayList<>();
    private final Context owner;

    HaloWindow(Context context) {
        super(context);
        owner = context;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    static Window activeWindow(Activity activity) {
        for (int i = OPEN.size() - 1; i >= 0; i--) {
            HaloWindow window = OPEN.get(i).get();
            if (window == null || !window.isShowing()) {
                OPEN.remove(i);
                continue;
            }
            if (HaloNotice.activity(window.owner) == activity) return window.getWindow();
        }
        return activity.getWindow();
    }

    @Override protected void onStart() {
        super.onStart();
        OPEN.removeIf(ref -> ref.get() == null || ref.get() == this);
        OPEN.add(new WeakReference<>(this));
    }

    @Override protected void onStop() {
        OPEN.removeIf(ref -> ref.get() == null || ref.get() == this);
        super.onStop();
    }
}
