//! Calls into Spotify's core through Java's `spatch.CoreBridge`: Esperanto services by name, protobuf
//! bytes in and out. Single calls block (run them off the UI thread); streams call back on Rx threads.

use jni::objects::{GlobalRef, JByteArray, JClass, JString, JValue};
use jni::sys::jlong;
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

struct Java {
    vm: JavaVM,
    class: GlobalRef,
}

type Handler = Arc<dyn Fn(Result<&[u8], &str>) + Send + Sync>;

static JAVA: OnceLock<Java> = OnceLock::new();
static UI_HOST: OnceLock<Java> = OnceLock::new();
static UI_QUEUED: AtomicBool = AtomicBool::new(false);
static HANDLERS: Mutex<Option<HashMap<i64, Handler>>> = Mutex::new(None);
static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSION: AtomicI64 = AtomicI64::new(0);
static SESSION_GATE: Mutex<()> = Mutex::new(());

pub fn session() -> i64 { SESSION.load(Ordering::SeqCst) }

/// Commit a reply only if its account is still current; never hold this gate over network calls.
pub fn in_session<R>(epoch: i64, commit: impl FnOnce() -> R) -> Option<R> {
    let _gate = SESSION_GATE.lock().unwrap();
    (session() == epoch).then(commit)
}

/// Invalidates replies already in flight before the account's transport was reset.
pub fn reset_session(reset: impl FnOnce()) {
    let _gate = SESSION_GATE.lock().unwrap();
    SESSION.fetch_add(1, Ordering::SeqCst);
    UI_QUEUED.store(false, Ordering::SeqCst);
    handlers(|h| h.clear());
    reset();
}

fn handlers<R>(f: impl FnOnce(&mut HashMap<i64, Handler>) -> R) -> R {
    f(HANDLERS.lock().unwrap().get_or_insert_with(HashMap::new))
}

/// Java calls this right after System.load, on a thread whose class loader sees Spatch's classes
/// (later native threads use the global class reference kept here).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeInit<'l>(mut env: JNIEnv<'l>, _: JClass<'l>) {
    crate::init_log();
    let found = (|| -> jni::errors::Result<Java> {
        let vm = env.get_java_vm()?;
        let class = env.find_class("spatch/CoreBridge")?;
        Ok(Java { vm, class: env.new_global_ref(class)? })
    })();
    match found {
        Ok(java) => {
            let _ = JAVA.set(java);
        }
        Err(e) => log::error!("CoreBridge init failed: {e}"),
    }
}

fn java() -> Result<&'static Java, String> {
    JAVA.get().ok_or_else(|| "Java bridge isn't loaded".to_string())
}

/// A pending Java exception as text (and cleared, so the thread can go on), else the JNI error.
fn describe(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    if env.exception_check().unwrap_or(false) {
        let thrown = env.exception_occurred().ok();
        let _ = env.exception_clear();
        let text = thrown
            .and_then(|t| env.call_method(&t, "toString", "()Ljava/lang/String;", &[]).and_then(|v| v.l()).ok())
            .and_then(|s| env.get_string(&JString::from(s)).ok().map(String::from));
        if let Some(text) = text {
            return text;
        }
    }
    error.to_string()
}

/// One request to a core service; blocks until it answers. Never call on the UI thread.
pub fn call(service: &str, method: &str, request: &[u8]) -> Result<Vec<u8>, String> {
    call_with_timeout(service, method, request, std::time::Duration::from_secs(8))
}

pub fn call_with_timeout(service: &str, method: &str, request: &[u8], wait: std::time::Duration) -> Result<Vec<u8>, String> {
    let session = SESSION.load(Ordering::SeqCst);
    let java = java()?;
    let mut env = java.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<Vec<u8>> {
        let (service, method) = (env.new_string(service)?, env.new_string(method)?);
        let body = env.byte_array_from_slice(request)?;
        let class: &JClass = java.class.as_obj().into();
        let reply = env
            .call_static_method(
                class,
                "callTimed",
                "(Ljava/lang/String;Ljava/lang/String;[BJ)[B",
                &[JValue::Object(&service), JValue::Object(&method), JValue::Object(&body),
                    JValue::Long(wait.as_millis().clamp(1, 8000) as i64)],
            )?
            .l()?;
        env.convert_byte_array(JByteArray::from(reply))
    })();
    let bytes = result.map_err(|e| describe(&mut env, e))?;
    if SESSION.load(Ordering::SeqCst) != session { return Err("Account session changed".into()); }
    Ok(bytes)
}

