//! What SpatchUi.java calls. Every function that touches Spotify blocks, so Java runs them on its
//! own worker threads; the cheap readers (`nativePlayer`, `nativeEntries`) are safe on the UI thread.

use crate::bridge;
use crate::spotify::{library, player, playlist};
#[cfg(not(feature = "preview"))]
use crate::spotify::{collection, profile};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use crate::protos::com::spotify::player::esperanto::proto as es;
#[cfg(not(feature = "preview"))]
use prost::Message;
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobjectArray, jstring};
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
    queue.queue_revision == now.queue_revision &&
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
        let mut state = PLAYER.lock().unwrap();
        let mut queue = QUEUE_STATE.lock().unwrap();
        match reply {
            Ok(bytes) => {
                if let Ok(next) = es::Queue::decode(bytes) {
                    *queue = Some(next);
                    state.0 += 1;
                }
            }
            Err(_) => {
                *queue = None;
                state.0 += 1;
                QUEUE_CONNECTED.store(false, Ordering::Release);
            }
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
        *USERNAME.lock().unwrap() = Some(name.to_string());
    }
}
#[cfg(feature = "preview")]
static SAVED: Mutex<Vec<String>> = Mutex::new(Vec::new());

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
                    let mut state = PLAYER.lock().unwrap();
                    state.0 += 1;
                    state.1 = Some(now);
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

/// The player as strings, or null if nothing changed since `revision`:
/// revision, uri, title, artist, album, image, playing, shuffle, repeat, position, duration, source,
/// "Explicit" or "", context uri, the quality label or "", buffering, can skip next, can skip previous, album uri, artist uri; then four strings (title, artist, image, uri) per queued track.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlayer(mut env: JNIEnv, _: JClass, revision: jlong) -> jobjectArray {
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
            queue_key(next, now.queue_revision), (editable && next.queued).to_string()]);
    }
    strings(&mut env, fields)
}

fn fetch_library() -> Result<Vec<library::Item>, String> {
    let bytes = bridge::call(library::SERVICE, "All", &library::request(300))?;
    let items = library::decode(&bytes)?;
    crate::cache::put("library", &bytes);
    Ok(items)
}

static LIBRARY_REVISION: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
/// Subscribed to the core's StreamAll; `LIBRARY_LIVE` once it has delivered, so plain refreshes can stop.
#[cfg(not(feature = "preview"))]
static LIBRARY_WATCHED: AtomicBool = AtomicBool::new(false);
static LIBRARY_LIVE: AtomicBool = AtomicBool::new(false);

/// Replaces the library if it changed; true if it did.
fn set_library(items: Vec<library::Item>) -> bool {
    let mut current = LIBRARY.lock().unwrap();
    if current.as_ref() == Some(&items) {
        return false;
    }
    *current = Some(items);
    LIBRARY_REVISION.fetch_add(1, Ordering::Relaxed);
    true
}

/// Keeps the library current the way the core's own screens do: YourLibraryService.StreamAll sends the whole
/// library now and again whenever it changes (Spotify pushes the change to the core), so nothing has to ask.
#[cfg(not(feature = "preview"))]
fn watch_library() {
    if LIBRARY_WATCHED.swap(true, Ordering::Relaxed) {
        return;
    }
    let opened = bridge::subscribe(library::SERVICE, "StreamAll", &library::request(300), |reply| match reply {
        Ok(bytes) => {
            if let Ok(items) = library::decode(bytes) {
                crate::cache::put("library", bytes);
                LIBRARY_LIVE.store(true, Ordering::Relaxed);
                set_library(items);
            }
        }
        Err(error) => {
            log::warn!("Library stream ended: {error}");
            LIBRARY_LIVE.store(false, Ordering::Relaxed);
            LIBRARY_WATCHED.store(false, Ordering::Relaxed);
        }
    });
    if let Err(error) = opened {
        log::warn!("Library stream: {error}");
        LIBRARY_WATCHED.store(false, Ordering::Relaxed);
    }
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
pub extern "system" fn Java_spatch_SpatchUi_nativeCatalog(mut env: JNIEnv, _: JClass, bytes: JByteArray) -> jobjectArray {
    let Some(bytes) = env.convert_byte_array(bytes).ok() else { return std::ptr::null_mut() };
    #[cfg(feature = "preview")]
    let decoded = std::str::from_utf8(&bytes).ok().map(crate::demo::catalog);
    #[cfg(not(feature = "preview"))]
    let decoded = crate::spotify::catalog::decode(&bytes).ok();
    match decoded {
        Some(fields) => strings(&mut env, fields),
        None => std::ptr::null_mut(),
    }
}

/// Results for a search, six strings each (section, title, subtitle, uri, image, category): your library first,
/// then what the core's offline index knows. Blocks for the core's answer; empty for an empty query.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSearch(mut env: JNIEnv, _: JClass, query: JString) -> jobjectArray {
    use crate::spotify::search;
    let query = read(&mut env, &query).unwrap_or_default();
    if query.trim().is_empty() {
        return strings(&mut env, Vec::new());
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
    strings(&mut env, hits.into_iter().flat_map(search::Hit::fields).collect())
}

/// Library items that can be downloaded (playlists, albums, podcasts), for asking the core about each.
fn downloadable_uris() -> Vec<String> {
    LIBRARY
        .lock()
        .unwrap()
        .iter()
        .flatten()
        .filter(|i| i.kind != library::Kind::Artist && i.kind != library::Kind::Other && !i.uri.is_empty())
        .map(|i| i.uri.clone())
        .collect()
}

/// Starts following the core's downloads and loads what's there. Blocks for the first answer; call it off the
/// main thread once the library is known.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsStart(_: JNIEnv, _: JClass) {
    use crate::spotify::downloads;
    downloads::watch();
    if let Err(error) = downloads::refresh(&downloadable_uris()) {
        log::warn!("Downloads: {error}");
    }
}

