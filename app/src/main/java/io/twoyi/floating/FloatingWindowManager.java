/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.floating;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import io.twoyi.ui.GuestSurfaceView;

/**
 * Holds the guest surface during the handoff between {@link io.twoyi.Render2Activity} and
 * {@link FloatingGuestService}, so neither side needs a reference to the other's Context
 * (avoids leaking an Activity into the Service and vice versa).
 */
public final class FloatingWindowManager {

    private static volatile GuestSurfaceView sPendingHandoff;

    private FloatingWindowManager() {
    }

    /** Called by Render2Activity.goFloating() before starting the service. */
    public static void offer(GuestSurfaceView view) {
        sPendingHandoff = view;
    }

    /** Called by the service when it takes over; returns null if nothing was offered. */
    public static GuestSurfaceView takePending() {
        GuestSurfaceView view = sPendingHandoff;
        sPendingHandoff = null;
        return view;
    }

    /** True while a handoff is in flight (activity finished, service not up yet). */
    public static boolean hasPendingHandoff() {
        return sPendingHandoff != null;
    }

    /** Drop an offered surface (used when the overlay fails to start after the activity left). */
    public static void clearPending() {
        sPendingHandoff = null;
    }

    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        // Pre-M the permission is granted at install time (kept for safety).
        return true;
    }

    public static Intent overlayPermissionIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }
}
