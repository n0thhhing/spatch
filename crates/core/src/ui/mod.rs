//! What SpatchUi.java calls. Every function that touches Spotify blocks, so Java runs them on its
//! own worker threads; the cheap readers (`nativePlayer`, `nativeEntries`) are safe on the UI thread.

mod account;
mod downloads;
mod library_api;
mod liked;
mod playlist_api;

use crate::bridge;
use library_api::*;
use playlist_api::*;
use crate::spotify::{library, player, playlist};
#[cfg(not(feature = "preview"))]
use crate::spotify::collection;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use crate::protos::com::spotify::player::esperanto::proto as es;
#[cfg(not(feature = "preview"))]
use prost::Message;
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jobjectArray, jstring};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

static CONNECTED: AtomicBool = AtomicBool::new(false);
/// A counter that moves with every state change, and the latest state.
static PLAYER: Mutex<(i64, Option<player::NowPlaying>)> = Mutex::new((0, None));
static LIBRARY: Mutex<Option<Vec<library::Item>>> = Mutex::new(None);
#[cfg(not(feature = "preview"))]
static QUEUE_CONNECTED: AtomicBool = AtomicBool::new(false);
static QUEUE_STATE: Mutex<Option<es::Queue>> = Mutex::new(None);

fn current_queue(now: &player::NowPlaying, queue: &es::Queue) -> bool {
    (queue.queue_revision == now.queue_revision || queue.queue_revision == 0 || now.queue_revision == 0) &&
        queue.track.as_ref().and_then(|t| t.context_track.as_ref())
            .map_or(now.uri.is_empty(), |t| t.uri == now.uri)
}

fn queue_key(track: &player::NextTrack, revision: u64) -> String {
    format!("{revision}:{}:{}", track.uid, track.uri)
}

#[cfg(not(feature = "preview"))]
fn connect_queue() {
    if QUEUE_CONNECTED.swap(true, Ordering::AcqRel) {
        return;
    }
    if bridge::subscribe(player::SERVICE, "GetQueue", &es::GetQueueRequest::default().encode_to_vec(), |reply| {
        let mut changed = false;
        {
            let mut state = PLAYER.lock().unwrap();
            let mut queue = QUEUE_STATE.lock().unwrap();
            match reply {
                Ok(bytes) => {
                    if let Ok(next) = es::Queue::decode(bytes) {
                        *queue = Some(next);
                        state.0 += 1;
                        changed = true;
                    }
                }
                Err(_) => {
                    *queue = None;
                    state.0 += 1;
                    QUEUE_CONNECTED.store(false, Ordering::Release);
                    changed = true;
                }
            }
        }
        if changed {
            bridge::notify_ui();
        }
    }).is_err() {
        QUEUE_CONNECTED.store(false, Ordering::Release);
    }
}

/// Your Spotify username, learned from a playlist you own; Liked Songs' link needs it.
static USERNAME: Mutex<Option<String>> = Mutex::new(None);
/// What the last username lookup tried, shown on the Liked Songs page when it fails.
static LIKED_NOTE: Mutex<String> = Mutex::new(String::new());
/// The Liked Songs link the playlist service accepted, once one has worked.
static LIKED_WORKING: Mutex<Option<String>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_CoreBridge_nativeResetSession(_: JNIEnv, _: JClass) {
    bridge::reset_session(|| {
        stop_watching_playlist();
        crate::cache::set_account(None);
        crate::spotify::downloads::reset();
        *USERNAME.lock().unwrap() = None;
        *LIKED_WORKING.lock().unwrap() = None;
        LIKED_NOTE.lock().unwrap().clear();
        *LIBRARY.lock().unwrap() = None;
        *QUEUE_STATE.lock().unwrap() = None;
        let mut player = PLAYER.lock().unwrap();
        player.0 += 1;
        player.1 = None;
        drop(player);
        SONGS.lock().unwrap().clear();
        CONNECTED.store(false, Ordering::Release);
        LIBRARY_LIVE.store(false, Ordering::Release);
        #[cfg(not(feature = "preview"))]
        {
            QUEUE_CONNECTED.store(false, Ordering::Release);
            LIBRARY_WATCHED.store(false, Ordering::Release);
            *FOUND_ITEMS.lock().unwrap() = None;
        }
        LIBRARY_REVISION.fetch_add(1, Ordering::SeqCst);
    });
}