/// Everything downloaded or on its way: uri, state (1 error, 2 requested, 3 downloaded, 4 downloading,
/// 5 waiting) and percent (0 to 100), as three strings each.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloads(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let fields = crate::spotify::downloads::snapshot()
        .into_iter()
        .flat_map(|d| [d.uri, d.state.code().to_string(), ((d.percent * 100.0).round() as i32).to_string()])
        .collect();
    strings(&mut env, fields)
}

/// Bumps on any change to downloads, progress included.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsRevision(_: JNIEnv, _: JClass) -> jlong {
    crate::spotify::downloads::revision() as jlong
}

/// Bumps when a download is added, removed or changes state (not for progress alone).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsSet(_: JNIEnv, _: JClass) -> jlong {
    crate::spotify::downloads::set_revision() as jlong
}

/// Adds (0), removes (1) a download of `uri`, or removes every download (2); "" on success, else why not.
/// Blocks for the core's answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownload(mut env: JNIEnv, _: JClass, uri: JString, action: jint) -> jstring {
    use crate::spotify::downloads;
    let uri = read(&mut env, &uri).unwrap_or_default();
    let result = match action {
        0 if uri.starts_with("spotify:") => downloads::add(&uri),
        1 if uri.starts_with("spotify:") => downloads::remove(&uri),
        2 => downloads::remove_all(),
        _ => Err("Nothing to download".into()),
    };
    string(&mut env, &result.err().unwrap_or_default())
}

/// The whole queue: downloaded songs, total songs, downloaded bytes, total bytes, downloading ("true" or "false"),
/// percent (0 to 100), seconds left, bytes per second, queued songs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadTotals(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let t = crate::spotify::downloads::total();
    strings(
        &mut env,
        vec![
            t.downloaded_tracks.to_string(),
            t.total_tracks.to_string(),
            t.downloaded_bytes.to_string(),
            t.total_bytes.to_string(),
            t.downloading.to_string(),
            ((t.percent * 100.0).round() as i32).to_string(),
            t.seconds_left.to_string(),
            t.speed.to_string(),
            t.queued_tracks.to_string(),
        ],
    )
}

/// The cache: everything in bytes, the part kept for downloads, free space on the phone, the phone's size; or
/// ["!", reason]. Blocks for the core.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeStorage(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let fields = match crate::spotify::downloads::storage() {
        Ok(s) => vec![s.current.to_string(), s.locked.to_string(), s.free.to_string(), s.total.to_string()],
        Err(error) => vec!["!".into(), error],
    };
    strings(&mut env, fields)
}

/// The commit this build came from.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeBuild(mut env: JNIEnv, _: JClass) -> jstring {
    string(&mut env, env!("SPATCH_COMMIT"))
}

/// Bumps whenever the library changes; the UI redraws when it moves.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibraryRevision(_: JNIEnv, _: JClass) -> jlong {
    LIBRARY_REVISION.load(Ordering::Relaxed)
}

/// Loads the library once; "" on success, "cached" if it came from the last launch's copy (refresh it soon),
/// else Spotify's error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibrary(mut env: JNIEnv, _: JClass) -> jstring {
    #[cfg(not(feature = "preview"))]
    watch_library();
    if LIBRARY.lock().unwrap().is_some() {
        return string(&mut env, "");
    }
    if !cfg!(feature = "preview") {
        if let Some(items) = crate::cache::get("library").and_then(|bytes| library::decode(&bytes).ok()) {
            *LIBRARY.lock().unwrap() = Some(items);
            return string(&mut env, "cached");
        }
    }
    let status = match fetch_library() {
        Ok(items) => {
            *LIBRARY.lock().unwrap() = Some(items);
            String::new()
        }
        Err(error) => error,
    };
    string(&mut env, &status)
}