/// A playlist's items (playlist4 SelectedListContent) from Spotify's playlist service over the app's web connection.
pub fn playlist_contents(id: &str) -> Result<Vec<u8>, String> {
    let session = SESSION.load(Ordering::SeqCst);
    let java = java()?;
    let mut env = java.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<Vec<u8>> {
        let id = env.new_string(id)?;
        let class: &JClass = java.class.as_obj().into();
        let reply = env.call_static_method(class, "playlistContents", "(Ljava/lang/String;)[B", &[JValue::Object(&id)])?.l()?;
        env.convert_byte_array(JByteArray::from(reply))
    })();
    let bytes = result.map_err(|e| describe(&mut env, e))?;
    if SESSION.load(Ordering::SeqCst) != session { return Err("Account session changed".into()); }
    Ok(bytes)
}

/// Spotify's extended-metadata service over the app's web connection: BatchedEntityRequest in, BatchedExtensionResponse out.
pub fn extended_metadata(request: &[u8]) -> Result<Vec<u8>, String> {
    let session = SESSION.load(Ordering::SeqCst);
    let java = java()?;
    let mut env = java.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<Vec<u8>> {
        let body = env.byte_array_from_slice(request)?;
        let class: &JClass = java.class.as_obj().into();
        let reply = env.call_static_method(class, "extendedMetadata", "([B)[B", &[JValue::Object(&body)])?.l()?;
        env.convert_byte_array(JByteArray::from(reply))
    })();
    let bytes = result.map_err(|e| describe(&mut env, e))?;
    if SESSION.load(Ordering::SeqCst) != session { return Err("Account session changed".into()); }
    Ok(bytes)
}

/// Public catalog details for a page of track links, using a separate anonymous Spotify session.
pub fn public_track_metadata(uris: &[String]) -> Result<Vec<String>, String> {
    let session = SESSION.load(Ordering::SeqCst);
    let java = java()?;
    let mut env = java.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let result = (|| -> jni::errors::Result<Vec<u8>> {
        let input = env.byte_array_from_slice(&crate::cache::encode(uris))?;
        let class: &JClass = java.class.as_obj().into();
        let reply = env.call_static_method(class, "publicTrackMetadata", "([B)[B", &[JValue::Object(&input)])?.l()?;
        env.convert_byte_array(JByteArray::from(reply))
    })();
    let bytes = result.map_err(|e| describe(&mut env, e))?;
    if SESSION.load(Ordering::SeqCst) != session { return Err("Account session changed".into()); }
    crate::cache::decode(&bytes).ok_or_else(|| "Invalid public track metadata".into())
}

/// The phone's two-letter country.
pub fn device_country() -> Option<String> {
    let java = java().ok()?;
    let mut env = java.vm.attach_current_thread_permanently().ok()?;
    let class: &JClass = java.class.as_obj().into();
    let value = env.call_static_method(class, "deviceCountry", "()Ljava/lang/String;", &[]).and_then(|v| v.l()).ok()?;
    let text: String = env.get_string(&JString::from(value)).ok()?.into();
    (text.len() == 2).then_some(text)
}