fn remember_username(name: &str) {
    crate::cache::set_account(Some(name));
    *USERNAME.lock().unwrap() = Some(name.to_string());
}

/// Every spelling of the Liked Songs link, most likely first: what the library lists, what the username
/// gives, then the two names Spotify's own code compares against.
#[cfg_attr(feature = "preview", allow(dead_code))]
fn liked_candidates(first: &str) -> Vec<String> {
    let listed = LIBRARY.lock().unwrap().iter().flatten().find(|i| library::is_liked(&i.uri)).map(|i| i.uri.clone());
    let user = USERNAME.lock().unwrap().clone().map(|u| format!("spotify:user:{u}:collection"));
    let mut all: Vec<String> = [Some(first.to_string()), listed, user, Some("spotify:internal:collection:tracks".into()), Some("spotify:collection".into()), Some("spotify:collection:tracks".into())]
        .into_iter()
        .flatten()
        .collect();
    let mut seen = std::collections::HashSet::new();
    all.retain(|u| seen.insert(u.clone()));
    all
}

/// `spotify:user:<name>:collection` (Liked Songs playing) names the account; remember it.
fn learn_username(context: &str) {
    if let Some(name) = context.strip_prefix("spotify:user:").and_then(|rest| rest.strip_suffix(":collection")).filter(|n| !n.is_empty() && !n.contains(':')) {
        remember_username(name);
    }
}
#[cfg(feature = "preview")]
static SAVED: Mutex<Vec<String>> = Mutex::new(Vec::new());

fn packed_strings(env: &JNIEnv, values: &[String]) -> jbyteArray {
    let bytes = crate::cache::encode(values);
    env.byte_array_from_slice(&bytes).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

fn strings(env: &mut JNIEnv, values: Vec<String>) -> jobjectArray {
    let build = |env: &mut JNIEnv| -> jni::errors::Result<jobjectArray> {
        let array: JObjectArray = env.new_object_array(values.len() as i32, "java/lang/String", JString::default())?;
        for (i, value) in values.iter().enumerate() {
            let text = env.new_string(value)?;
            env.set_object_array_element(&array, i as i32, &text)?;
            env.delete_local_ref(text)?;
        }
        Ok(array.into_raw())
    };
    build(env).unwrap_or(std::ptr::null_mut())
}

fn string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map_or(std::ptr::null_mut(), JString::into_raw)
}

fn read(env: &mut JNIEnv, text: &JString) -> Option<String> {
    env.get_string(text).ok().map(String::from)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |t| t.as_millis() as u64)
}

#[cfg(feature = "preview")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_PreviewActivity_nativePreview(_: JNIEnv, _: JClass) {
    CONNECTED.store(true, Ordering::Release);
    *PLAYER.lock().unwrap() = (1, Some(crate::demo::now_playing()));
    *LIBRARY.lock().unwrap() = Some(crate::demo::library());
}

/// Opens the player state stream once; true while it is open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeConnect(_: JNIEnv, _: JClass) -> jboolean {
    if !CONNECTED.swap(true, Ordering::AcqRel) {
        let opened = bridge::subscribe(player::SERVICE, "GetState", &player::state_request(), |reply| match reply {
            Ok(bytes) => {
                if let Ok(now) = player::NowPlaying::decode(bytes) {
                    learn_username(&now.context);
                    {
                        let mut state = PLAYER.lock().unwrap();
                        state.0 += 1;
                        state.1 = Some(now);
                    }
                    bridge::notify_ui();
                }
            }
            Err(_) => CONNECTED.store(false, Ordering::Release),
        });
        if opened.is_err() {
            CONNECTED.store(false, Ordering::Release);
        }
    }
    #[cfg(not(feature = "preview"))]
    if CONNECTED.load(Ordering::Acquire) { connect_queue(); }
    if CONNECTED.load(Ordering::Acquire) { JNI_TRUE } else { JNI_FALSE }
}

/// Acknowledges any pending push notification and snapshots all 5 revision counters in one JNI call:
/// [player, playlist, downloads, downloads_set, library].
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRevisions(env: JNIEnv, _: JClass, out: jni::objects::JLongArray) {
    bridge::ack_ui();
    let player = PLAYER.lock().unwrap().0;
    let vals = [
        player,
        PLAYLIST_REVISION.load(Ordering::SeqCst),
        crate::spotify::downloads::revision() as i64,
        crate::spotify::downloads::set_revision() as i64,
        LIBRARY_REVISION.load(Ordering::SeqCst),
    ];
    let _ = env.set_long_array_region(&out, 0, &vals);
}