/// Fetches the library again; true if it differs from what the UI had.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRefreshLibrary(_: JNIEnv, _: JClass) -> jboolean {
    if cfg!(feature = "preview") {
        return JNI_FALSE;
    }
    #[cfg(not(feature = "preview"))]
    watch_library();
    // The stream keeps it current; only ask when it isn't delivering.
    if LIBRARY_LIVE.load(Ordering::Relaxed) {
        return JNI_FALSE;
    }
    let Ok(items) = fetch_library() else { return JNI_FALSE };
    set_library(items) as jboolean
}

fn item_fields<'a>(items: impl IntoIterator<Item = &'a library::Item>) -> Vec<String> {
    items
        .into_iter()
        .flat_map(|i| [i.name.clone(), i.subtitle.clone(), i.uri.clone(), i.image.clone(), i.kind.chip().unwrap_or(-1).to_string()])
        .collect()
}

/// Library items under a chip (-1 for all, else playlists 0, albums 1, artists 2, podcasts 3) matching
/// `query`, optionally alphabetical, up to `limit`, as five strings each: name, subtitle, uri, image, chip (or -1).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeEntries(
    mut env: JNIEnv,
    _: JClass,
    filter: jint,
    query: JString,
    alphabetical: jboolean,
    limit: jint,
) -> jobjectArray {
    let query = read(&mut env, &query).unwrap_or_default();
    let fields = {
        let library = LIBRARY.lock().unwrap();
        item_fields(library::select(library.as_deref().unwrap_or(&[]), filter, &query, alphabetical != JNI_FALSE, limit.max(0) as usize))
    };
    strings(&mut env, fields)
}

/// What you played lately, from Spotify's own history, as five strings each (like `nativeEntries`).
/// Blocks for up to a few seconds; empty if Spotify has nothing or didn't answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRecent(mut env: JNIEnv, _: JClass) -> jobjectArray {
    #[cfg(feature = "preview")]
    let recent: Result<Vec<library::Item>, String> = Ok(crate::demo::recent());
    #[cfg(not(feature = "preview"))]
    let recent = crate::spotify::recent::fetch(12, std::time::Duration::from_secs(4));
    let fields = match recent {
        Ok(items) => item_fields(&items),
        Err(error) => {
            log::warn!("Recently played: {error}");
            Vec::new()
        }
    };
    strings(&mut env, fields)
}

/// Learns your username from the first of your own playlists that Spotify says you own.
#[cfg(not(feature = "preview"))]
fn find_username() -> Option<String> {
    let candidates: Vec<String> = LIBRARY
        .lock()
        .unwrap()
        .iter()
        .flatten()
        .filter(|i| i.kind == library::Kind::Playlist && !library::is_liked(&i.uri) && i.uri.starts_with("spotify:playlist:"))
        .take(8)
        .map(|i| i.uri.clone())
        .collect();
    if candidates.is_empty() {
        *LIKED_NOTE.lock().unwrap() = "No playlists with a spotify:playlist: link to ask about".into();
        return None;
    }
    let mut note = format!("Looked at {} of your playlists:", candidates.len());
    let found = candidates.iter().find_map(|uri| {
        match bridge::call(playlist::SERVICE, "Get", &playlist::request_range(uri, 0, 1)).and_then(|bytes| playlist::decode(&bytes, 0)) {
            Ok(playlist::Reply::Page { owner: Some(name), .. }) => Some(name),
            Ok(playlist::Reply::Page { owner: None, .. }) => {
                note.push_str(" not yours;");
                None
            }
            Ok(playlist::Reply::Loading) => {
                note.push_str(" still loading;");
                None
            }
            Err(e) => {
                note.push_str(&format!(" error ({e});"));
                None
            }
        }
    });
    log::warn!("Username lookup: {note}");
    *LIKED_NOTE.lock().unwrap() = note;
    found
}

/// What the username lookup found out, for the Liked Songs page when it can't load.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLikedNote(mut env: JNIEnv, _: JClass) -> jstring {
    let note = LIKED_NOTE.lock().unwrap().clone();
    string(&mut env, &note)
}

