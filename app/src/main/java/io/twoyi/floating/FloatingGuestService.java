/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.floating;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import io.twoyi.R;
import io.twoyi.Render2Activity;
import io.twoyi.TwoyiStatusManager;
import io.twoyi.ui.GuestSurfaceView;
import io.twoyi.utils.RomManager;

/**
 * Foreground service that hosts the guest display in a {@code TYPE_APPLICATION_OVERLAY} window.
 *
 * The service does NOT create a second renderer or a second guest: it takes ownership of the
 * single shared {@link GuestSurfaceView} (handed over by {@link Render2Activity} through
 * {@link FloatingWindowManager}) and adds it to its own overlay window. Because reparenting a
 * SurfaceView destroys and recreates its surface, the handoff token in
 * {@link GuestSurfaceView#requestHandoff()} suppresses {@code Renderer.removeWindow} during the
 * transition; the new window's {@code surfaceCreated} re-attaches the native window via
 * {@code Renderer.attach}.
 *
 * The guest keeps running when the activity is closed because the guest process is a child of
 * the app process, which stays alive while this foreground service runs.
 */
public class FloatingGuestService extends Service {

    private static final String TAG = "FloatingGuestService";
    private static final String CHANNEL_ID = "twoyi_floating";
    private static final int NOTIFICATION_ID = 0x5A01;

    public static final String ACTION_START_FLOATING = "io.twoyi.action.FLOATING";
    /** Start the floating window and boot the guest directly into it (no fullscreen first). */
    public static final String ACTION_BOOT_FLOATING = "io.twoyi.action.BOOT_FLOATING";
    /** Close the floating window and stop the service (notification action / close button). */
    public static final String ACTION_STOP_FLOATING = "io.twoyi.action.STOP_FLOATING";

    /** Non-null while the service owns the guest surface; used as an isRunning() flag. */
    @Nullable
    private static volatile GuestSurfaceView sOwnedView;
    @Nullable
    private static volatile FloatingGuestService sInstance;

    private WindowManager mWindowManager;
    private View mRootView;
    private FrameLayout mChrome;
    private GuestSurfaceView mGuestView;
    private WindowManager.LayoutParams mParams;
    private boolean mMinimized = false;
    private boolean mBootRequested = false;

    // ---------------------------------------------------------------- statics

    public static boolean isRunning() {
        return sOwnedView != null;
    }