/// The player as strings, or null if nothing changed since `revision`:
/// revision, uri, title, artist, album, image, playing, shuffle, repeat, position, duration, source,
/// "Explicit" or "", context uri, the quality label or "", buffering, can skip next, can skip previous, album uri, artist uri; then four strings (title, artist, image, uri) per queued track.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlayer(env: JNIEnv, _: JClass, revision: jlong) -> jbyteArray {
    let (serial, now, queue) = {
        let state = PLAYER.lock().unwrap();
        if state.0 == revision {
            return std::ptr::null_mut();
        }
        let queue = QUEUE_STATE.lock().unwrap();
        let tracks = state.1.as_ref().and_then(|now| queue.as_ref().filter(|q| current_queue(now, q))
            .map(|q| player::next_tracks(&q.next_tracks).collect::<Vec<_>>()));
        (state.0, state.1.clone(), tracks)
    };
    let Some(now) = now else { return std::ptr::null_mut() };
    let mut fields = vec![
        serial.to_string(),
        now.uri.clone(),
        now.title.clone(),
        now.artist.clone(),
        now.album.clone(),
        now.image.clone(),
        now.playing.to_string(),
        now.shuffle.to_string(),
        now.repeat.to_string(),
        now.position_at(now_ms()).to_string(),
        now.duration_ms.to_string(),
        now.source.clone(),
        if now.explicit { "Explicit".into() } else { String::new() },
        now.context.clone(),
        now.quality.clone(),
        now.buffering.to_string(),
        now.can_next.to_string(),
        now.can_previous.to_string(),
        now.album_uri.clone(),
        now.artist_uri.clone(),
    ];
    let editable = queue.is_some() || cfg!(feature = "preview");
    for next in queue.as_ref().unwrap_or(&now.up_next) {
        fields.extend([next.title.clone(), next.artist.clone(), next.image.clone(), next.uri.clone(),
            queue_key(next, now.queue_revision), editable.to_string()]);
    }
    packed_strings(&env, &fields)
}

#[cfg(feature = "preview")]
static PREVIEW_SHUFFLE: Mutex<Vec<(String, i32)>> = Mutex::new(Vec::new());
#[cfg(feature = "preview")]
static PREVIEW_PLAYLISTS: Mutex<Option<std::collections::HashMap<String, Vec<playlist::Track>>>> = Mutex::new(None);
#[cfg(feature = "preview")]
static PREVIEW_PLAYLIST_SEQ: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

/// The shuffle mode Spotify keeps for a playlist: 0 off, 1 shuffle, 2 Smart Shuffle, -1 if it couldn't say.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeShuffleMode(mut env: JNIEnv, _: JClass, uri: JString) -> jint {
    let Some(uri) = read(&mut env, &uri) else { return -1 };
    #[cfg(feature = "preview")]
    return PREVIEW_SHUFFLE.lock().unwrap().iter().find(|(u, _)| *u == uri).map_or(0, |(_, m)| *m);
    #[cfg(not(feature = "preview"))]
    crate::spotify::shuffle::get(&uri).unwrap_or_else(|e| {
        log::warn!("Shuffle state for {uri}: {e}");
        -1
    })
}

