/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Renderer {

    static {
        System.loadLibrary("twoyi");
    }

    public static native void init(Surface surface, String loader, float xdpi, float ydpi, int fps);

    /**
     * Re-attach an existing renderer to a new surface without booting anything. Used when the
     * guest view is handed off between {@link io.twoyi.Render2Activity} and the floating overlay.
     * Safe no-op if the renderer/guest was never started; otherwise identical to the already-started
     * branch of {@link #init} (setNativeWindow + resetSubWindow).
     */
    public static native void attach(Surface surface);

    public static native void resetWindow(Surface surface, int top, int left, int width, int height);

    public static native void removeWindow(Surface surface);

    public static native void handleTouch(MotionEvent event);

    public static native void sendKeycode(int keycode);
}
