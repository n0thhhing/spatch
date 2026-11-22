//! Liked Songs: working out whose library a song belongs to and the Liked list itself.

use super::*;

/// Learns your username from the first of your own playlists that Spotify says you own.
#[cfg(not(feature = "preview"))]
/// What asking about one playlist told us about whose it is.
#[cfg(not(feature = "preview"))]
pub(super) enum Owner {
    Yours(String),
    NotYours,
    Unknown(String),
}

#[cfg(not(feature = "preview"))]
pub(super) fn probe_owner(uri: &str, budget: std::time::Duration) -> Owner {
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
pub(super) fn find_username() -> Option<String> {
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