/// Stores a playlist's shuffle mode with Spotify; "" on success, else why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSetShuffleMode(mut env: JNIEnv, _: JClass, uri: JString, mode: jint) -> jstring {
    let Some(uri) = read(&mut env, &uri) else { return string(&mut env, "No playlist") };
    #[cfg(feature = "preview")]
    {
        let mut kept = PREVIEW_SHUFFLE.lock().unwrap();
        kept.retain(|(u, _)| *u != uri);
        kept.push((uri, mode));
        string(&mut env, "")
    }
    #[cfg(not(feature = "preview"))]
    {
        let result = crate::spotify::shuffle::set(&uri, mode.clamp(0, 2));
        string(&mut env, &result.err().unwrap_or_default())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCatalog(env: JNIEnv, _: JClass, bytes: JByteArray) -> jbyteArray {
    let Some(bytes) = env.convert_byte_array(bytes).ok() else { return std::ptr::null_mut() };
    #[cfg(feature = "preview")]
    let decoded = std::str::from_utf8(&bytes).ok().map(crate::demo::catalog);
    #[cfg(not(feature = "preview"))]
    let decoded = crate::spotify::catalog::decode(&bytes).ok();
    match decoded {
        Some(fields) => packed_strings(&env, &fields),
        None => std::ptr::null_mut(),
    }
}

/// Results for a search, six strings each (section, title, subtitle, uri, image, category): your library first,
/// then what the core's offline index knows. Blocks for the core's answer; empty for an empty query.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSearch(mut env: JNIEnv, _: JClass, query: JString) -> jbyteArray {
    use crate::spotify::search;
    let query = read(&mut env, &query).unwrap_or_default();
    if query.trim().is_empty() {
        return packed_strings(&env, &[]);
    }
    #[cfg(feature = "preview")]
    let offline = crate::demo::search(&query);
    #[cfg(not(feature = "preview"))]
    let offline = search::offline(query.trim()).unwrap_or_else(|error| {
        log::warn!("Offline search: {error}");
        Vec::new()
    });
    let library = LIBRARY.lock().unwrap().clone().unwrap_or_default();
    let hits = search::merge(&query, &library, offline, 8);
    let fields: Vec<String> = hits.into_iter().flat_map(search::Hit::fields).collect();
    packed_strings(&env, &fields)
}

/// The commit this build came from.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeBuild(mut env: JNIEnv, _: JClass) -> jstring {
    string(&mut env, env!("SPATCH_COMMIT"))
}




/// The open playlist's songs (playlist::FIELDS strings each), kept here so a search doesn't resend them.
static SONGS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Keeps the open playlist's song fields for nativeArrange.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSongs(env: JNIEnv, _: JClass, packed: JByteArray) {
    let Ok(bytes) = env.convert_byte_array(&packed) else { return };
    if let Some(values) = crate::cache::decode(&bytes) {
        *SONGS.lock().unwrap() = values;
    }
}

/// The songs to show, as indexes into the kept songs, for a search and a sort
/// (0 playlist order, 1 title, 2 artist, 3 album, 4 recently added).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeArrange(mut env: JNIEnv, _: JClass, query: JString, sort: jint) -> jni::sys::jintArray {
    let query = read(&mut env, &query).unwrap_or_default();
    let order: Vec<i32> = playlist::arrange(&SONGS.lock().unwrap(), &query, sort).into_iter().map(|i| i as i32).collect();
    let Ok(out) = env.new_int_array(order.len() as i32) else { return std::ptr::null_mut() };
    let _ = env.set_int_array_region(&out, 0, &order);
    out.into_raw()
}

/// Plays `context` from the song `track` at `index` in it; true if Spotify accepted.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlayFrom(mut env: JNIEnv, _: JClass, context: JString, track: JString, index: jint) -> jboolean {
    let (Some(context), Some(track)) = (read(&mut env, &context), read(&mut env, &track)) else { return JNI_FALSE };
    let command = player::Command::PlayFrom { context, track, index: index.max(0) as i64 };
    #[cfg(feature = "preview")]
    {
        let changed = {
            let mut state = PLAYER.lock().unwrap();
            if let Some(now) = &mut state.1 {
                crate::demo::apply(now, command);
                state.0 += 1;
                true
            } else {
                false
            }
        };
        if changed {
            bridge::notify_ui();
        }
        JNI_TRUE
    }
    #[cfg(not(feature = "preview"))]
    {
        send_player_command(command)
    }
}

#[cfg(not(feature = "preview"))]
fn send_player_command(command: player::Command) -> jboolean {
    let try_one = |cmd: player::Command| -> Result<(), String> {
        let (method, request) = player::command(cmd);
        bridge::call(player::SERVICE, method, &request).and_then(|b| player::check_response(&b))
    };
    match try_one(command.clone()) {
        Ok(()) => JNI_TRUE,
        Err(e) => {
            for fallback in player::command_fallbacks(&command) {
                if try_one(fallback).is_ok() {
                    return JNI_TRUE;
                }
            }
            log::warn!("ContextPlayer command failed: {e}");
            JNI_FALSE
        }
    }
}


