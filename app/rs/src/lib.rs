// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::objects::JValue;
use jni::sys::{jclass, jfloat, jint, jobject, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, Level, debug};
use ndk_sys;
use std::ffi::c_void;

use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

// Phase 3: guest display size set by renderer_init; used to scale touch coordinates when the
// custom-resolution switch is on and the host surface differs from the guest framebuffer.
static GUEST_WIDTH: AtomicI32 = AtomicI32::new(0);
static GUEST_HEIGHT: AtomicI32 = AtomicI32::new(0);
// Size of the currently attached host surface/view, updated on init/attach/resetWindow.
pub static VIEW_WIDTH: AtomicI32 = AtomicI32::new(0);
pub static VIEW_HEIGHT: AtomicI32 = AtomicI32::new(0);
use std::thread;

use android_logger::Config;

use std::fs::File;
use std::process::{Command, Stdio};

mod input;
mod renderer_bindings;

/// ## Examples
/// ```
/// let method:NativeMethod = jni_method!(native_method, "(Ljava/lang/String;)V");
/// ```
macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

static RENDERER_STARTED: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub fn renderer_init(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    loader: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
    width: jint,
    height: jint,
    dpi: jint,
) {
    debug!("renderer_init");
    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };

    let window = match std::ptr::NonNull::new(window) {
        Some(x) => x,
        None => {
            error!("ANativeWindow_fromSurface was null!");
            return;
        }
    };

    let window = unsafe { ndk::native_window::NativeWindow::from_ptr(window) };

    // Phase 3: custom resolution. width/height/dpi come from the Java settings layer and are
    // 0 when the custom-resolution switch is off, which keeps the old host-window behaviour
    // exactly (guest size = host surface size, DPI = host xdpi/ydpi).
    let host_width = window.width();
    let host_height = window.height();

    let guest_width = if width > 0 { width } else { host_width };
    let guest_height = if height > 0 { height } else { host_height };

    let guest_xdpi = if dpi > 0 { dpi } else { xdpi as i32 };
    let guest_ydpi = if dpi > 0 { dpi } else { ydpi as i32 };

    VIEW_WIDTH.store(host_width, Ordering::Release);
    VIEW_HEIGHT.store(host_height, Ordering::Release);
    GUEST_WIDTH.store(guest_width, Ordering::Release);
    GUEST_HEIGHT.store(guest_height, Ordering::Release);

    info!(
        "renderer_init guest {}x{}@{}, host {}x{}, fps: {}",
        guest_width, guest_height, dpi.max(guest_xdpi), host_width, host_height, fps
    );

    if RENDERER_STARTED.compare_exchange(false, true, 
        Ordering::Acquire, Ordering::Relaxed).is_err() {
        let win = window.ptr().as_ptr() as *mut c_void;
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(
                win, 0, 0, guest_width, guest_height, guest_width, guest_height, 1.0, 0.0,
            );
        }
    } else {
        input::start_input_system(guest_width, guest_height);

        thread::spawn(move || {
            let win = window.ptr().as_ptr() as *mut c_void;
            info!("win: {:#?}", win);
            unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    guest_width,
                    guest_height,
                    guest_xdpi,
                    guest_ydpi,
                    fps as i32,
                );
            }
        });

        let loader_path: String = env.get_string(loader.into()).unwrap().into();
        let working_dir = "/data/data/io.twoyi/rootfs";
        let log_path = "/data/data/io.twoyi/log.txt";
        let outputs = File::create(log_path).unwrap();
        let errors = outputs.try_clone().unwrap();
        let _ = Command::new("./init")
            .current_dir(working_dir)
            .env("TYLOADER", loader_path)
            .stdout(Stdio::from(outputs))
            .stderr(Stdio::from(errors))
            .spawn();
    }
}

#[no_mangle]
pub fn renderer_attach(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
) {
    debug!("renderer_attach");

    // Only meaningful once the renderer/guest is running; before that this is a no-op and
    // the normal renderer_init path will own the first real surface.
    if RENDERER_STARTED.load(Ordering::Acquire) {
        let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };
        if window.is_null() {
            error!("renderer_attach: ANativeWindow_fromSurface was null!");
            return;
        }
        let win = window as *mut c_void;
        // Phase 3: when a custom resolution is active, keep the guest framebuffer size
        // (set by renderer_init) instead of following the new surface's host size.
        let gw = GUEST_WIDTH.load(Ordering::Acquire);
        let gh = GUEST_HEIGHT.load(Ordering::Acquire);
        let width = unsafe { ndk_sys::ANativeWindow_getWidth(win) };
        let height = unsafe { ndk_sys::ANativeWindow_getHeight(win) };
        let (sw, sh) = if gw > 0 && gh > 0 { (gw, gh) } else { (width, height) };
        VIEW_WIDTH.store(width, Ordering::Release);
        VIEW_HEIGHT.store(height, Ordering::Release);
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(win, 0, 0, sw, sh, sw, sh, 1.0, 0.0);
        }
    }
}

