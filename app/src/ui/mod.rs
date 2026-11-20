//! What SpatchUi.java calls. Every function that touches Spotify blocks, so Java runs them on its
//! own worker threads; the cheap readers (`nativePlayer`, `nativeEntries`) are safe on the UI thread.

mod account;
mod downloads;

use crate::bridge;
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
            *FOUND_ITEMS.lock().unwrap() = None;
            QUEUE_CONNECTED.store(false, Ordering::Release);
            LIBRARY_WATCHED.store(false, Ordering::Release);
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
            queue_key(next, now.queue_revision), (editable && next.queued).to_string()]);
    }
    packed_strings(&env, &fields)
}

fn fetch_library() -> Result<bool, String> {
    let epoch = bridge::session();
    let bytes = bridge::call(library::SERVICE, "All", &library::request(300))?;
    let items = library::decode(&bytes)?;
    bridge::in_session(epoch, || {
        if let Some(name) = library::username(&bytes) { remember_username(&name); }
        crate::cache::put("library", &bytes);
        set_library(items)
    }).ok_or_else(|| "Account session changed".into())
}

static LIBRARY_REVISION: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
/// Subscribed to the core's StreamAll; `LIBRARY_LIVE` once it has delivered, so plain refreshes can stop.
#[cfg(not(feature = "preview"))]
static LIBRARY_WATCHED: AtomicBool = AtomicBool::new(false);
static LIBRARY_LIVE: AtomicBool = AtomicBool::new(false);

/// Replaces the library if it changed; true if it did.
fn set_library(items: Vec<library::Item>) -> bool {
    {
        let mut current = LIBRARY.lock().unwrap();
        if current.as_ref() == Some(&items) {
            return false;
        }
        *current = Some(items);
        LIBRARY_REVISION.fetch_add(1, Ordering::SeqCst);
    }
    bridge::notify_ui();
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
                if let Some(name) = library::username(bytes) { remember_username(&name); }
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

/// Bumps whenever the library changes; the UI redraws when it moves.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibraryRevision(_: JNIEnv, _: JClass) -> jlong {
    LIBRARY_REVISION.load(Ordering::Relaxed)
}

/// Logs the account's product state once per session: the keys that decide song details and the login hand-off.
#[cfg(not(feature = "preview"))]
fn log_product_state() {
    static LOGGED: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(i64::MIN);
    let session = bridge::session();
    if LOGGED.swap(session, Ordering::SeqCst) == session {
        return;
    }
    match bridge::call(crate::spotify::product_state::SERVICE, "GetValues", &[]) {
        Ok(bytes) => log::info!("Product state: {}", crate::spotify::product_state::summary(&crate::spotify::product_state::decode(&bytes))),
        Err(e) => log::warn!("Product state: {e}"),
    }
}

/// Loads the current account's library; "" on success, otherwise Spotify's error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibrary(mut env: JNIEnv, _: JClass) -> jstring {
    #[cfg(not(feature = "preview"))]
    watch_library();
    #[cfg(not(feature = "preview"))]
    log_product_state();
    if LIBRARY.lock().unwrap().is_some() {
        return string(&mut env, "");
    }
    let status = match fetch_library() {
        Ok(_) => String::new(),
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
    fetch_library().unwrap_or(false) as jboolean
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
) -> jbyteArray {
    let query = read(&mut env, &query).unwrap_or_default();
    let fields = {
        let library = LIBRARY.lock().unwrap();
        item_fields(library::select(library.as_deref().unwrap_or(&[]), filter, &query, alphabetical != JNI_FALSE, limit.max(0) as usize))
    };
    packed_strings(&env, &fields)
}

/// Playlists Spotify says this account can add to, in library order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylistTargets(env: JNIEnv, _: JClass) -> jbyteArray {
    let items = LIBRARY.lock().unwrap();
    let fields = item_fields(items.iter().flatten().filter(|i| i.can_add && !library::is_liked(&i.uri)));
    packed_strings(&env, &fields)
}

/// What you played lately, from Spotify's own history, as five strings each (like `nativeEntries`).
/// Blocks for up to a few seconds; empty if Spotify has nothing or didn't answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRecent(env: JNIEnv, _: JClass) -> jbyteArray {
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
    packed_strings(&env, &fields)
}