/// The link the playlist service accepts for Liked Songs: the library's own if it lists one, else
/// `spotify:user:<username>:collection`. Blocks (it may need a playlist request): never on the UI thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLiked(mut env: JNIEnv, _: JClass) -> jstring {
    let listed = LIBRARY.lock().unwrap().iter().flatten().find(|i| library::is_liked(&i.uri)).map(|i| i.uri.clone());
    #[cfg(not(feature = "preview"))]
    let user = {
        if USERNAME.lock().unwrap().is_none() && listed.is_none() {
            let found = find_username();
            *USERNAME.lock().unwrap() = found;
        }
        USERNAME.lock().unwrap().clone()
    };
    #[cfg(feature = "preview")]
    let user: Option<String> = None;
    let working = LIKED_WORKING.lock().unwrap().clone();
    let uri = working.or(listed).or_else(|| user.map(|u| format!("spotify:user:{u}:collection"))).unwrap_or_else(|| "spotify:collection:tracks".into());
    string(&mut env, &uri)
}

/// A page of a playlist: ["loading"], [error], or ["", next offset or "0", then title, artist, uri, image per track].
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylist(mut env: JNIEnv, _: JClass, uri: JString, #[allow(unused_variables)] offset: jint) -> jobjectArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    if !cfg!(feature = "preview") && !library::is_playlist(&uri) {
        return std::ptr::null_mut();
    }
    #[cfg(feature = "preview")]
    let reply: Result<playlist::Reply, String> = Ok(playlist::Reply::Page {
        tracks: PREVIEW_PLAYLISTS
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|map| map.get(&uri).cloned())
            .unwrap_or_else(crate::demo::playlist),
        next: None,
        owner: None,
    });
    #[cfg(not(feature = "preview"))]
    let reply = if library::is_liked(&uri) {
        // Liked Songs has several spellings and only one is accepted: try them in turn and keep what works.
        let mut attempts = Vec::new();
        let mut won = None;
        for candidate in liked_candidates(&uri) {
            match bridge::call(playlist::SERVICE, "Get", &playlist::request(&candidate, offset)).and_then(|bytes| playlist::decode(&bytes, offset)) {
                Ok(reply) => {
                    won = Some((candidate, reply));
                    break;
                }
                Err(e) => attempts.push(format!("{candidate} -> {e}")),
            }
        }
        match won {
            Some((candidate, reply)) => {
                *LIKED_WORKING.lock().unwrap() = Some(candidate);
                Ok(reply)
            }
            None => {
                let mut note = LIKED_NOTE.lock().unwrap();
                // Keep what the username lookup said, and put each link's answer under it.
                let lookup = note.lines().find(|l| l.starts_with("Looked at") || l.starts_with("No playlists")).unwrap_or("Username unknown").to_string();
                *note = format!("{lookup}\n{}", attempts.join("\n"));
                Err(attempts.first().cloned().unwrap_or_else(|| "No Liked Songs link worked".into()))
            }
        }
    } else {
        bridge::call(playlist::SERVICE, "Get", &playlist::request(&uri, offset)).and_then(|bytes| playlist::decode(&bytes, offset))
    };
    let fields = match reply {
        Ok(playlist::Reply::Loading) => vec!["loading".into()],
        Ok(page) => page_fields(page),
        Err(error) => vec![if error.is_empty() { "Couldn’t load playlist".into() } else { error }],
    };
    strings(&mut env, fields)
}

/// A playlist page as the UI reads it: "", the next offset, then title, artist, uri and image per track.
fn page_fields(page: playlist::Reply) -> Vec<String> {
    let playlist::Reply::Page { tracks, next, owner } = page else { return vec!["loading".into()] };
    if let Some(name) = owner {
        *USERNAME.lock().unwrap() = Some(name);
    }
    ["".to_string(), next.unwrap_or(0).to_string()].into_iter().chain(tracks.into_iter().flat_map(playlist::Track::fields)).collect()
}

/// The rest of a playlist after its first page: every page from `offset` on, as song fields only. Blocks; stops
/// at an error (returning what it has) or 10,000 songs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylistRest(mut env: JNIEnv, _: JClass, uri: JString, offset: jint) -> jobjectArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let mut fields = Vec::new();
    let mut next = Some(offset);
    let mut waits = 0;
    while let Some(at) = next.filter(|&at| at > 0 && at < 10_000) {
        if cfg!(feature = "preview") {
            break;
        }
        match bridge::call(playlist::SERVICE, "Get", &playlist::request(&uri, at)).and_then(|b| playlist::decode(&b, at)) {
            Ok(playlist::Reply::Page { tracks, next: more, .. }) => {
                fields.extend(tracks.into_iter().flat_map(playlist::Track::fields));
                next = more;
            }
            // Spotify is still fetching this page: wait, but not forever.
            Ok(playlist::Reply::Loading) if waits < 20 => {
                waits += 1;
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
            Ok(playlist::Reply::Loading) => {
                log::warn!("Playlist page at {at} still loading after 10 s");
                break;
            }
            Err(error) => {
                log::warn!("Playlist page at {at}: {error}");
                break;
            }
        }
    }
    strings(&mut env, fields)
}