    /**
     * Start the overlay taking over the currently displayed guest surface.
     *
     * @return false if the overlay permission is missing (the caller keeps rendering fullscreen)
     *         or the service could not be started.
     */
    public static boolean startFloating(Context context) {
        if (!FloatingWindowManager.hasOverlayPermission(context)) {
            Toast.makeText(context, R.string.floating_permission_required, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!isRunning()) {
            Intent intent = new Intent(context, FloatingGuestService.class)
                    .setAction(ACTION_START_FLOATING);
            startForegroundServiceCompat(context, intent);
        }
        return true;
    }

    /**
     * Boot the guest straight into the floating window (used from the launcher entry).
     *
     * @return false if the overlay permission is missing; the caller should then send the user
     *         to {@link FloatingWindowManager#overlayPermissionIntent}.
     */
    public static boolean startBootFloating(Context context) {
        if (!FloatingWindowManager.hasOverlayPermission(context)) {
            return false;
        }
        Intent intent = new Intent(context, FloatingGuestService.class)
                .setAction(ACTION_BOOT_FLOATING);
        startForegroundServiceCompat(context, intent);
        return true;
    }

    /** Bring the overlay back to the front / un-minimize it. */
    public static void restore(Context context) {
        FloatingGuestService service = sInstance;
        if (service == null) {
            // Not running anymore: fall back to the normal fullscreen activity.
            context.startActivity(new Intent(context, Render2Activity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        service.runOnUiThread(() -> {
            if (service.mMinimized) {
                service.setMinimized(false);
            }
            if (service.mRootView != null) {
                service.mRootView.setVisibility(View.VISIBLE);
            }
        });
    }

    private static void startForegroundServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BOOT_FLOATING.equals(action)) {
            mBootRequested = true;
        }

        if (isRunning()) {
            restore(this);
            return START_NOT_STICKY;
        }

        startOwnForegroundNotification();

        if (!FloatingWindowManager.hasOverlayPermission(this)) {
            // Revoked between check and start: bail out gracefully, nothing was leaked.
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            createOverlay();
        } catch (Exception e) {
            // WindowAddFailed etc.: never leave a half-state behind.
            android.util.Log.e(TAG, "failed to create overlay", e);
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        sInstance = this;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ---------------------------------------------------------------- overlay

    private void createOverlay() {
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.min(dm.widthPixels, dp(400));
        int height = Math.max(dp(240), (int) (width * 16f / 9f));
        if (height > dm.heightPixels - dp(80)) {
            height = dm.heightPixels - dp(80);
            width = (int) (height * 9f / 16f);
        }

        // FLAG_NOT_FOCUSABLE: the overlay must never steal input focus; guest keys are handled
        // through the surface's own touch stream and the HOME-on-back mapping in GuestSurfaceView.
        final boolean handoff = FloatingWindowManager.hasPendingHandoff();

        mRootView = LayoutInflater.from(this).inflate(R.layout.floating_guest_window, null);
        mChrome = mRootView.findViewById(R.id.floating_chrome);
        TextView title = mRootView.findViewById(R.id.floating_title);
        View minimize = mRootView.findViewById(R.id.floating_minimize);
        View close = mRootView.findViewById(R.id.floating_close);
        View fullscreen = mRootView.findViewById(R.id.floating_fullscreen);
        View resizeHandle = mRootView.findViewById(R.id.floating_resize_handle);
        FrameLayout guestContainer = mRootView.findViewById(R.id.floating_guest_container);

        title.setText(R.string.floating_notification_title);
        title.setOnTouchListener(mDragTouchListener);
        minimize.setOnClickListener(v -> setMinimized(true));
        close.setOnClickListener(v -> stopSelf());
        fullscreen.setOnClickListener(v -> goFullscreen());

        GuestSurfaceView view = FloatingWindowManager.takePending();
        if (view != null) {
            // Handoff from the activity: arm the detach suppression BEFORE we attach the view
            // to our window so no removeWindow/init races can happen.
            view.beginDetachHandoff();
        } else {
            view = new GuestSurfaceView(this);
        }
        mGuestView = view;
        sOwnedView = view;

        // Cold boot into the overlay (launcher "Boot in floating window"). Deliberately minimal:
        // we only support it when the ROM is already extracted and no upgrade is pending --
        // extraction needs the activity's progress UI, so any other state falls back to the
        // normal fullscreen boot path. RomManager.initRootfs mirrors Render2Activity.bootSystem.
        if (!TwoyiStatusManager.getInstance().isStarted() && mBootRequested) {
            boolean forceInstall = io.twoyi.utils.AppKV.getBooleanConfig(
                    getApplicationContext(), io.twoyi.utils.AppKV.FORCE_ROM_BE_RE_INSTALL, false);
            boolean canBootDirectly = RomManager.romExist(this) && !forceInstall
                    && !RomManager.needsUpgrade(this);
            if (canBootDirectly) {
                RomManager.initRootfs(this);
                view.markForBoot();
            } else {
                // Not yet offer()'d, so nothing to clear; just don't own anything.
                mGuestView = null;
                sOwnedView = null;
                stopSelf();
                startActivity(new Intent(this, Render2Activity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }

        guestContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (handoff) {
            // The activity detaches the view on ITS main thread after startForegroundService
            // returned; that detach (surfaceDestroyed) is suppressed by beginDetachHandoff.
            // End the suppression only once our window's surface is actually alive, so a late
            // destroy callback can never tear the renderer down while we are displaying it.
            view.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull android.view.SurfaceHolder holder) {
                    view.getHolder().removeCallback(this);
                    view.endHandoff();
                }

                @Override
                public void surfaceChanged(@NonNull android.view.SurfaceHolder holder, int format, int w, int h) {
                }

                @Override
                public void surfaceDestroyed(@NonNull android.view.SurfaceHolder holder) {
                }
            });
        } else {
            mRootView.post(() -> {
                GuestSurfaceView v = mGuestView;
                if (v != null) {
                    v.endHandoff();
                }
            });
        }

        resizeHandle.setOnTouchListener(mResizeTouchListener);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(80);
        mParams = params;

        mWindowManager.addView(mRootView, params);

        if (mBootRequested && !TwoyiStatusManager.getInstance().isStarted()) {
            Toast.makeText(this, R.string.floating_booting, Toast.LENGTH_LONG).show();
        }
    }

    /** Move the overlay back into a fullscreen activity. */
    private void goFullscreen() {
        // Publish the view first so Render2Activity.onCreate finds it already "offered";
        // stopSelf()'s cleanup then hands it over with the detach suppression armed (no
        // removeWindow while reparenting) and the activity's surfaceCreated re-attaches.
        GuestSurfaceView view = mGuestView;
        if (view != null) {
            Render2Activity.sGuestSurfaceView = view;
            FloatingWindowManager.offer(view);
        }
        mGuestView = null;
        sOwnedView = null;
        stopSelf();
        startActivity(new Intent(this, Render2Activity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void setMinimized(boolean minimized) {
        mMinimized = minimized;
        if (minimized) {
            mChrome.setVisibility(View.GONE);
            mGuestView.setVisibility(View.GONE);
        } else {
            mChrome.setVisibility(View.VISIBLE);
            mGuestView.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- gestures

    private float mDownRawX, mDownRawY, mStartX, mStartY;
    private boolean mMoved;

    private final View.OnTouchListener mDragTouchListener = (v, event) -> {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartX = mParams.x;
                mStartY = mParams.y;
                mMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                mParams.x = (int) (mStartX + event.getRawX() - mDownRawX);
                mParams.y = (int) (mStartY + event.getRawY() - mDownRawY);
                mMoved = true;
                applyParams();
                return true;
            case MotionEvent.ACTION_UP:
                snapToNearestEdge(event);
                return true;
            default:
                return false;
        }
    };

    /** Edge snapping: pull the window flush against the nearest horizontal edge on release. */
    private void snapToNearestEdge(MotionEvent up) {
        if (!mMoved) {
            return; // simple tap, keep position
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int centerX = mParams.x + (mRootView.getWidth() > 0 ? mRootView.getWidth() / 2 : 0);
        if (centerX < dm.widthPixels / 2) {
            mParams.x = -mRootView.getWidth() + dp(32); // peek at left edge
        } else {
            mParams.x = dm.widthPixels - dp(32);         // peek at right edge
        }
        applyParams();
    }

    private float mResizeDownX, mResizeDownY;
    private int mStartW, mStartH;

    private final View.OnTouchListener mResizeTouchListener = (v, event) -> {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mResizeDownX = event.getRawX();
                mResizeDownY = event.getRawY();
                mStartW = mRootView.getWidth();
                mStartH = mRootView.getHeight();
                return true;
            case MotionEvent.ACTION_MOVE:
                int w = Math.max(dp(160), mStartW + (int) (event.getRawX() - mResizeDownX));
                int h = Math.max(dp(120), mStartH + (int) (event.getRawY() - mResizeDownY));
                mParams.width = w;
                mParams.height = h;
                applyParams();
                return true;
            default:
                return false;
        }
    };

    private void applyParams() {
        try {
            if (mWindowManager != null && mRootView != null && mRootView.isAttachedToWindow()) {
                mWindowManager.updateViewLayout(mRootView, mParams);
            }
        } catch (IllegalArgumentException e) {
            // View detached underneath us; ignore, cleanup will follow.
        }
    }

    // ---------------------------------------------------------------- teardown

    private void cleanup() {
        // Removing the root view destroys the guest surface. When we hand the view back to a
        // fullscreen activity (goFullscreen) or drop it entirely, arm the detach suppression so
        // surfaceDestroyed does NOT call Renderer.removeWindow: the renderer must stay alive
        // while no surface is attached; the next window's surfaceCreated re-attaches it via
        // Renderer.attach. (If the whole process dies afterwards -- e.g. RomManager.shutdown --
        // removing sub-windows would be pointless anyway.)
        try {
            if (mGuestView != null) {
                mGuestView.beginDetachHandoff();
            }
            if (mWindowManager != null && mRootView != null) {
                mWindowManager.removeView(mRootView);
            }
        } catch (Exception ignored) {
        }
        mRootView = null;
        mChrome = null;
        if (mGuestView != null) {
            // The view survives (statically referenced) but its surface is gone; drop our
            // ownership so isRunning() goes false. The next fullscreen attach re-points the
            // renderer via Renderer.attach.
            sOwnedView = null;
            Render2Activity.sGuestSurfaceView = mGuestView;
            mGuestView = null;
        }
        sOwnedView = null;
        FloatingWindowManager.clearPending();
        if (sInstance == this) {
            sInstance = null;
        }
    }

    // ---------------------------------------------------------------- notification

    private void startOwnForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.floating_notification_title),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        PendingIntent contentIntent = PendingIntent.getService(this, 1,
                new Intent(this, FloatingGuestService.class).setAction(ACTION_START_FLOATING),
                pendingFlags());

        Intent stopIntent = new Intent(this, FloatingGuestService.class)
                .setAction(ACTION_STOP_FLOATING);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, pendingFlags());

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.floating_notification_title))
                .setContentText(getString(R.string.floating_notification_text))
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.floating_close), stopPendingIntent)
                .build();

        // compileSdk 31 / targetSdk 27: no foreground service type is required or available
        // (FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34). Plain two-arg call.
        startForeground(NOTIFICATION_ID, notification);
    }

    private static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
