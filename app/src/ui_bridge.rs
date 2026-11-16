//! JNI boundary for Spatch's Android views. Network calls run on the Java worker.
use crate::{core, library, player};
use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobjectArray, jstring};
use std::sync::{
    Mutex,
    atomic::{AtomicBool, Ordering},
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
        now.source,
        now.album_artist,
        now.release,
        now.album_position,
        if now.explicit { "Explicit".into() } else { String::new() },
        now.context,
    ];
    strings(
        &mut env,
        fields.into_iter().chain(
            now.up_next
                .into_iter()
                .flat_map(|t| [t.title, t.artist, t.image, t.uri]),
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

/// Fetches the library again; true when it differs from what the UI has. Blocks: off the UI thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRefreshLibrary(_: JNIEnv, _: JClass) -> jboolean {
    #[cfg(feature = "preview")]
    return JNI_FALSE;
    #[cfg(not(feature = "preview"))]
    {
        let Ok(items) = core::call(library::SERVICE, "All", &library::request(300)).and_then(|bytes| library::decode(&bytes)) else {
            return JNI_FALSE;
        };
        let mut current = LIBRARY_ITEMS.lock().unwrap();
        if current.as_ref() == Some(&items) {
            return JNI_FALSE;
        }
        *current = Some(items);
        JNI_TRUE
    }
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
        .find(|item| library::is_liked_uri(&item.uri))
        .map_or("spotify:collection:tracks", |item| item.uri.as_str());
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
        9 => {
            let Ok(text) = env.get_string(&uri) else {
                return JNI_FALSE;
            };
            let uri = String::from(text);
            if !uri.starts_with("spotify:") {
                return JNI_FALSE;
            }
            Command::AddToQueue(uri)
        }
        // Jump to Up Next entry `value`, if it is still the track the UI showed there.
        8 => {
            let Ok(text) = env.get_string(&uri) else {
                return JNI_FALSE;
            };
            let uri = String::from(text);
            let state = STATE.lock().unwrap();
            let Some(next) = state.1.as_ref().and_then(|now| now.up_next.get(value.max(0) as usize)).filter(|t| t.uri == uri) else {
                return JNI_FALSE;
            };
            Command::SkipTo { uri, uid: next.uid.clone() }
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylist(
    mut env: JNIEnv,
    _: JClass,
    uri: JString,
    offset: jint,
) -> jobjectArray {
    let Ok(uri) = env.get_string(&uri) else {
        return std::ptr::null_mut();
    };
    let uri = String::from(uri);
    #[cfg(feature = "preview")]
    if uri.starts_with("spotify:preview:") || library::is_liked_uri(&uri) {
        let demo = crate::preview::player();
        // Forty rows (the demo tracks repeated) so long-list scrolling can be tried offline.
        let tracks: Vec<_> = std::iter::once((demo.title, demo.artist, demo.image))
            .chain(demo.up_next.into_iter().map(|t| (t.title, t.artist, t.image)))
            .collect();
        let tracks = tracks.iter().cloned().cycle().take(40);
        return strings(
            &mut env,
            ["".into(), "0".into()]
                .into_iter()
                .chain(tracks.enumerate().flat_map(|(i, (title, artist, image))| {
                    [title, artist, format!("spotify:preview:{i}"), image]
                })),
        )
        .unwrap_or(std::ptr::null_mut());
    }

    if !library::is_playlist_uri(&uri) {
        return std::ptr::null_mut();
    }
    let uri = if library::is_liked_uri(&uri) {
        "spotify:collection:tracks".into()
    } else {
        uri
    };
    use crate::protos::{
        com::spotify::cosmos::util::policy::proto as metadata,
        com::spotify::playlist::policy::proto as policy, spotify::playlist::esperanto::proto as es,
    };
    use prost::Message;
    let offset = offset.max(0);
    let request = es::PlaylistGetRequest {
        uri,
        query: Some(es::PlaylistQuery {
            range: Some(es::PlaylistRange {
                start: offset,
                length: 100,
            }),
            ..Default::default()
        }),
        policy: Some(policy::PlaylistRequestDecorationPolicy {
            track: Some(policy::PlaylistTrackDecorationPolicy {
                track: Some(metadata::TrackDecorationPolicy {
                    name: true,
                    link: true,
                    ..Default::default()
                }),
                artist: Some(metadata::ArtistDecorationPolicy {
                    name: true,
                    ..Default::default()
                }),
                ..Default::default()
            }),
            item: Some(policy::PlaylistItemDecorationPolicy {
                uri: true,
                metadata: Some(policy::ItemMetadataPolicy {
                    name: true,
                    image: true,
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        }),
    };
    let result = core::call(
        "spotify.playlist_esperanto.proto.PlaylistDataService",
        "Get",
        &request.encode_to_vec(),
    )
    .and_then(|bytes| es::PlaylistGetResponse::decode(bytes.as_slice()).map_err(|e| e.to_string()))
    .and_then(|reply| {
        reply.data.ok_or_else(|| {
            reply
                .status
                .map_or_else(|| "No playlist data".into(), |status| status.reason)
        })
    });
    let fields = match result {
        Ok(data) if data.loading_contents && data.item.is_empty() => vec!["loading".into()],
        Ok(data) => {
            let end = offset as usize + data.item.len();
            let more = !data.item.is_empty()
                && end < data.unranged_length.max(data.unfiltered_length) as usize;
            ["".into(), if more { end.to_string() } else { "0".into() }]
                .into_iter()
                .chain(
                    data.item
                        .into_iter()
                        .map(|item| {
                            let mut title = item
                                .metadata
                                .as_ref()
                                .map_or_else(String::new, |metadata| metadata.name.clone());
                            let image =
                                item.metadata.as_ref().map_or_else(String::new, |metadata| {
                                    player::image_url(&metadata.image)
                                });
                            let artist = item
                                .track_metadata
                                .as_ref()
                                .and_then(|track| track.artist.first())
                                .map_or_else(String::new, |artist| artist.name.clone());
                            if title.is_empty() {
                                title = item
                                    .track_metadata
                                    .map_or_else(|| "Track unavailable".into(), |track| track.name);
                            }
                            [title, artist, item.uri, image]
                        })
                        .flatten(),
                )
                .collect::<Vec<_>>()
        }
        Err(error) => vec![if error.is_empty() {
            "Couldn’t load playlist".into()
        } else {
            error
        }],
    };
    strings(&mut env, fields).unwrap_or(std::ptr::null_mut())
}

#[cfg(feature = "preview")]
static PREVIEW_SAVED: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// 1 saved, 0 not saved, -1 unknown (call failed). Blocks: Java calls it off the UI thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSaved(mut env: JNIEnv, _: JClass, uri: JString) -> jint {
    let Ok(uri) = env.get_string(&uri).map(String::from) else { return -1 };
    if !uri.starts_with("spotify:") {
        return -1;
    }
    #[cfg(feature = "preview")]
    return PREVIEW_SAVED.lock().unwrap().contains(&uri) as jint;
    #[cfg(not(feature = "preview"))]
    match core::call(crate::collection::SERVICE, "Contains", &crate::collection::contains_request(&uri))
        .and_then(|bytes| crate::collection::decode_contains(&bytes))
    {
        Ok(saved) => saved as jint,
        Err(_) => -1,
    }
}

/// Saves (or unsaves) a track to Liked Songs; true when Spotify accepted it. Blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSave(mut env: JNIEnv, _: JClass, uri: JString, save: jboolean) -> jboolean {
    let Ok(uri) = env.get_string(&uri).map(String::from) else { return JNI_FALSE };
    if !uri.starts_with("spotify:") {
        return JNI_FALSE;
    }
    #[cfg(feature = "preview")]
    {
        let mut saved = PREVIEW_SAVED.lock().unwrap();
        saved.retain(|u| u != &uri);
        if save != JNI_FALSE {
            saved.push(uri);
        }
        return JNI_TRUE;
    }
    #[cfg(not(feature = "preview"))]
    {
        let method = if save != JNI_FALSE { "Add" } else { "Remove" };
        match core::call(crate::collection::SERVICE, method, &crate::collection::change_request(&uri))
            .and_then(|bytes| crate::collection::decode_change(&bytes))
        {
            Ok(()) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        }
    }
}

// Packed UI route: page, browse origin, queue origin, then the active collection filter.
const HOME: i32 = 0;
const LIBRARY: i32 = 1;
const PLAYER: i32 = 2;
const IMMERSIVE: i32 = 3;
const QUEUE: i32 = 4;
const PLAYLIST: i32 = 5;
const SETTINGS: i32 = 6;

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
        3 if page == IMMERSIVE => PLAYER,
        6 if page <= LIBRARY => {
            next = (next & !(1 << 3)) | (page << 3);
            PLAYLIST
        }
        7 if page <= LIBRARY => {
            next = (next & !(1 << 3)) | (page << 3);
            SETTINGS
        }
        4 if page == PLAYER || page == IMMERSIVE => {
            next = (next & !(7 << 4)) | (page << 4);
            QUEUE
        }
        5 => match page {
            LIBRARY => HOME,
            PLAYER | PLAYLIST | SETTINGS => browse,
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
    fn player_art_toggles_immersive_and_playlist_back_preserves_library_filter() {
        let library = navigate(HOME, 1, 0);
        let player = navigate(library, 2, 0);
        assert_eq!(player & 7, PLAYER);
        let immersive = navigate(player, 3, 0);
        assert_eq!(immersive & 7, IMMERSIVE);
        assert_eq!(navigate(immersive, 3, 0), player);
        let playlist = navigate(library, 6, 0);
        assert_eq!(playlist & 7, PLAYLIST);
        assert_eq!(navigate(playlist, 5, 0), library | (1 << 3));
    }

    #[test]
    fn settings_opens_from_browse_and_returns_to_it() {
        let library = navigate(HOME, 1, 1);
        let settings = navigate(library, 7, 0);
        assert_eq!(settings & 7, SETTINGS);
        assert_eq!(navigate(settings, 2, 0), settings);
        assert_eq!(navigate(settings, 5, 0) & 7, LIBRARY);
    }

    #[test]
    fn back_returns_through_queue_immersive_player_and_filtered_library() {
        let library = navigate(HOME, 1, 2);
        assert_eq!(navigate(library, 3, 0), library);
        let player = navigate(library, 2, 0);
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
