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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.floating.FloatingGuestService;
import io.twoyi.floating.FloatingWindowManager;
import io.twoyi.ui.GuestSurfaceView;
import io.twoyi.ui.HomeActivity;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends Activity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    /** The shared guest surface; may be handed off to the floating overlay (Phase 2). */
    public static GuestSurfaceView sGuestSurfaceView;

    private SurfaceView mSurfaceView;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);

    private ViewGroup mRootView;
    private View mTopBarBtns;
    private View mFloatButton;
    private static final int REQUEST_OVERLAY_PERMISSION = 0x2F01;
    private boolean mPendingFloatSettings = false;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            mPendingFloatSettings = false;
            if (FloatingWindowManager.hasOverlayPermission(this)) {
                goFloating();
            } else {
                // Graceful denial: keep running fullscreen, just tell the user why.
                Toast.makeText(this, R.string.floating_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started);

        if (started && FloatingGuestService.isRunning()) {
            // The floating overlay owns the guest surface right now. Don't create a second
            // fullscreen surface onto the running renderer and don't reboot the guest --
            // just bring the overlay back and finish.
            FloatingGuestService.restore(this);
            finish();
            return;
        }

        if (started) {
            // we have been started, but WTF we are onCreate again? just reboot ourself.
            finish();
            RomManager.reboot(this);
            return;
        }

        // reset state
        TwoyiStatusManager.getInstance().reset();

        NavUtils.hideNavigation(getWindow());

        super.onCreate(savedInstanceState);

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        if (sGuestSurfaceView == null) {
            sGuestSurfaceView = new GuestSurfaceView(this);
            sGuestSurfaceView.markForBoot();
        } else if (!TwoyiStatusManager.getInstance().isStarted()
                && !sGuestSurfaceView.isSurfaceAlive()) {
            // Reused view from a previous session whose renderer is not up: this surface will
            // perform the cold boot when it is created.
            sGuestSurfaceView.markForBoot();
        }
        mSurfaceView = sGuestSurfaceView;

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);
        mTopBarBtns = findViewById(R.id.topBarBtns);
        mTopBarBtns.setVisibility(View.GONE);
        mFloatButton = findViewById(R.id.floatBtn);
        mFloatButton.setOnClickListener(v -> goFloating());
        View btnHomeMenu = findViewById(R.id.btnHomeMenu);
        if (btnHomeMenu != null) {
            btnHomeMenu.setOnClickListener(v -> {
                Intent intent = new Intent(this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            });
        }

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        UITips.checkForAndroid12(this, this::bootSystem);

        mSurfaceView.setOnTouchListener(this);

    }

    /**
     * Hand the guest surface over to the floating overlay service. Only proceeds when the
     * handoff token was granted by the service (so surfaceDestroyed must NOT tear the
     * renderer down); otherwise the activity keeps owning the surface.
     */
    public void goFloating() {
        if (!TwoyiStatusManager.getInstance().isStarted()) {
            Toast.makeText(this, R.string.floating_not_started_yet, Toast.LENGTH_SHORT).show();
            return;
        }

        // Overlay-permission flow with graceful denial: ask once via the system settings
        // screen; if the user still denies (or is on Android 12+ where full-screen app
        // "display over other apps" is restricted), we simply keep rendering fullscreen.
        if (!FloatingWindowManager.hasOverlayPermission(this)) {
            mPendingFloatSettings = true;
            try {
                startActivityForResult(
                        FloatingWindowManager.overlayPermissionIntent(this),
                        REQUEST_OVERLAY_PERMISSION);
            } catch (Throwable t) {
                // No settings screen available on this device/ROM: stay fullscreen.
                mPendingFloatSettings = false;
                Toast.makeText(this, R.string.floating_permission_required, Toast.LENGTH_LONG).show();
            }
            return;
        }

        GuestSurfaceView view = sGuestSurfaceView;
        if (view == null || !view.requestHandoff()) {
            Toast.makeText(this, R.string.floating_handoff_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Offer the view first: the service's onStartCommand runs on this same main thread but
        // only AFTER goFloating returns, so takePending() is guaranteed to see it.
        FloatingWindowManager.offer(view);

        if (!FloatingGuestService.startFloating(this)) {
            // Permission denied or service refused: keep rendering fullscreen here.
            view.endHandoff();
            FloatingWindowManager.clearPending();
            return;
        }

        // Await the detach on the next layout pass so the overlay window is created first;
        // beginDetachHandoff suppresses removeWindow during the reparent.
        mRootView.post(() -> {
            if (sGuestSurfaceView == null) {
                return;
            }
            sGuestSurfaceView.beginDetachHandoff();
            mRootView.removeView(sGuestSurfaceView);
            sGuestSurfaceView = null;
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mTopBarBtns != null) {
            mTopBarBtns.setVisibility(TwoyiStatusManager.getInstance().isStarted() ? View.VISIBLE : View.GONE);
        }
        // The overlay settings screen may have been closed via BACK instead of "allow":
        // re-check the grant when we come back so a denied request settles gracefully.
        if (mPendingFloatSettings && !FloatingWindowManager.hasOverlayPermission(this)) {
            mPendingFloatSettings = false;
            Toast.makeText(this, R.string.floating_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        if (FloatingGuestService.isRunning() || sGuestSurfaceView != null) {
            // In/for floating mode the overlay owns (or will re-attach) the surface; a rotation
            // or recreation must NOT reboot the guest. Just make sure the view is parented back.
            if (mRootView != null && sGuestSurfaceView != null && sGuestSurfaceView.getParent() == null) {
                mRootView.addView(sGuestSurfaceView, 0);
            }
            return;
        }

        // we don't support state restore, just reboot.
        finish();
        RomManager.reboot(this);
    }

    private void bootSystem() {
        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

            new Thread(() -> {
                mIsExtracting.set(true);
                RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                mIsExtracting.set(false);

                RomManager.initRootfs(getApplicationContext());

                runOnUiThread(() -> {
                    mRootView.addView(mSurfaceView, 0);
                    showBootingProcedure();
                });
            }, "extract-rom").start();
        } else {
            mRootView.addView(mSurfaceView, 0);
            showBootingProcedure();
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        // mLoadingText.setText(R.string.booting_tips);
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);
        new Thread(() -> {

            if (true) {
                boolean success = false;
                try {
                    success = TwoyiStatusManager.getInstance().waitBoot(15, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }

                if (!success) {
                    LogEvents.trackBootFailure(getApplicationContext());

                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.boot_failed, Toast.LENGTH_SHORT).show());

                    // waiting for track
                    SystemClock.sleep(3000);

                    finish();
                    System.exit(0);
                    return;
                }
            }

            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
                if (mTopBarBtns != null) {
                    mTopBarBtns.setVisibility(View.VISIBLE);
                }
            });
        }, "waiting-boot").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Fullscreen teardown only: if the floating overlay is not running, nobody else owns
        // the guest surface anymore -- drop it so a later boot creates a fresh one. If the
        // overlay IS running it already re-parented (and may be holding) this view, so leave
        // the static reference to it alone.
        if (!FloatingGuestService.isRunning()) {
            sGuestSurfaceView = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        }

        // Update global visibility.
        TwoyiStatusManager.getInstance().updateVisibility(hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Renderer.handleTouch(event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // super.onBackPressed();
        Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
    }

    private float getBestFps() {
        WindowManager windowManager = getWindowManager();
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        float fps = 45;
        for (Display.Mode supportedMode : supportedModes) {
            float refreshRate = supportedMode.getRefreshRate();
            if (refreshRate > fps) {
                // fps = refreshRate;
            }
        }

        Log.w(TAG, "current fps: " + fps);
        return fps;
    }
}