/// An album or artist page: [error], or "", name, subtitle, image, track count, release count, then twelve
/// strings per track (playlist::Track::fields) and four per release (name, subtitle, uri, image).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePage(mut env: JNIEnv, _: JClass, uri: JString) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    #[cfg(feature = "preview")]
    let page: Result<crate::spotify::metadata::Page, String> = Ok(crate::demo::page(&uri));
    #[cfg(not(feature = "preview"))]
    let page = crate::spotify::metadata::page(&uri);
    let fields = match page {
        Ok(p) => {
            let mut f = vec!["".to_string(), p.name, p.subtitle, p.image, p.tracks.len().to_string(), p.releases.len().to_string()];
            f.extend(p.tracks.into_iter().flat_map(playlist::Track::fields));
            f.extend(p.releases.into_iter().flat_map(|(name, sub, uri, image)| [name, sub, uri, image]));
            f
        }
        Err(error) => vec![if error.is_empty() { "Couldn’t load this page".into() } else { error }],
    };
    packed_strings(&env, &fields)
}

#[cfg(feature = "preview")]
static PREVIEW_SETTINGS: Mutex<Vec<i32>> = Mutex::new(Vec::new());

/// Spotify's own settings as key, value pairs of strings (bools are "0" or "1"), or ["!", reason] if they
/// couldn't be read. Blocks for up to a few seconds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSpotifySettings(mut env: JNIEnv, _: JClass) -> jobjectArray {
    use crate::spotify::settings;
    #[cfg(feature = "preview")]
    let read: Result<Vec<i32>, String> = {
        let mut stored = PREVIEW_SETTINGS.lock().unwrap();
        if stored.is_empty() {
            *stored = vec![1, 0, 3, 1, 0, 1, 1, 1, 1, 5, 0, 3, 0];
        }
        Ok(stored.clone())
    };
    #[cfg(not(feature = "preview"))]
    let read = settings::read(std::time::Duration::from_secs(6));
    let fields = match read {
        Ok(values) => settings::SETTINGS.iter().zip(values).flat_map(|(s, v)| [s.key.to_string(), v.to_string()]).collect(),
        Err(error) => vec!["!".to_string(), error],
    };
    strings(&mut env, fields)
}

/// Changes one of Spotify's settings; "" on success, else why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSetSpotifySetting(mut env: JNIEnv, _: JClass, key: JString, value: jint) -> jstring {
    use crate::spotify::settings;
    let Some(key) = read(&mut env, &key) else { return string(&mut env, "No setting") };
    #[cfg(feature = "preview")]
    let result: Result<(), String> = match settings::SETTINGS.iter().position(|s| s.key == key) {
        Some(i) => {
            if let Some(slot) = PREVIEW_SETTINGS.lock().unwrap().get_mut(i) {
                *slot = value;
            }
            Ok(())
        }
        None => Err("Unknown setting".into()),
    };
    #[cfg(not(feature = "preview"))]
    let result = settings::write(&key, value);
    string(&mut env, &result.err().unwrap_or_default())
}

/// Devices that can play your music, as six strings each: name, kind, id, "true" if it is playing,
/// "true" if it is this phone. Blocks for up to a few seconds; empty if Spotify has none or didn't answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDevices(mut env: JNIEnv, _: JClass) -> jobjectArray {
    #[cfg(feature = "preview")]
    let found: Result<Vec<crate::spotify::connect::Device>, String> = Ok(crate::demo::devices());
    #[cfg(not(feature = "preview"))]
    let found = crate::spotify::connect::devices(std::time::Duration::from_secs(4));
    let fields = match found {
        Ok(devices) => devices.into_iter().flat_map(|d| [d.name, d.kind, d.id, d.active.to_string(), d.this_device.to_string()]).collect(),
        Err(error) => {
            log::warn!("Devices: {error}");
            Vec::new()
        }
    };
    strings(&mut env, fields)
}

#[cfg(feature = "preview")]
static PREVIEW_VOLUME: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(40_000);

/// The volume of the device that's playing, 0 to 65535, or -1 if Spotify couldn't say. Blocks briefly.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeVolume(_: JNIEnv, _: JClass) -> jint {
    #[cfg(feature = "preview")]
    return PREVIEW_VOLUME.load(Ordering::Relaxed);
    #[cfg(not(feature = "preview"))]
    crate::spotify::volume::get(std::time::Duration::from_secs(3)).unwrap_or_else(|error| {
        log::warn!("Volume: {error}");
        -1
    })
}