/// The open playlist's songs (playlist::FIELDS strings each), kept here so a search doesn't resend them.
static SONGS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Keeps the open playlist's song fields for nativeArrange.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSongs(mut env: JNIEnv, _: JClass, fields: JObjectArray) {
    let Ok(len) = env.get_array_length(&fields) else { return };
    let mut values = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(item) = env.get_object_array_element(&fields, i) else { return };
        let item = JString::from(item);
        values.push(read(&mut env, &item).unwrap_or_default());
        let _ = env.delete_local_ref(item);
    }
    *SONGS.lock().unwrap() = values;
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
        let mut state = PLAYER.lock().unwrap();
        if let Some(now) = &mut state.1 {
            crate::demo::apply(now, command);
            state.0 += 1;
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
    let (method, request) = player::command(command);
    match bridge::call(player::SERVICE, method, &request).and_then(|b| player::check_response(&b)) {
        Ok(()) => JNI_TRUE,
        Err(e) => {
            log::warn!("ContextPlayer {method} failed: {e}");
            JNI_FALSE
        }
    }
}

/// The open playlist's saved-answer key and its subscription; bumps `PLAYLIST_REVISION` on each change.
static WATCHED_PLAYLIST: Mutex<Option<(String, i64)>> = Mutex::new(None);
static PLAYLIST_REVISION: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

fn stop_watching_playlist() {
    if let Some((_, id)) = WATCHED_PLAYLIST.lock().unwrap().take() {
        if id != 0 {
            bridge::cancel(id);
        }
    }
}

/// Keeps the open playlist live: PlaylistDataService.Subscribe (Spotify's own playlist screen uses it) sends
/// the first page again whenever the playlist changes. Each change replaces the saved answer under `key`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeWatchPlaylist(mut env: JNIEnv, _: JClass, uri: JString, key: JString) {
    let (Some(uri), Some(key)) = (read(&mut env, &uri), read(&mut env, &key)) else { return };
    stop_watching_playlist();
    if cfg!(feature = "preview") || !library::is_playlist(&uri) {
        return;
    }
    // The key goes in first, so a push that lands before the id is known still counts.
    *WATCHED_PLAYLIST.lock().unwrap() = Some((key.clone(), 0));
    let watched = key.clone();
    let opened = bridge::subscribe(playlist::SERVICE, "Subscribe", &playlist::request(&uri, 0), move |reply| {
        let Ok(bytes) = reply else {
            log::warn!("Playlist stream ended: {}", reply.err().unwrap_or_default());
            return;
        };
        let Ok(page @ playlist::Reply::Page { .. }) = playlist::decode(bytes, 0) else { return };
        if WATCHED_PLAYLIST.lock().unwrap().as_ref().is_none_or(|(k, _)| k != &watched) {
            return;
        }
        let encoded = crate::cache::encode(&page_fields(page));
        if crate::cache::get(&watched).as_deref() != Some(encoded.as_slice()) {
            crate::cache::put(&watched, &encoded);
            PLAYLIST_REVISION.fetch_add(1, Ordering::Relaxed);
        }
    });
    match opened {
        Ok(id) => match WATCHED_PLAYLIST.lock().unwrap().as_mut() {
            Some((k, slot)) if *k == key => *slot = id,
            _ => bridge::cancel(id), // another playlist opened meanwhile
        },
        Err(error) => log::warn!("Playlist stream: {error}"),
    }
}

/// Stops following the open playlist (the page closed).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeStopPlaylist(_: JNIEnv, _: JClass) {
    stop_watching_playlist();
}

/// Bumps whenever the open playlist changes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylistRevision(_: JNIEnv, _: JClass) -> jlong {
    PLAYLIST_REVISION.load(Ordering::Relaxed)
}