#[no_mangle]
pub fn renderer_reset_window(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    _top: jint,
    _left: jint,
    _width: jint,
    _height: jint,
) {
    debug!("reset_window");
    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        // Track the real view size so touch scaling stays correct for this surface.
        let vw = ndk_sys::ANativeWindow_getWidth(window);
        let vh = ndk_sys::ANativeWindow_getHeight(window);
        if vw > 0 {
            VIEW_WIDTH.store(vw, Ordering::Release);
        }
        if vh > 0 {
            VIEW_HEIGHT.store(vh, Ordering::Release);
        }
        // Phase 3: with a custom resolution active the sub-window keeps the guest
        // framebuffer size; otherwise follow the Java-supplied view size (old behavior).
        let gw = GUEST_WIDTH.load(Ordering::Acquire);
        let gh = GUEST_HEIGHT.load(Ordering::Acquire);
        let vw = if vw > 0 { vw } else { _width };
        let vh = if vh > 0 { vh } else { _height };
        let (sw, sh) = if gw > 0 && gh > 0 && (gw != vw || gh != vh) {
            (gw, gh)
        } else {
            (_width, _height)
        };
        renderer_bindings::resetSubWindow(window as *mut c_void, 0, 0, sw, sh, sw, sh, 1.0, 0.0);
    }
}

#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    // The Java side guards this (GuestSurfaceView only calls removeWindow when it is not in a
    // reparent handoff), but be defensive: removing the sub-window while no renderer/guest is
    // running would pass a dangling native window into libOpenglRender.
    if !RENDERER_STARTED.load(Ordering::Acquire) {
        return;
    }

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if window.is_null() {
            return;
        }
        renderer_bindings::removeSubWindow(window as *mut c_void);
    }
}

#[no_mangle]
pub fn handle_touch(env: JNIEnv, _clz: jclass, event: jobject) {
    // TODO: cache the field id.
    let ptr = env.get_field(event, "mNativePtr", "J").unwrap();

    if let JValue::Long(p) = ptr {
        let ev = unsafe {
            let nonptr =
            std::ptr::NonNull::new(std::mem::transmute::<i64, *mut ndk_sys::AInputEvent>(p))
                .unwrap();
            ndk::event::MotionEvent::from_ptr(nonptr)
        };
                // Phase 3: when a custom resolution is active the guest framebuffer differs from the
        // attached view/surface size; map pointer coordinates into guest space before sending.
        let gw = GUEST_WIDTH.load(Ordering::Acquire);
        let gh = GUEST_HEIGHT.load(Ordering::Acquire);
        let vw = VIEW_WIDTH.load(Ordering::Acquire);
        let vh = VIEW_HEIGHT.load(Ordering::Acquire);
        if gw > 0 && gh > 0 && vw > 0 && vh > 0 && (gw != vw || gh != vh) {
            let pts = rescale_motion_event(&ev, gw, gh, vw, vh);
            input::handle_touch_scaled(&ev, &pts);
        } else {
            input::handle_touch(ev)
        }
    }
}

/// Builds guest-space pointer samples using the shared input::TouchPt type.
fn rescale_motion_event(
    ev: &ndk::event::MotionEvent,
    gw: i32,
    gh: i32,
    vw: i32,
    vh: i32,
) -> Vec<input::TouchPt> {
    let sx = gw as f32 / vw as f32;
    let sy = gh as f32 / vh as f32;

    let raw = ev.ptr().as_ptr() as *const ndk_sys::AInputEvent;
    let pointer_count = unsafe { ndk_sys::AMotionEvent_getPointerCount(raw) };

    let mut pts = Vec::with_capacity(pointer_count);
    let mut i: usize = 0;
    while i < pointer_count {
        let x = unsafe { ndk_sys::AMotionEvent_getX(raw, i) } * sx;
        let y = unsafe { ndk_sys::AMotionEvent_getY(raw, i) } * sy;
        let id = unsafe { ndk_sys::AMotionEvent_getPointerId(raw, i) };
        pts.push(input::TouchPt { id, x, y });
        i += 1;
    }
    pts
}

pub fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = jvm.get_env().unwrap();
    let jni_version = env.get_version().unwrap();
    let version: jint = jni_version.into();

    debug!("JNI Version : {:#?} ", jni_version);

    let clazz = match env.find_class(class_name) {
        Ok(clazz) => clazz,
        Err(e) => {
            error!("java class not found : {:?}", e);
            return JNI_ERR;
        }
    };
    debug!("clazz: {:#?}", clazz);

    let result = env.register_native_methods(clazz, &methods);

    if result.is_ok() {
        debug!("register_natives : succeed");
        version
    } else {
        error!("register_natives : failed ");
        JNI_ERR
    }
}

#[no_mangle]
#[allow(non_snake_case)]
unsafe fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    debug!("JNI_OnLoad");

    let class_name: &str = "io/twoyi/Renderer";
    let jni_methods = [
        jni_method!(
            init,
            renderer_init,
            "(Landroid/view/Surface;Ljava/lang/String;FFIIII)V"
        ),
        jni_method!(attach, renderer_attach, "(Landroid/view/Surface;)V"),
        jni_method!(
            resetWindow,
            renderer_reset_window,
            "(Landroid/view/Surface;IIII)V"
        ),
        jni_method!(
            removeWindow,
            renderer_remove_window,
            "(Landroid/view/Surface;)V"
        ),
        jni_method!(handleTouch, handle_touch, "(Landroid/view/MotionEvent;)V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
    ];

    register_natives(&jvm, class_name, jni_methods.as_ref())
}
