//! SpatchApp's natives: the SurfaceView's buffer is locked as RGBA_8888 (byte order matches Slint's
//! PremultipliedRgbaColor), drawn by the software renderer and posted. All on Android's main thread.

use crate::ui;
use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{jfloat, jint};
use ndk_sys::{ANativeWindow, ANativeWindow_Buffer};
use slint::platform::software_renderer::PremultipliedRgbaColor;
use std::cell::Cell;

const WINDOW_FORMAT_RGBA_8888: i32 = 1;

thread_local! {
    static WINDOW: Cell<*mut ANativeWindow> = const { Cell::new(std::ptr::null_mut()) };
}

fn release() {
    WINDOW.with(|w| {
        let old = w.replace(std::ptr::null_mut());
        if !old.is_null() {
            unsafe { ndk_sys::ANativeWindow_release(old) };
        }
    });
}

fn draw() {
    WINDOW.with(|w| {
        let window = w.get();
        if window.is_null() {
            return;
        }
        let mut buffer: ANativeWindow_Buffer = unsafe { std::mem::zeroed() };
        if unsafe { ndk_sys::ANativeWindow_lock(window, &mut buffer, std::ptr::null_mut()) } != 0 {
            return;
        }
        let pixels = unsafe { std::slice::from_raw_parts_mut(buffer.bits as *mut PremultipliedRgbaColor, buffer.stride as usize * buffer.height as usize) };
        ui::render(pixels, buffer.stride as usize);
        unsafe { ndk_sys::ANativeWindow_unlockAndPost(window) };
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeOpen<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, density: jfloat) {
    ui::open(density);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeClose<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    ui::close();
    release();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeSurface<'l>(env: JNIEnv<'l>, _class: JClass<'l>, surface: JObject<'l>, width: jint, height: jint) {
    release();
    if surface.is_null() || width <= 0 || height <= 0 {
        return;
    }
    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_raw() as *mut _, surface.as_raw() as *mut _) };
    if window.is_null() {
        return;
    }
    unsafe { ndk_sys::ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) };
    WINDOW.with(|w| w.set(window));
    ui::resize(width as u32, height as u32);
    ui::frame();
    draw();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeFrame<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    ui::frame();
    draw();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchApp_nativeTouch<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, action: jint, x: jfloat, y: jfloat) {
    ui::touch(action, x, y);
}
