//! SpatchApp's natives, all on Android's main thread: the SurfaceView's ANativeWindow goes to the
//! GPU window (window.rs), frames come from Choreographer only while something is due.

use crate::ui;
use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{jfloat, jint, jlong};
use ndk_sys::ANativeWindow;
use std::cell::Cell;
use std::ptr::NonNull;

thread_local! {
    /// The window we hold a reference to; released after the renderer lets go of it.
    static NATIVE: Cell<*mut ANativeWindow> = const { Cell::new(std::ptr::null_mut()) };
}

fn release() {
    ui::with_window(|w| w.detach());
    NATIVE.with(|n| {
        let old = n.replace(std::ptr::null_mut());
        if !old.is_null() {
            unsafe { ndk_sys::ANativeWindow_release(old) };
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeOpen<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, density: jfloat) {
    ui::open(density);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeClose<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    release();
    ui::close();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeSurface<'l>(env: JNIEnv<'l>, _class: JClass<'l>, surface: JObject<'l>, width: jint, height: jint) {
    release();
    if surface.is_null() || width <= 0 || height <= 0 {
        return;
    }
    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_raw() as *mut _, surface.as_raw() as *mut _) };
    let Some(ptr) = NonNull::new(window as *mut std::ffi::c_void) else { return };
    NATIVE.with(|n| n.set(window));
    if let Some(Err(e)) = ui::with_window(|w| w.attach(ptr, width as u32, height as u32)) {
        log::error!("GPU surface: {e}");
        release();
    }
}

/// Draws if due; returns when the next frame is wanted (see ui::frame).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeFrame<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) -> jlong {
    ui::frame()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeTouch<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, action: jint, x: jfloat, y: jfloat) {
    ui::touch(action, x, y);
}
