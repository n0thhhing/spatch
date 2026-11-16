//! JNI boundary for Spatch's Android views. Network calls run on the Java worker.
use crate::{core, library, player};
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};

static CONNECTED: AtomicBool = AtomicBool::new(false);
static STATE: Mutex<(i64, Option<player::NowPlaying>)> = Mutex::new((0, None));

#[cfg(feature = "preview")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_PreviewActivity_nativePreview(_: JNIEnv, _: JClass) {
    CONNECTED.store(true, Ordering::Release);
    *STATE.lock().unwrap() = (1, Some(crate::preview::player()));
    *LIBRARY_ITEMS.lock().unwrap() = Some(crate::preview::library());
}

fn strings(
    env: &mut JNIEnv,
    values: impl IntoIterator<Item = String>,
) -> jni::errors::Result<jobjectArray> {
    let values: Vec<_> = values.into_iter().collect();
    let array: JObjectArray =
        env.new_object_array(values.len() as i32, "java/lang/String", JString::default())?;
    for (index, value) in values.into_iter().enumerate() {
        let text = env.new_string(value)?;
        env.set_object_array_element(&array, index as i32, &text)?;
        env.delete_local_ref(text)?;
    }
    Ok(array.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeConnect(_: JNIEnv, _: JClass) -> jboolean {
    if CONNECTED.swap(true, Ordering::AcqRel) {
        return JNI_TRUE;
    }
    let result = core::subscribe(
        player::SERVICE,
        "GetState",
        &player::state_request(),
        |reply| match reply {
            Ok(bytes) => {
                if let Ok(now) = player::NowPlaying::decode(bytes) {
                    let mut state = STATE.lock().unwrap();
                    state.0 += 1;
                    state.1 = Some(now);
                }
            }
            Err(_) => CONNECTED.store(false, Ordering::Release),
        },
    );
    if result.is_err() {
        CONNECTED.store(false, Ordering::Release);
    }
    if CONNECTED.load(Ordering::Acquire) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlayer(
    mut env: JNIEnv,
    _: JClass,
    revision: jlong,
) -> jobjectArray {
    let (serial, state) = {
        let guard = STATE.lock().unwrap();
        if guard.0 == revision {
            return std::ptr::null_mut();
        }
        (guard.0, guard.1.clone())
    };
    let Some(now) = state else {
        return std::ptr::null_mut();
    };
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |time| time.as_millis() as u64);
    let position = now.position_at(now_ms);
    let fields = [
        serial.to_string(),
        now.uri,
        now.title,
        now.artist,
        now.album,
        now.image,
        now.playing.to_string(),
        now.shuffle.to_string(),
        now.repeat.to_string(),
        position.to_string(),
        now.duration_ms.to_string(),
    ];
    strings(
        &mut env,
        fields.into_iter().chain(
            now.up_next
                .into_iter()
                .flat_map(|(title, artist, image)| [title, artist, image]),
        ),
    )
    .unwrap_or(std::ptr::null_mut())
}

static LIBRARY_ITEMS: Mutex<Option<Vec<library::Item>>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibrary(env: JNIEnv, _: JClass) -> jstring {
    if LIBRARY_ITEMS.lock().unwrap().is_some() {
        return env
            .new_string("")
            .map_or(std::ptr::null_mut(), JString::into_raw);
    }
    let result = core::call(library::SERVICE, "All", &library::request(300))
        .and_then(|bytes| library::decode(&bytes));
    let status = match result {
        Ok(items) => {
            *LIBRARY_ITEMS.lock().unwrap() = Some(items);
            String::new()
        }
        Err(error) => error,
    };
    env.new_string(status)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeEntries(
    mut env: JNIEnv,
    _: JClass,
    filter: jint,
    limit: jint,
) -> jobjectArray {
    let items = LIBRARY_ITEMS.lock().unwrap();
    let fields = items
        .iter()
        .flatten()
        .filter(|item| filter == -1 || item.kind.chip() == Some(filter))
        .take(limit.max(0) as usize)
        .flat_map(|item| {
            [
                item.name.clone(),
                item.subtitle.clone(),
                item.uri.clone(),
                item.image.clone(),
                item.kind.chip().unwrap_or(-1).to_string(),
            ]
        });
    strings(&mut env, fields).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLiked(env: JNIEnv, _: JClass) -> jstring {
    let items = LIBRARY_ITEMS.lock().unwrap();
    let uri = items
        .iter()
        .flatten()
        .find(|item| item.uri.ends_with(":collection"))
        .map_or("", |item| &item.uri);
    env.new_string(uri)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCommand(
    mut env: JNIEnv,
    _: JClass,
    action: jint,
    value: jlong,
    uri: JString,
) -> jboolean {
    use player::Command;
    let command = match action {
        0 => Command::Pause,
        1 => Command::Resume,
        2 => Command::Previous,
        3 => Command::Next,
        4 if value >= 0 => Command::SeekTo(value),
        5 => Command::Shuffle(value != 0),
        6 if (0..=2).contains(&value) => Command::Repeat(value as u8),
        7 => {
            let Ok(text) = env.get_string(&uri) else {
                return JNI_FALSE;
            };
            let uri = String::from(text);
            if !uri.starts_with("spotify:") {
                return JNI_FALSE;
            }
            Command::Play(uri)
        }
        _ => return JNI_FALSE,
    };
    #[cfg(feature = "preview")]
    {
        let mut state = STATE.lock().unwrap();
        if let Some(now) = &mut state.1 {
            crate::preview::apply(now, command);
            state.0 += 1;
            return JNI_TRUE;
        }
    }
    let (method, request) = player::command(command);
    if core::call(player::SERVICE, method, &request).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// Packed UI route: page, browse origin, queue origin, then the active collection filter.
const HOME: i32 = 0;
const LIBRARY: i32 = 1;
const PLAYER: i32 = 2;
const IMMERSIVE: i32 = 3;
const QUEUE: i32 = 4;

fn navigate(route: i32, action: i32, category: i32) -> i32 {
    let page = route & 7;
    let browse = (route >> 3) & 1;
    let queue_from = (route >> 4) & 7;
    let mut next = route & !7;
    let destination = match action {
        0 if page <= LIBRARY => HOME,
        1 if page <= LIBRARY && (-1..=3).contains(&category) => {
            next = (next & 0x7f) | ((category + 1) << 7);
            LIBRARY
        }
        2 if page <= LIBRARY => {
            next = (next & !(1 << 3)) | (page << 3);
            PLAYER
        }
        3 if page == PLAYER => IMMERSIVE,
        4 if page == PLAYER || page == IMMERSIVE => {
            next = (next & !(7 << 4)) | (page << 4);
            QUEUE
        }
        5 => match page {
            LIBRARY => HOME,
            PLAYER => browse,
            IMMERSIVE => PLAYER,
            QUEUE => queue_from,
            _ => HOME,
        },
        _ => page,
    };
    next | destination
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeNavigate(
    _: JNIEnv,
    _: JClass,
    route: jint,
    action: jint,
    category: jint,
) -> jint {
    navigate(route, action, category)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn back_returns_through_queue_immersive_player_and_filtered_library() {
        let library = navigate(HOME, 1, 2);
        let player = navigate(library, 2, 0);
        assert_eq!(navigate(library, 3, 0), library);
        let immersive = navigate(player, 3, 0);
        assert_eq!(immersive & 7, IMMERSIVE);
        let queue = navigate(immersive, 4, 0);
        let back = navigate(queue, 5, 0);
        assert_eq!(back & 7, IMMERSIVE);
        let back = navigate(back, 5, 0);
        assert_eq!(back & 7, PLAYER);
        let back = navigate(back, 5, 0);
        assert_eq!(back & 7, LIBRARY);
        assert_eq!((back >> 7) - 1, 2);
    }
}
