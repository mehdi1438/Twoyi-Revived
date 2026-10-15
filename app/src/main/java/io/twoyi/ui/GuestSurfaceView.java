/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import io.twoyi.Renderer;

/**
 * The guest display surface, decoupled from any specific window so that it can be
 * reparented between {@link io.twoyi.Render2Activity} and the floating overlay
 * ({@link io.twoyi.floating.FloatingGuestService}) without tearing the renderer down.
 *
 * While {@link #mDetachInProgress} is true (a reparent handoff), the surface callbacks
 * are suppressed: we never call {@code Renderer.removeWindow} mid-handoff because the
 * native side would remove the sub-window while the guest is still running, and we never
 * re-run {@code Renderer.init} for an already-started renderer.
 */
public class GuestSurfaceView extends SurfaceView {

    private volatile boolean mHandoffAllowed = false;
    private volatile boolean mDetachInProgress = false;
    private volatile boolean mSurfaceAlive = false;

    /** Called by Render2Activity when this view is created fresh (before any boot). */
    public void markForBoot() {
        mShouldBootOnSurface = true;
    }

    private volatile boolean mShouldBootOnSurface = false;

    private static float[] hostDpi(android.content.Context context) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return new float[]{dm.xdpi, dm.ydpi};
    }

    private final SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            mSurfaceAlive = true;
            if (mDetachInProgress) {
                return;
            }
            if (mShouldBootOnSurface && !io.twoyi.TwoyiStatusManager.getInstance().isStarted()) {
                // First real surface of a cold boot: this is the ONLY place where the guest
                // process gets spawned (Renderer.init -> Rust renderer_init -> fork ./init).
                mShouldBootOnSurface = false;
                float[] dpi = hostDpi(getContext());
                Renderer.init(holder.getSurface(),
                        io.twoyi.utils.RomManager.getLoaderPath(getContext()),
                        dpi[0], dpi[1], 45);
                return;
            }
            // Already-booted handoff (activity <-> overlay): just re-point the renderer at
            // the new surface; never boots anything.
            Renderer.attach(holder.getSurface());
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            if (mDetachInProgress || !holder.getSurface().isValid()) {
                return;
            }
            Renderer.resetWindow(holder.getSurface(), 0, 0, width, height);
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            mSurfaceAlive = false;
            if (mDetachInProgress) {
                // Reparent handoff: the renderer keeps running, the new host window's
                // surfaceCreated will re-attach the native window. Do NOT removeWindow.
                return;
            }
            Renderer.removeWindow(holder.getSurface());
        }
    };

    /** True while a live surface exists (used by the overlay to avoid booting onto a dead one). */
    public boolean isSurfaceAlive() {
        return mSurfaceAlive;
    }

    /**
     * Boot directly onto an already-valid surface (used by the floating overlay when the guest
     * was started from the service without ever having an activity surface). Same parameters
     * as the cold-boot branch of surfaceCreated.
     */
    public void bootOnCurrentSurface(android.view.Surface surface) {
        mShouldBootOnSurface = false;
        float[] dpi = hostDpi(getContext());
        Renderer.init(surface,
                io.twoyi.utils.RomManager.getLoaderPath(getContext()),
                dpi[0], dpi[1], 45);
    }

    public GuestSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(mCallback);
    }

    /**
     * Called by the overlay service when it wants to take over the surface. Only then may
     * the activity detach the view (this arms the suppression of removeWindow during the
     * handoff). Without this token, removing the view from the activity would destroy the
     * surface and tear the renderer down while the guest is still alive.
     */
    public boolean requestHandoff() {
        if (!isAttachedToWindow()) {
            return false;
        }
        mHandoffAllowed = true;
        return true;
    }

    /** Begin the detach half of the handoff (suppresses surfaceDestroyed -> removeWindow). */
    public void beginDetachHandoff() {
        mDetachInProgress = true;
    }

    /** End the handoff once the view has been attached to its new parent window. */
    public void endHandoff() {
        mDetachInProgress = false;
        mHandoffAllowed = false;
    }

    public boolean isHandoffAllowed() {
        return mHandoffAllowed;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Renderer.handleTouch(event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
