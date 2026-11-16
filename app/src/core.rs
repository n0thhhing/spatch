//! Calls into Spotify's core through Java's spatch.CoreBridge: Esperanto services by name, protobuf
//! bytes in and out. Single calls block (run them off the UI thread); streams call back on Rx threads.

use jni::objects::{GlobalRef, JByteArray, JClass, JString, JValue};
use jni::sys::{jint, jlong, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

struct Java {
    vm: JavaVM,
    bridge: GlobalRef,
    app: GlobalRef,
}

static JAVA: OnceLock<Java> = OnceLock::new();
type Handler = Box<dyn Fn(Result<&[u8], &str>) + Send + Sync>;
static HANDLERS: OnceLock<Mutex<HashMap<i64, Handler>>> = OnceLock::new();
static NEXT_TOKEN: AtomicI64 = AtomicI64::new(1);

fn handlers() -> &'static Mutex<HashMap<i64, Handler>> {
    HANDLERS.get_or_init(Default::default)
}

/// System.load runs this on the loading thread, whose class loader can see Spatch's classes;
/// later native threads can't, so both classes are kept here.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("SpatchApp").with_max_level(log::LevelFilter::Info));
    let java = (|| -> jni::errors::Result<Java> {
        let mut env = vm.get_env()?;
        let bridge = env.find_class("spatch/CoreBridge")?;
        let bridge = env.new_global_ref(bridge)?;
        let app = env.find_class("spatch/SpatchApp")?;
        let app = env.new_global_ref(app)?;
        Ok(Java { vm, bridge, app })
    })();
    match java {
        Ok(j) => {
            let _ = JAVA.set(j);
        }
        Err(e) => log::error!("JNI_OnLoad: {e}"),
    }
    JNI_VERSION_1_6
}

fn java() -> Result<&'static Java, String> {
    JAVA.get().ok_or_else(|| "Java bridge isn't loaded".to_string())
}

/// A pending Java exception as text, cleared so the thread can go on.
fn exception(env: &mut JNIEnv, e: jni::errors::Error) -> String {
    if env.exception_check().unwrap_or(false) {
        let thrown = env.exception_occurred().ok();
        let _ = env.exception_clear();
        if let Some(t) = thrown {
            if let Ok(s) = env.call_method(&t, "toString", "()Ljava/lang/String;", &[]).and_then(|v| v.l()) {
                if let Ok(text) = env.get_string(&JString::from(s)) {
                    return text.into();
                }
            }
        }
    }
    e.to_string()
}

/// One request to a core service; blocks until it answers. Never call on the UI thread.
pub fn call(service: &str, method: &str, request: &[u8]) -> Result<Vec<u8>, String> {
    let j = java()?;
    let mut env = j.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<Vec<u8>> {
        let s = env.new_string(service)?;
        let m = env.new_string(method)?;
        let r = env.byte_array_from_slice(request)?;
        let class: &JClass = j.bridge.as_obj().into();
        let out = env
            .call_static_method(class, "call", "(Ljava/lang/String;Ljava/lang/String;[B)[B", &[JValue::Object(&s), JValue::Object(&m), JValue::Object(&r)])?
            .l()?;
        env.convert_byte_array(JByteArray::from(out))
    })();
    result.map_err(|e| exception(&mut env, e))
}

/// Opens a stream; `handler` gets every reply (or the error that ended it) on Spotify's threads.
/// Returns the id for `cancel`.
pub fn subscribe(service: &str, method: &str, request: &[u8], handler: impl Fn(Result<&[u8], &str>) + Send + Sync + 'static) -> Result<i64, String> {
    let j = java()?;
    let token = NEXT_TOKEN.fetch_add(1, Ordering::Relaxed);
    handlers().lock().unwrap().insert(token, Box::new(handler));
    let mut env = j.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<i64> {
        let s = env.new_string(service)?;
        let m = env.new_string(method)?;
        let r = env.byte_array_from_slice(request)?;
        let class: &JClass = j.bridge.as_obj().into();
        env.call_static_method(class, "subscribe", "(Ljava/lang/String;Ljava/lang/String;[BJ)J", &[JValue::Object(&s), JValue::Object(&m), JValue::Object(&r), JValue::Long(token)])?
            .j()
    })();
    result.map_err(|e| {
        handlers().lock().unwrap().remove(&token);
        exception(&mut env, e)
    })
}

pub fn cancel(id: i64) {
    if let Ok(j) = java() {
        if let Ok(mut env) = j.vm.attach_current_thread_permanently() {
            let class: &JClass = j.bridge.as_obj().into();
            if env.call_static_method(class, "cancel", "(J)V", &[JValue::Long(id)]).is_err() {
                let _ = env.exception_clear();
            }
        }
    }
}

/// Asks Java to take the app's surface down (it calls nativeClose on the main thread).
pub fn close_app() {
    if let Ok(j) = java() {
        if let Ok(mut env) = j.vm.attach_current_thread_permanently() {
            let class: &JClass = j.app.as_obj().into();
            if env.call_static_method(class, "close", "()V", &[]).is_err() {
                let _ = env.exception_clear();
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeNext<'l>(env: JNIEnv<'l>, _class: JClass<'l>, token: jlong, value: JByteArray<'l>) {
    let Ok(bytes) = env.convert_byte_array(&value) else { return };
    if let Some(h) = handlers().lock().unwrap().get(&token) {
        h(Ok(&bytes));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeError<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>, token: jlong, message: JString<'l>) {
    let text: String = env.get_string(&message).map(Into::into).unwrap_or_default();
    if let Some(h) = handlers().lock().unwrap().remove(&token) {
        h(Err(&text));
    }
}
