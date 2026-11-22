//! A playlist's pages and its live watch.

use super::*;

/// Each playlist's (song link, time added) when the core reported it empty but its songs were found another way.
#[cfg(not(feature = "preview"))]
pub(super) type FoundItems = std::collections::HashMap<String, std::sync::Arc<[(String, u32)]>>;
#[cfg(not(feature = "preview"))]
pub(super) static FOUND_ITEMS: Mutex<Option<FoundItems>> = Mutex::new(None);

/// Some accounts' cores report a playlist as loaded with no songs, and Spotify's own screens show it empty too: the
/// core waits for every song's details, and its song-details requests fail on those accounts (they carry the
/// account's country and catalogue from the product state). So an empty answer is asked again without details, then
/// Spotify's playlist service directly. Catalog details can be loaded separately from membership.
#[cfg(not(feature = "preview"))]
pub(super) fn when_empty(epoch: i64, uri: &str, offset: i32, reply: playlist::Reply) -> playlist::Reply {
    let empty = matches!(&reply, playlist::Reply::Page { tracks, next: None, .. } if tracks.is_empty());
    if offset != 0 || !uri.starts_with("spotify:playlist:") {
        return reply;
    }
    if !empty {
        if matches!(reply, playlist::Reply::Page { .. }) {
            bridge::in_session(epoch, || {
                if let Some(found) = FOUND_ITEMS.lock().unwrap().as_mut() {
                    found.remove(uri);
                }
            });
        }
        return reply;
    }
    let known = FOUND_ITEMS.lock().unwrap().as_ref().and_then(|m| m.get(uri).cloned());
    let items = match known {
        Some(items) => items,
        None => {
            let from_core = bridge::call(playlist::SERVICE, "Get", &playlist::items_request(uri)).and_then(|b| playlist::items(&b));
            let items = match from_core {
                Ok(items) if !items.is_empty() => {
                    log::info!("Playlist {uri}: the core lists {} songs without their details", items.len());
                    items
                }
                other => {
                    let id = uri.trim_start_matches("spotify:playlist:");
                    match bridge::playlist_contents(id) {
                        Ok(bytes) => {
                            let items = playlist::server_items(&bytes);
                            log::info!("Playlist {uri}: core items-only gave {:?}; Spotify's playlist service has {}", other.map(|i| i.len()), items.len());
                            items
                        }
                        Err(e) => {
                            log::warn!("Playlist {uri}: Spotify's playlist service: {e}");
                            Vec::new()
                        }
                    }
                }
            };
            if items.is_empty() {
                return reply;
            }
            let items: std::sync::Arc<[_]> = items.into();
            if bridge::in_session(epoch, || {
                FOUND_ITEMS.lock().unwrap().get_or_insert_with(std::collections::HashMap::new).insert(uri.to_string(), items.clone());
            }).is_none() {
                return reply;
            }
            items
        }
    };
    page_of(&items, offset)
}

/// Try the account's metadata on the first recovered page, then fill missing details from Spotify's public catalog.
#[cfg(not(feature = "preview"))]
pub(super) fn page_of(items: &[(String, u32)], offset: i32) -> playlist::Reply {
    use crate::spotify::{metadata, product_state};
    let start = (offset.max(0) as usize).min(items.len());
    let end = (start + playlist::PAGE as usize).min(items.len());
    let mut tracks: Vec<playlist::Track> = items[start..end].iter().map(|(uri, added)| playlist::Track { uri: uri.clone(), added: *added, ..Default::default() }).collect();
    if start == 0 {
        metadata::resolve(&mut tracks);
    }
    if start == 0 && tracks.iter().any(|t| t.title.is_empty()) {
        let values = bridge::call(product_state::SERVICE, "GetValues", &[]).map(|b| product_state::decode(&b)).unwrap_or_default();
        let country = product_state::country(&values).or_else(bridge::device_country).unwrap_or_else(|| "US".into());
        let catalogue = values.get("catalogue").filter(|c| !c.is_empty()).cloned().unwrap_or_else(|| "free".into());
        let (filled, asked, refused) = metadata::resolve_extended(&mut tracks, &country, &catalogue);
        log::info!("Song details from extended metadata ({country}, {catalogue}): {filled} of {asked}; refused by status: {refused:?}");
    }
    metadata::resolve_public(&mut tracks);
    // A song can still lack details if it isn't in the public catalog or Spotify's services are unreachable.
    for t in tracks.iter_mut().filter(|t| t.title.is_empty()) {
        t.title = "Song details not sent".into();
        t.artist = "Spotify didn't send this song's details to this app".into();
    }
    playlist::Reply::Page { tracks, next: (end < items.len()).then_some(end as i32), owner: None }
}

pub(super) fn found_elsewhere(#[allow(unused_variables)] uri: &str) -> bool {
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
            .map(|reply| when_empty(epoch, &uri, offset, reply))
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
    if bridge::session() == epoch { packed_strings(&env, &fields) } else { std::ptr::null_mut() }
}

/// A playlist page as the UI reads it: "", the next offset, then title, artist, uri and image per track.
pub(super) fn page_fields(page: playlist::Reply) -> Vec<String> {
    let playlist::Reply::Page { tracks, next, .. } = page else { return vec!["loading".into()] };
    ["".to_string(), next.unwrap_or(0).to_string()].into_iter().chain(tracks.into_iter().flat_map(playlist::Track::fields)).collect()
}

/// The rest of a playlist after its first page: every page from `offset` on, as song fields only. Blocks; stops
/// at an error (returning what it has) or 10,000 songs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePlaylistRest(mut env: JNIEnv, _: JClass, uri: JString, offset: jint) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let epoch = bridge::session();
    #[cfg(not(feature = "preview"))]
    if let Some(items) = bridge::in_session(epoch, || {
        FOUND_ITEMS.lock().unwrap().as_ref().and_then(|m| m.get(&uri).cloned())
    }).flatten() {
        let fields = (offset.max(0)..10_000.min(items.len() as i32)).step_by(playlist::PAGE as usize)
            .take_while(|_| bridge::session() == epoch)
            .flat_map(|at| match page_of(&items, at) {
                playlist::Reply::Page { tracks, .. } => tracks,
                playlist::Reply::Loading => Vec::new(),
            })
            .flat_map(playlist::Track::fields)
            .collect::<Vec<_>>();
        return if bridge::session() == epoch { packed_strings(&env, &fields) } else { std::ptr::null_mut() };
    }
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
    if bridge::session() == epoch { packed_strings(&env, &fields) } else { std::ptr::null_mut() }
}

/// The open playlist's saved-answer key and its subscription; bumps `PLAYLIST_REVISION` on each change.
pub(super) static WATCHED_PLAYLIST: Mutex<Option<(String, i64)>> = Mutex::new(None);
pub(super) static PLAYLIST_REVISION: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

pub(super) fn stop_watching_playlist() {
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