/// Opens a stream; `handler` gets every reply (or the error that ended it) on Spotify's threads.
/// Returns the id for [`cancel`].
pub fn subscribe(
    service: &str,
    method: &str,
    request: &[u8],
    handler: impl Fn(Result<&[u8], &str>) + Send + Sync + 'static,
) -> Result<i64, String> {
    let java = java()?;
    let mut env = java.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
    let token = NEXT.fetch_add(1, Ordering::Relaxed);
    let session = SESSION.load(Ordering::SeqCst);
    handlers(|h| h.insert(token, Arc::new(move |reply| {
        in_session(session, || handler(reply));
    })));
    let result = (|| -> jni::errors::Result<i64> {
        let (service, method) = (env.new_string(service)?, env.new_string(method)?);
        let body = env.byte_array_from_slice(request)?;
        let class: &JClass = java.class.as_obj().into();
        env.call_static_method(
            class,
            "subscribe",
            "(Ljava/lang/String;Ljava/lang/String;[BJ)J",
            &[JValue::Object(&service), JValue::Object(&method), JValue::Object(&body), JValue::Long(token)],
        )?
        .j()
    })();
    let id = result.map_err(|e| {
        handlers(|h| h.remove(&token));
        describe(&mut env, e)
    })?;
    if SESSION.load(Ordering::SeqCst) != session {
        cancel(id);
        return Err("Account session changed".into());
    }
    Ok(id)
}

pub fn cancel(id: i64) {
    handlers(|h| h.remove(&id));
    let Ok(java) = java() else { return };
    let Ok(mut env) = java.vm.attach_current_thread_permanently() else { return };
    let class: &JClass = java.class.as_obj().into();
    if env.call_static_method(class, "cancel", "(J)V", &[JValue::Long(id)]).is_err() {
        let _ = env.exception_clear();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeNext<'l>(env: JNIEnv<'l>, _: JClass<'l>, token: jlong, value: JByteArray<'l>) {
    let Ok(bytes) = env.convert_byte_array(&value) else { return };
    if let Some(handler) = handlers(|h| h.get(&token).cloned()) {
        handler(Ok(&bytes));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeError<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, token: jlong, message: JString<'l>) {
    let text: String = env.get_string(&message).map(Into::into).unwrap_or_default();
    if let Some(handler) = handlers(|h| h.remove(&token)) {
        handler(Err(&text));
    }
}

/// Registers `spatch.SpatchUi` so Rust can wake the UI immediately on state pushes instead of polling.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeAttachUi<'l>(env: JNIEnv<'l>, class: JClass<'l>) {
    if UI_HOST.get().is_some() {
        return;
    }
    if let (Ok(vm), Ok(global)) = (env.get_java_vm(), env.new_global_ref(class)) {
        let _ = UI_HOST.set(Java { vm, class: global });
    }
}

/// Clears the coalesced push flag right before Java reads the latest revision counters.
pub fn ack_ui() {
    UI_QUEUED.store(false, Ordering::SeqCst);
}

/// Wakes `SpatchUi` on the main thread when Rust state changes (coalesced to one in-flight push).
/// Must never be called while holding a Rust state mutex.
pub fn notify_ui() {
    let Some(ui) = UI_HOST.get() else { return };
    if UI_QUEUED.swap(true, Ordering::SeqCst) {
        return;
    }
    let Ok(mut env) = ui.vm.attach_current_thread() else {
        UI_QUEUED.store(false, Ordering::SeqCst);
        return;
    };
    let class: &JClass = ui.class.as_obj().into();
    let handled = env
        .call_static_method(class, "onNativeChange", "()Z", &[])
        .and_then(|v| v.z())
        .unwrap_or_else(|_| {
            let _ = env.exception_clear();
            false
        });
    if !handled {
        UI_QUEUED.store(false, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    #[test]
    fn cancellation_releases_handlers_and_callbacks_can_cancel_themselves() {
        let token = NEXT.fetch_add(1, Ordering::Relaxed);
        let calls = Arc::new(AtomicUsize::new(0));
        let counted = calls.clone();
        handlers(|h| h.insert(token, Arc::new(move |_| {
            counted.fetch_add(1, Ordering::Relaxed);
            cancel(token);
        })));
        let handler = handlers(|h| h.get(&token).cloned()).unwrap();
        handler(Ok(b"reply"));
        assert_eq!(calls.load(Ordering::Relaxed), 1);
        assert!(handlers(|h| !h.contains_key(&token)));
        assert_eq!(Arc::strong_count(&handler), 1);
        let epoch = session();
        assert_eq!(in_session(epoch, || 7), Some(7));
        reset_session(|| {});
        assert_eq!(in_session(epoch, || panic!("stale account reply was committed")), None::<()>);
        assert_eq!(in_session(session(), || 9), Some(9));
    }
}