/// Sets the volume of the device that's playing (0 to 65535); "" on success, else why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSetVolume(mut env: JNIEnv, _: JClass, volume: jint) -> jstring {
    #[cfg(feature = "preview")]
    let result: Result<(), String> = {
        PREVIEW_VOLUME.store(volume.clamp(0, crate::spotify::volume::MAX), Ordering::Relaxed);
        Ok(())
    };
    #[cfg(not(feature = "preview"))]
    let result = crate::spotify::volume::set(volume);
    string(&mut env, &result.err().unwrap_or_default())
}

/// The playback speed (1.0 normal) of what's playing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaybackSpeed(_: JNIEnv, _: JClass) -> jni::sys::jfloat {
    PLAYER.lock().unwrap().1.as_ref().map_or(1.0, |now| if now.speed > 0.0 { now.speed } else { 1.0 })
}

/// Moves playback to a device; "" on success, else why not.
#[unsafe(no_mangle)]
#[allow(unused_variables)]
pub extern "system" fn Java_spatch_SpatchUi_nativeTransfer(mut env: JNIEnv, _: JClass, id: JString) -> jstring {
    let Some(id) = read(&mut env, &id).filter(|id| !id.is_empty()) else { return string(&mut env, "No device chosen") };
    #[cfg(feature = "preview")]
    let result: Result<(), String> = Ok(());
    #[cfg(not(feature = "preview"))]
    let result = crate::spotify::connect::transfer(&id);
    string(&mut env, &result.err().unwrap_or_default())
}

/// Sends a player command; true if Spotify accepted it.
/// 0 pause, 1 resume, 2 previous, 3 next, 4 seek to `value` ms, 5 shuffle on/off, 6 repeat mode,
/// 7 play the context `uri`, 8 skip to queue entry `value` (which must still hold `uri`), 9 add `uri` to the queue,
/// 10 to 12 edit the queue, 13 put `uri` at the front of Up Next, 14 playback speed `value` / 100 (25 to 400).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCommand(mut env: JNIEnv, _: JClass, action: jint, value: jlong, uri: JString) -> jboolean {
    use player::Command;
    let target = read(&mut env, &uri).unwrap_or_default();
    let spotify_uri = target.starts_with("spotify:");
    let command = match action {
        0 => Command::Pause,
        1 => Command::Resume,
        2 => Command::Previous(value != 0),
        3 => Command::Next,
        4 if value >= 0 => Command::SeekTo(value),
        5 => Command::Shuffle(value != 0),
        6 if (0..=2).contains(&value) => Command::Repeat(value as u8),
        7 if spotify_uri => Command::Play {
            uri: target,
            shuffle: match value { 1 => Some(true), 2 => Some(false), _ => None },
        },
        8 | 10..=12 | 15 => {
            let key = target;
            // A drag (15) packs where the row ends up in the high 32 bits and where it started in the low ones.
            let (value, to) = if action == 15 { (value & 0xffff_ffff, (value >> 32) as usize) } else { (value, 0) };
            let Ok(index) = usize::try_from(value) else { return JNI_FALSE };
            let mut state = PLAYER.lock().unwrap();
            let Some(now) = state.1.as_mut() else { return JNI_FALSE };
            let queue = QUEUE_STATE.lock().unwrap();
            let full_queue = queue.as_ref().filter(|q| current_queue(now, q));
            let next = match full_queue {
                Some(q) => player::next_tracks(&q.next_tracks).nth(index),
                None => now.up_next.get(index).cloned(),
            };
            let Some(next) = next.filter(|t| queue_key(t, now.queue_revision) == key) else { return JNI_FALSE };
            if action == 8 {
                Command::SkipTo { uri: next.uri, uid: next.uid }
            } else {
                #[cfg(feature = "preview")]
                {
                    let done = if action == 15 { player::move_in_queue(&mut now.up_next, index, to, |_| true) } else { player::edit_queue(&mut now.up_next, index, action, |_| true) };
                    if !done {
                        return JNI_FALSE;
                    }
                    now.queue_revision += 1;
                    state.0 += 1;
                    drop(queue);
                    drop(state);
                    bridge::notify_ui();
                    return JNI_TRUE;
                }
                #[cfg(not(feature = "preview"))]
                {
                    let Some(request) = full_queue.and_then(|q| player::queue_edit_request(q, index, action, to)) else {
                        return JNI_FALSE;
                    };
                    let request = request.encode_to_vec();
                    drop(queue);
                    drop(state);
                    return match bridge::call(player::SERVICE, "SetQueue", &request).and_then(|bytes| player::check_response(&bytes)) {
                        Ok(()) => JNI_TRUE,
                        Err(error) => { log::warn!("SetQueue failed: {error}"); JNI_FALSE }
                    };
                }
            }
        }
        9 if spotify_uri => Command::AddToQueue(target),
        13 if spotify_uri => Command::PlayNext(target),
        14 if (25..=400).contains(&value) => Command::Speed(value as f32 / 100.0),
        _ => return JNI_FALSE,
    };
    #[cfg(feature = "preview")]
    {
        let changed = {
            let mut state = PLAYER.lock().unwrap();
            if let Some(now) = &mut state.1 {
                crate::demo::apply(now, command);
                state.0 += 1;
                true
            } else {
                false
            }
        };
        if changed {
            bridge::notify_ui();
            return JNI_TRUE;
        }
    }
    #[cfg(not(feature = "preview"))]
    return send_player_command(command);
    #[cfg(feature = "preview")]
    JNI_FALSE
}