/// Learns your username from the first of your own playlists that Spotify says you own.
#[cfg(not(feature = "preview"))]
/// What asking about one playlist told us about whose it is.
#[cfg(not(feature = "preview"))]
enum Owner {
    Yours(String),
    NotYours,
    Unknown(String),
}

#[cfg(not(feature = "preview"))]
fn probe_owner(uri: &str, budget: std::time::Duration) -> Owner {
    let ask = || {
        bridge::call_with_timeout(playlist::SERVICE, "Get", &playlist::request_range(uri, 0, 1), budget)
            .and_then(|bytes| playlist::decode(&bytes, 0))
    };
    let mut reply = ask();
    if matches!(reply, Ok(playlist::Reply::Loading)) {
        // Spotify was still fetching it: give it a moment and ask once more.
        std::thread::sleep(std::time::Duration::from_millis(500));
        reply = ask();
    }
    match reply {
        Ok(playlist::Reply::Page { owner: Some(name), .. }) => Owner::Yours(name),
        Ok(playlist::Reply::Page { owner: None, .. }) => Owner::NotYours,
        Ok(playlist::Reply::Loading) => Owner::Unknown("still loading".into()),
        Err(e) => Owner::Unknown(e),
    }
}

/// Learns your username from whichever of your playlists says it's yours. Playlists you can edit are asked about
/// first (they're yours or shared with you), then the rest, up to 24 in all, six at a time, so someone who follows
/// many playlists by other people is still found. Gives up after about ten seconds.
#[cfg(not(feature = "preview"))]
fn find_username() -> Option<String> {
    let epoch = bridge::session();
    let mut candidates: Vec<(bool, String)> = LIBRARY
        .lock()
        .unwrap()
        .iter()
        .flatten()
        .filter(|i| i.kind == library::Kind::Playlist && !library::is_liked(&i.uri) && i.uri.starts_with("spotify:playlist:"))
        .map(|i| (i.can_add, i.uri.clone()))
        .collect();
    // Stable: editable playlists first, each group in the library's own order.
    candidates.sort_by_key(|(editable, _)| !*editable);
    let candidates: Vec<String> = candidates.into_iter().take(24).map(|(_, uri)| uri).collect();
    if candidates.is_empty() {
        bridge::in_session(epoch, || *LIKED_NOTE.lock().unwrap() = "No playlists with a spotify:playlist: link to ask about".into());
        return None;
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    let (mut not_yours, mut failed, mut found) = (0, Vec::new(), None);
    for chunk in candidates.chunks(6) {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        let budget = remaining.min(std::time::Duration::from_millis(2500));
        let results: Vec<Owner> = std::thread::scope(|scope| {
            let handles: Vec<_> = chunk.iter().map(|uri| scope.spawn(move || probe_owner(uri, budget))).collect();
            handles.into_iter().filter_map(|h| h.join().ok()).collect()
        });
        for result in results {
            match result {
                Owner::Yours(name) if found.is_none() => found = Some(name),
                Owner::Yours(_) => {}
                Owner::NotYours => not_yours += 1,
                Owner::Unknown(why) => failed.push(why),
            }
        }
        if found.is_some() {
            break;
        }
    }
    let note = format!("Looked at {} of your playlists: {not_yours} not yours, {} didn't answer ({})", candidates.len(), failed.len(), failed.first().map_or("", String::as_str));
    log::warn!("Username lookup: {note}");
    bridge::in_session(epoch, || *LIKED_NOTE.lock().unwrap() = note);
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
    #[cfg(not(feature = "preview"))]
    let epoch = bridge::session();
    let listed = LIBRARY.lock().unwrap().iter().flatten().find(|i| library::is_liked(&i.uri)).map(|i| i.uri.clone());
    #[cfg(not(feature = "preview"))]
    let user = {
        if USERNAME.lock().unwrap().is_none() && listed.is_none() {
            let found = find_username();
            if let Some(name) = found { bridge::in_session(epoch, || remember_username(&name)); }
        }
        USERNAME.lock().unwrap().clone()
    };
    #[cfg(feature = "preview")]
    let user: Option<String> = None;
    let working = LIKED_WORKING.lock().unwrap().clone();
    let uri = working.or(listed).or_else(|| user.map(|u| format!("spotify:user:{u}:collection"))).unwrap_or_else(|| "spotify:collection:tracks".into());
    string(&mut env, &uri)
}

/// Each playlist's (song link, time added) when the core reported it empty but its songs were found another way.
#[cfg(not(feature = "preview"))]
type FoundItems = std::collections::HashMap<String, Vec<(String, u32)>>;
#[cfg(not(feature = "preview"))]
static FOUND_ITEMS: Mutex<Option<FoundItems>> = Mutex::new(None);

/// A loaded core reply can be empty when its metadata lookup fails. Try the playlist service for the raw items.
#[cfg(not(feature = "preview"))]
fn when_empty(uri: &str, offset: i32, epoch: i64, reply: playlist::Reply) -> Result<playlist::Reply, String> {
    if !matches!(&reply, playlist::Reply::Page { tracks, next: None, .. } if tracks.is_empty()) {
        return Ok(reply);
    }
    let Some(id) = uri.strip_prefix("spotify:playlist:") else { return Ok(reply) };
    let from_core = bridge::call(playlist::SERVICE, "Get", &playlist::items_request(uri)).and_then(|b| playlist::items(&b));
    let items = match from_core {
        Ok(items) if !items.is_empty() => items,
        other => {
            let bytes = bridge::playlist_contents(id).map_err(|e| {
                log::warn!("Playlist {uri}: Spotify's playlist service: {e}");
                "Couldn’t load playlist tracks from Spotify".to_string()
            })?;
            let items = playlist::server_items(&bytes);
            log::info!("Playlist {uri}: core items-only gave {:?}; Spotify's playlist service has {}", other.map(|i| i.len()), items.len());
            items
        }
    };
    if items.is_empty() {
        return Ok(reply);
    }
    let page = page_of(&items, offset)?;
    bridge::in_session(epoch, || {
        FOUND_ITEMS.lock().unwrap().get_or_insert_with(std::collections::HashMap::new).insert(uri.to_string(), items);
    }).ok_or_else(|| "Account session changed".to_string())?;
    Ok(page)
}

/// One page of found items with their details: the core's metadata service first, then extended metadata directly.
#[cfg(not(feature = "preview"))]
fn page_of(items: &[(String, u32)], offset: i32) -> Result<playlist::Reply, String> {
    use crate::spotify::{metadata, product_state};
    let start = (offset.max(0) as usize).min(items.len());
    let end = (start + playlist::PAGE as usize).min(items.len());
    let mut tracks: Vec<playlist::Track> = items[start..end].iter().map(|(uri, added)| playlist::Track { uri: uri.clone(), added: *added, ..Default::default() }).collect();
    metadata::resolve(&mut tracks);
    if tracks.iter().any(|t| t.title.is_empty()) {
        let values = bridge::call(product_state::SERVICE, "GetValues", &[]).map(|b| product_state::decode(&b)).unwrap_or_default();
        let country = product_state::country(&values).or_else(bridge::device_country).unwrap_or_else(|| "US".into());
        let catalogue = values.get("catalogue").filter(|c| !c.is_empty()).cloned().unwrap_or_else(|| "free".into());
        let (filled, asked, refused) = metadata::resolve_extended(&mut tracks, &country, &catalogue);
        log::info!("Song details from extended metadata ({country}, {catalogue}): {filled} of {asked}; refused by status: {refused:?}");
    }
    for (track, (_, added)) in tracks.iter_mut().zip(&items[start..end]) {
        track.added = *added;
    }
    playlist::fallback_page(tracks, (end < items.len()).then_some(end as i32))
}

fn found_elsewhere(#[allow(unused_variables)] uri: &str) -> bool {
    #[cfg(feature = "preview")]
    return false;
    #[cfg(not(feature = "preview"))]
    FOUND_ITEMS.lock().unwrap().as_ref().is_some_and(|m| m.contains_key(uri))
}

/// A page of a playlist: ["loading"], [error], or ["", next offset or "0", then title, artist, uri, image per track].
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylist(mut env: JNIEnv, _: JClass, uri: JString, #[allow(unused_variables)] offset: jint) -> jbyteArray {
    let epoch = bridge::session();
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
                bridge::in_session(epoch, || *LIKED_WORKING.lock().unwrap() = Some(candidate));
                Ok(reply)
            }
            None => {
                bridge::in_session(epoch, || {
                    let mut note = LIKED_NOTE.lock().unwrap();
                    let lookup = note.lines().find(|l| l.starts_with("Looked at") || l.starts_with("No playlists")).unwrap_or("Username unknown").to_string();
                    *note = format!("{lookup}\n{}", attempts.join("\n"));
                });
                Err(attempts.first().cloned().unwrap_or_else(|| "No Liked Songs link worked".into()))
            }
        }
    } else {
        bridge::call(playlist::SERVICE, "Get", &playlist::request(&uri, offset))
            .and_then(|bytes| playlist::decode(&bytes, offset))
            .and_then(|reply| when_empty(&uri, offset, epoch, reply))
    };
    let fields = match reply {
        Ok(playlist::Reply::Loading) => vec!["loading".into()],
        Ok(page) => {
            bridge::in_session(epoch, || {
                if let playlist::Reply::Page { owner: Some(ref name), .. } = page { remember_username(name); }
            });
            page_fields(page)
        },
        Err(error) => vec![if error.is_empty() { "Couldn’t load playlist".into() } else { error }],
    };
    packed_strings(&env, &fields)
}