/// An album or artist page: [error], or "", name, subtitle, image, track count, release count, then four
/// strings per track (title, artist, uri, image) and four per release (name, subtitle, uri, image).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePage(mut env: JNIEnv, _: JClass, uri: JString) -> jobjectArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    #[cfg(feature = "preview")]
    let page: Result<crate::spotify::metadata::Page, String> = Ok(crate::demo::page(&uri));
    #[cfg(not(feature = "preview"))]
    let page = crate::spotify::metadata::page(&uri);
    let fields = match page {
        Ok(p) => {
            let mut f = vec!["".to_string(), p.name, p.subtitle, p.image, p.tracks.len().to_string(), p.releases.len().to_string()];
            f.extend(p.tracks.into_iter().flat_map(|t| [t.title, t.artist, t.uri, t.image]));
            f.extend(p.releases.into_iter().flat_map(|(name, sub, uri, image)| [name, sub, uri, image]));
            f
        }
        Err(error) => vec![if error.is_empty() { "Couldn’t load this page".into() } else { error }],
    };
    strings(&mut env, fields)
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
            *stored = vec![1, 0, 3, 1, 0, 1, 1, 1, 1, 5, 0, 3];
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
/// 7 play the context `uri`, 8 skip to queue entry `value` (which must still hold `uri`), 9 add `uri` to the queue.
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
        8 | 10..=12 => {
            let key = target;
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
                    if !player::edit_queue(&mut now.up_next, index, action, |t| t.queued) {
                        return JNI_FALSE;
                    }
                    now.queue_revision += 1;
                    state.0 += 1;
                    return JNI_TRUE;
                }
                #[cfg(not(feature = "preview"))]
                {
                    let Some(request) = full_queue.and_then(|q| player::queue_edit_request(q, index, action)) else {
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
        _ => return JNI_FALSE,
    };
    #[cfg(feature = "preview")]
    {
        let mut state = PLAYER.lock().unwrap();
        if let Some(now) = &mut state.1 {
            crate::demo::apply(now, command);
            state.0 += 1;
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

struct QueuedEntry {
    uri: String,
    #[cfg_attr(not(feature = "preview"), allow(dead_code))]
    title: String,
    #[cfg_attr(not(feature = "preview"), allow(dead_code))]
    artist: String,
    #[cfg_attr(not(feature = "preview"), allow(dead_code))]
    image: String,
}

fn collect_queue_entries(now: &player::NowPlaying, queue: Option<&es::Queue>) -> Vec<QueuedEntry> {
    let mut entries = Vec::new();
    if !now.uri.is_empty() && !now.uri.starts_with("spotify:delimiter") {
        entries.push(QueuedEntry {
            uri: now.uri.clone(),
            title: now.title.clone(),
            artist: now.artist.clone(),
            image: now.image.clone(),
        });
    }
    let upcoming: Vec<player::NextTrack> = queue
        .filter(|q| current_queue(now, q))
        .map(|q| player::next_tracks(&q.next_tracks).collect())
        .unwrap_or_else(|| now.up_next.clone());
    for next in upcoming {
        if !next.uri.is_empty() && !next.uri.starts_with("spotify:delimiter") {
            entries.push(QueuedEntry {
                uri: next.uri,
                title: next.title,
                artist: next.artist,
                image: next.image,
            });
        }
    }
    entries
}

fn current_queue_entries() -> Vec<QueuedEntry> {
    let state = PLAYER.lock().unwrap();
    let queue = QUEUE_STATE.lock().unwrap();
    #[cfg(feature = "preview")]
    let fallback = state.1.is_none().then(crate::demo::now_playing);
    #[cfg(not(feature = "preview"))]
    let fallback: Option<player::NowPlaying> = None;
    let Some(now) = state.1.as_ref().or(fallback.as_ref()) else {
        return Vec::new();
    };
    collect_queue_entries(now, queue.as_ref())
}

#[cfg(not(feature = "preview"))]
fn resolve_username() -> Option<String> {
    if let Some(name) = USERNAME.lock().unwrap().clone() {
        return Some(name);
    }
    if LIBRARY.lock().unwrap().is_none() {
        if let Ok(items) = fetch_library() {
            set_library(items);
        } else if let Some(items) = crate::cache::get("library").and_then(|bytes| library::decode(&bytes).ok()) {
            *LIBRARY.lock().unwrap() = Some(items);
        }
    }
    if let Some(uri) = LIBRARY
        .lock()
        .unwrap()
        .iter()
        .flatten()
        .find(|i| library::is_liked(&i.uri))
        .map(|i| i.uri.clone())
    {
        learn_username(&uri);
    }
    if let Some(name) = USERNAME.lock().unwrap().clone() {
        return Some(name);
    }
    let found = find_username();
    if let Some(ref name) = found {
        *USERNAME.lock().unwrap() = Some(name.clone());
    }
    found
}

fn profile_fields() -> Vec<String> {
    #[cfg(feature = "preview")]
    {
        let p = crate::demo::profile();
        vec![p.display_name, p.username, p.image_url, p.profile_uri, String::new()]
    }
    #[cfg(not(feature = "preview"))]
    {
        let Some(username) = resolve_username() else {
            return vec![
                "Account unavailable".into(),
                String::new(),
                String::new(),
                String::new(),
                "Account username is not available yet".into(),
            ];
        };
        match profile::fetch(&username, std::time::Duration::from_secs(4)) {
            Ok(p) => vec![p.display_name, p.username, p.image_url, p.profile_uri, String::new()],
            Err(e) => vec![
                username.clone(),
                username.clone(),
                String::new(),
                format!("spotify:user:{username}"),
                e,
            ],
        }
    }
}

#[cfg(feature = "preview")]
fn preview_create_playlist(name: &str, description: &str, from_queue: bool) -> Vec<String> {
    let (name, description) = match playlist::validate_create(name, description) {
        Ok(valid) => valid,
        Err(e) => return vec![String::new(), e],
    };
    let tracks: Vec<playlist::Track> = if from_queue {
        let entries = current_queue_entries();
        if entries.is_empty() {
            return vec![String::new(), "Nothing in the queue to save".into()];
        }
        entries
            .into_iter()
            .map(|e| crate::demo::track_from_entry(&e.uri, &e.title, &e.artist, &e.image))
            .collect()
    } else {
        Vec::new()
    };
    let seq = PREVIEW_PLAYLIST_SEQ.fetch_add(1, Ordering::Relaxed);
    let uri = format!("spotify:playlist:preview-{seq}");
    let image = tracks.first().map(|t| t.image.clone()).unwrap_or_default();
    PREVIEW_PLAYLISTS
        .lock()
        .unwrap()
        .get_or_insert_with(std::collections::HashMap::new)
        .insert(uri.clone(), tracks);
    {
        let mut library = LIBRARY.lock().unwrap();
        let items = library.get_or_insert_with(crate::demo::library);
        items.push(library::Item {
            name,
            kind: library::Kind::Playlist,
            subtitle: if description.is_empty() { "Playlist".into() } else { description },
            uri: uri.clone(),
            image,
        });
        LIBRARY_REVISION.fetch_add(1, Ordering::Relaxed);
    }
    vec![uri, String::new()]
}

#[cfg(not(feature = "preview"))]
fn real_create_playlist(name: &str, description: &str, from_queue: bool) -> Vec<String> {
    let (name, description) = match playlist::validate_create(name, description) {
        Ok(valid) => valid,
        Err(e) => return vec![String::new(), e],
    };
    let uris: Vec<String> = if from_queue {
        let list: Vec<String> = current_queue_entries().into_iter().map(|e| e.uri).collect();
        if list.is_empty() {
            return vec![String::new(), "Nothing in the queue to save".into()];
        }
        list
    } else {
        Vec::new()
    };
    match playlist::create(&name, &description, &uris) {
        Ok((uri, warning)) => {
            if let Ok(items) = fetch_library() {
                set_library(items);
            }
            vec![uri, warning.unwrap_or_default()]
        }
        Err(e) => vec![String::new(), e],
    }
}

fn read_strings(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    let Ok(len) = env.get_array_length(array) else { return Vec::new() };
    (0..len)
        .map(|i| {
            env.get_object_array_element(array, i).ok()
                .and_then(|obj| env.get_string(&JString::from(obj)).ok().map(String::from))
                .unwrap_or_default()
        })
        .collect()
}

#[cfg(feature = "preview")]
fn preview_add_to_playlist(playlist_uri: &str, uris: &[String]) -> String {
    if let Err(e) = playlist::validate_add_items(playlist_uri, uris) {
        return e;
    }
    let mut playlists = PREVIEW_PLAYLISTS.lock().unwrap();
    let Some(tracks) = playlists.as_mut().and_then(|map| map.get_mut(playlist_uri)) else {
        return "Preview mode only supports adding songs to playlists you created".into();
    };
    let added: Vec<playlist::Track> = uris
        .iter()
        .map(|u| crate::demo::track_from_entry(u, "", "", ""))
        .collect();
    let first_image = added.first().map(|t| t.image.clone()).unwrap_or_default();
    tracks.extend(added);
    if !first_image.is_empty() {
        let mut library = LIBRARY.lock().unwrap();
        if let Some(item) = library.as_mut().and_then(|items| items.iter_mut().find(|i| i.uri == playlist_uri)) {
            if item.image.is_empty() {
                item.image = first_image;
                LIBRARY_REVISION.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
    String::new()
}

#[cfg(not(feature = "preview"))]
fn real_add_to_playlist(playlist_uri: &str, uris: &[String]) -> String {
    match playlist::add_items(playlist_uri, uris) {
        Ok(()) => String::new(),
        Err(e) => e,
    }
}

/// The signed-in user's profile: `[display_name, username, image_url, profile_uri, error]`.
/// Blocks (it may need username discovery and a profile stream): run on a worker thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeProfile(mut env: JNIEnv, _: JClass) -> jobjectArray {
    strings(&mut env, profile_fields())
}

/// Creates a playlist (optionally from the current queue): `[created_playlist_uri, error]`.
/// Blocks: run on a worker thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCreatePlaylist(
    mut env: JNIEnv,
    _: JClass,
    name: JString,
    description: JString,
    from_queue: jboolean,
) -> jobjectArray {
    let name = read(&mut env, &name).unwrap_or_default();
    let description = read(&mut env, &description).unwrap_or_default();
    let from_queue = from_queue != JNI_FALSE;
    #[cfg(feature = "preview")]
    let result = preview_create_playlist(&name, &description, from_queue);
    #[cfg(not(feature = "preview"))]
    let result = real_create_playlist(&name, &description, from_queue);
    strings(&mut env, result)
}

/// Appends `uris` to `playlist_uri`; returns `""` on success or an error message.
/// Blocks: run on a worker thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeAddToPlaylist(
    mut env: JNIEnv,
    _: JClass,
    playlist_uri: JString,
    uris: JObjectArray,
) -> jstring {
    let playlist_uri = read(&mut env, &playlist_uri).unwrap_or_default();
    let uris = read_strings(&mut env, &uris);
    #[cfg(feature = "preview")]
    let error = preview_add_to_playlist(&playlist_uri, &uris);
    #[cfg(not(feature = "preview"))]
    let error = real_add_to_playlist(&playlist_uri, &uris);
    string(&mut env, &error)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collects_current_and_upcoming_tracks_in_order() {
        let now = player::NowPlaying {
            uri: "spotify:track:current".into(),
            title: "Current".into(),
            artist: "Artist".into(),
            image: "img0".into(),
            queue_revision: 7,
            up_next: vec![player::NextTrack {
                title: "Fallback".into(),
                artist: "Artist".into(),
                image: "img1".into(),
                uri: "spotify:track:fallback".into(),
                uid: "u1".into(),
                queued: false,
            }],
            ..Default::default()
        };
        let queue = es::Queue {
            queue_revision: 7,
            track: Some(es::ProvidedTrack {
                context_track: Some(es::ContextTrack { uri: "spotify:track:current".into(), ..Default::default() }),
                ..Default::default()
            }),
            next_tracks: vec![
                es::ProvidedTrack {
                    context_track: Some(es::ContextTrack { uri: "spotify:delimiter".into(), ..Default::default() }),
                    ..Default::default()
                },
                es::ProvidedTrack {
                    context_track: Some(es::ContextTrack { uri: "spotify:track:next1".into(), ..Default::default() }),
                    ..Default::default()
                },
                es::ProvidedTrack {
                    context_track: Some(es::ContextTrack { uri: "spotify:track:next2".into(), ..Default::default() }),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        let uris: Vec<String> = collect_queue_entries(&now, Some(&queue)).into_iter().map(|e| e.uri).collect();
        assert_eq!(uris, vec!["spotify:track:current", "spotify:track:next1", "spotify:track:next2"]);
    }

    #[cfg(feature = "preview")]
    #[test]
    fn preview_profile_and_playlist_creation_retains_tracks_and_updates_library() {
        let profile = profile_fields();
        assert_eq!(profile, vec!["Preview Listener", "preview", "demo:0", "spotify:user:preview", ""]);

        assert_eq!(preview_create_playlist("  ", "", false), vec!["", "Playlist name cannot be empty"]);

        let created = preview_create_playlist("Road Trip", "From demo queue", true);
        assert_eq!(created[1], "");
        let uri = created[0].clone();
        assert!(library::is_playlist(&uri));

        let lib = LIBRARY.lock().unwrap().clone().unwrap();
        assert!(lib.iter().any(|item| item.uri == uri && item.name == "Road Trip" && item.subtitle == "From demo queue"));

        let retained = PREVIEW_PLAYLISTS.lock().unwrap().as_ref().unwrap().get(&uri).cloned().unwrap();
        assert_eq!(retained.len(), 5);
        assert_eq!(retained[0].uri, "spotify:preview:0");
        assert_eq!(retained[1].uri, "spotify:preview:1");
    }

    #[cfg(feature = "preview")]
    #[test]
    fn preview_add_to_playlist_preserves_order_and_rejects_unsupported() {
        let created = preview_create_playlist("Blank Mix", "", false);
        assert_eq!(created[1], "");
        let uri = created[0].clone();

        assert_eq!(
            preview_add_to_playlist(&uri, &["spotify:preview:2".into(), "spotify:preview:0".into()]),
            ""
        );
        let retained = PREVIEW_PLAYLISTS.lock().unwrap().as_ref().unwrap().get(&uri).cloned().unwrap();
        assert_eq!(retained.len(), 2);
        assert_eq!(retained[0].uri, "spotify:preview:2");
        assert_eq!(retained[0].title, "Blue Hour");
        assert_eq!(retained[1].uri, "spotify:preview:0");
        assert_eq!(retained[1].title, "Glasshouse");

        assert!(!preview_add_to_playlist("spotify:playlist:other", &["spotify:preview:0".into()]).is_empty());
        assert!(!preview_add_to_playlist(&uri, &[]).is_empty());
        assert!(!preview_add_to_playlist(&uri, &["spotify:delimiter".into()]).is_empty());
    }
}
