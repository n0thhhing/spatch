//! What SpatchUi.java calls. Every function that touches Spotify blocks, so Java runs them on its
//! own worker threads; the cheap readers (`nativePlayer`, `nativeEntries`) are safe on the UI thread.

use crate::bridge;
use crate::spotify::{library, player, playlist};
#[cfg(not(feature = "preview"))]
use crate::spotify::collection;
use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobjectArray, jstring};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

static CONNECTED: AtomicBool = AtomicBool::new(false);
/// A counter that moves with every state change, and the latest state.
static PLAYER: Mutex<(i64, Option<player::NowPlaying>)> = Mutex::new((0, None));
static LIBRARY: Mutex<Option<Vec<library::Item>>> = Mutex::new(None);
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
    if CONNECTED.load(Ordering::Acquire) { JNI_TRUE } else { JNI_FALSE }
}

/// The player as strings, or null if nothing changed since `revision`:
/// revision, uri, title, artist, album, image, playing, shuffle, repeat, position, duration, source,
/// "Explicit" or "", context uri, the lossless label or ""; then four strings (title, artist, image, uri) per queued track.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlayer(mut env: JNIEnv, _: JClass, revision: jlong) -> jobjectArray {
    let (serial, now) = {
        let state = PLAYER.lock().unwrap();
        if state.0 == revision {
            return std::ptr::null_mut();
        }
        (state.0, state.1.clone())
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
    ];
    for next in &now.up_next {
        fields.extend([next.title.clone(), next.artist.clone(), next.image.clone(), next.uri.clone()]);
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
    let reply: Result<playlist::Reply, String> = Ok(playlist::Reply::Page { tracks: crate::demo::playlist(), next: None, owner: None });
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
    while let Some(at) = next.filter(|&at| at > 0 && at < 10_000) {
        if cfg!(feature = "preview") {
            break;
        }
        match bridge::call(playlist::SERVICE, "Get", &playlist::request(&uri, at)).and_then(|b| playlist::decode(&b, at)) {
            Ok(playlist::Reply::Page { tracks, next: more, .. }) => {
                fields.extend(tracks.into_iter().flat_map(playlist::Track::fields));
                next = more;
            }
            Ok(playlist::Reply::Loading) => std::thread::sleep(std::time::Duration::from_millis(500)),
            Err(error) => {
                log::warn!("Playlist page at {at}: {error}");
                break;
            }
        }
    }
    strings(&mut env, fields)
}

/// The songs to show, as indexes into `fields` (playlist::FIELDS strings per song), for a search and a sort
/// (0 playlist order, 1 title, 2 artist, 3 album, 4 recently added).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeArrange(mut env: JNIEnv, _: JClass, fields: JObjectArray, query: JString, sort: jint) -> jni::sys::jintArray {
    let query = read(&mut env, &query).unwrap_or_default();
    let Ok(len) = env.get_array_length(&fields) else { return std::ptr::null_mut() };
    let mut values = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(item) = env.get_object_array_element(&fields, i) else { return std::ptr::null_mut() };
        let item = JString::from(item);
        values.push(read(&mut env, &item).unwrap_or_default());
        let _ = env.delete_local_ref(item);
    }
    let order: Vec<i32> = playlist::arrange(&values, &query, sort).into_iter().map(|i| i as i32).collect();
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
        return JNI_TRUE;
    }
    #[cfg(not(feature = "preview"))]
    {
        let (method, request) = player::command(command);
        if bridge::call(player::SERVICE, method, &request).is_ok() { JNI_TRUE } else { JNI_FALSE }
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
            *stored = vec![1, 0, 3, 1, 0, 1, 1, 1, 1, 5, 0];
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
        2 => Command::Previous,
        3 => Command::Next,
        4 if value >= 0 => Command::SeekTo(value),
        5 => Command::Shuffle(value != 0),
        6 if (0..=2).contains(&value) => Command::Repeat(value as u8),
        7 if spotify_uri => Command::Play(target),
        8 if spotify_uri => {
            let state = PLAYER.lock().unwrap();
            let Some(next) = state.1.as_ref().and_then(|n| n.up_next.get(value.max(0) as usize)).filter(|t| t.uri == target) else {
                return JNI_FALSE;
            };
            Command::SkipTo { uri: target, uid: next.uid.clone() }
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
    let (method, request) = player::command(command);
    if bridge::call(player::SERVICE, method, &request).is_ok() { JNI_TRUE } else { JNI_FALSE }
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
        return JNI_TRUE;
    }
    #[cfg(not(feature = "preview"))]
    {
        let method = if save != JNI_FALSE { "Add" } else { "Remove" };
        match bridge::call(collection::SERVICE, method, &collection::change_request(&uri)).and_then(|b| collection::decode_change(&b)) {
            Ok(()) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        }
    }
}