/// A playlist page as the UI reads it: "", the next offset, then title, artist, uri and image per track.
fn page_fields(page: playlist::Reply) -> Vec<String> {
    let playlist::Reply::Page { tracks, next, .. } = page else { return vec!["loading".into()] };
    ["".to_string(), next.unwrap_or(0).to_string()].into_iter().chain(tracks.into_iter().flat_map(playlist::Track::fields)).collect()
}

/// The rest of a playlist after its first page: every page from `offset` on, as song fields only. Blocks; stops
/// with no reply on error, or after 10,000 songs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylistRest(mut env: JNIEnv, _: JClass, uri: JString, offset: jint) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let epoch = bridge::session();
    #[cfg(not(feature = "preview"))]
    let found = FOUND_ITEMS.lock().unwrap().as_ref().and_then(|m| m.get(&uri).cloned());
    let mut fields = Vec::new();
    let mut next = Some(offset);
    let mut waits = 0;
    while let Some(at) = next.filter(|&at| at > 0 && at < 10_000) {
        if cfg!(feature = "preview") {
            break;
        }
        if bridge::session() != epoch {
            return std::ptr::null_mut();
        }
        let from_core = || bridge::call(playlist::SERVICE, "Get", &playlist::request(&uri, at)).and_then(|b| playlist::decode(&b, at));
        #[cfg(feature = "preview")]
        let reply = from_core();
        #[cfg(not(feature = "preview"))]
        let reply = found.as_ref().map_or_else(from_core, |items| page_of(items, at));
        match reply {
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
                return std::ptr::null_mut();
            }
            Err(error) => {
                log::warn!("Playlist page at {at}: {error}");
                return std::ptr::null_mut();
            }
        }
    }
    packed_strings(&env, &fields)
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
    let watched = WATCHED_PLAYLIST.lock().unwrap().take();
    if let Some((_, id)) = watched {
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
    let watched_uri = uri.clone();
    let opened = bridge::subscribe(playlist::SERVICE, "Subscribe", &playlist::request(&uri, 0), move |reply| {
        let Ok(bytes) = reply else {
            log::warn!("Playlist stream ended: {}", reply.err().unwrap_or_default());
            return;
        };
        let Ok(page @ playlist::Reply::Page { .. }) = playlist::decode(bytes, 0) else { return };
        // The core's empty copy must not replace songs found another way.
        if matches!(&page, playlist::Reply::Page { tracks, .. } if tracks.is_empty()) && found_elsewhere(&watched_uri) {
            return;
        }
        if WATCHED_PLAYLIST.lock().unwrap().as_ref().is_none_or(|(k, _)| k != &watched) {
            return;
        }
        let encoded = crate::cache::encode(&page_fields(page));
        if crate::cache::get(&watched).as_deref() != Some(encoded.as_slice()) {
            crate::cache::put(&watched, &encoded);
            PLAYLIST_REVISION.fetch_add(1, Ordering::SeqCst);
            bridge::notify_ui();
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
                    drop(queue);
                    drop(state);
                    bridge::notify_ui();
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