/// 1 saved, 0 not saved, -1 unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSaved(mut env: JNIEnv, _: JClass, uri: JString) -> jint {
    let Some(uri) = read(&mut env, &uri).filter(|u| u.starts_with("spotify:")) else { return -1 };
    #[cfg(feature = "preview")]
    return SAVED.lock().unwrap().contains(&uri) as jint;
    // The playing song's state already says whether it's liked; only other songs need a lookup.
    #[cfg(not(feature = "preview"))]
    if let Some(liked) = PLAYER.lock().unwrap().1.as_ref().filter(|now| now.uri == uri).and_then(|now| now.liked) {
        return liked as jint;
    }
    #[cfg(not(feature = "preview"))]
    match bridge::call(collection::SERVICE, "Contains", &collection::contains_request(&uri)).and_then(|b| collection::decode_contains(&b)) {
        Ok(saved) => saved as jint,
        Err(_) => -1,
    }
}

/// Saves or unsaves a track in Liked Songs; true if Spotify accepted it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSave(mut env: JNIEnv, _: JClass, uri: JString, save: jboolean) -> jboolean {
    let Some(uri) = read(&mut env, &uri).filter(|u| u.starts_with("spotify:")) else { return JNI_FALSE };
    #[cfg(feature = "preview")]
    {
        let mut saved = SAVED.lock().unwrap();
        saved.retain(|u| u != &uri);
        if save != JNI_FALSE {
            saved.push(uri);
        }
        JNI_TRUE
    }
    #[cfg(not(feature = "preview"))]
    {
        let want = save != JNI_FALSE;
        let method = if want { "Add" } else { "Remove" };
        match bridge::call(collection::SERVICE, method, &collection::change_request(&uri)).and_then(|b| collection::decode_change(&b)) {
            Ok(()) => {
                if let Some(now) = PLAYER.lock().unwrap().1.as_mut().filter(|now| now.uri == uri) {
                    now.liked = Some(want);
                }
                JNI_TRUE
            }
            Err(e) => {
                log::warn!("Collection {method} failed for {uri}: {e}");
                JNI_FALSE
            }
        }
    }
}

fn shader_source(name: &str) -> Option<&'static str> {
    match name {
        "field" => Some(include_str!("../shaders/field.agsl")),
        "ring" => Some(include_str!("../shaders/ring.agsl")),
        "grain" => Some(include_str!("../shaders/grain.agsl")),
        "glow" => Some(include_str!("../shaders/glow.agsl")),
        _ => None,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_GpuFx_nativeShader(mut env: JNIEnv, _: JClass, name: JString) -> jstring {
    let Ok(name) = env.get_string(&name).map(String::from) else { return std::ptr::null_mut() };
    match shader_source(&name).map(|text| env.new_string(text)) {
        Some(Ok(text)) => text.into_raw(),
        _ => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod shader_tests {
    #[test]
    fn every_shader_has_an_entry_point() {
        for name in ["field", "ring", "grain", "glow"] {
            assert!(super::shader_source(name).unwrap().contains("half4 main(float2 p)"), "{name}");
        }
        assert!(super::shader_source("missing").is_none());
    }
}
