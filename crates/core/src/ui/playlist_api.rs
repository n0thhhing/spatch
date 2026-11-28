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
        let encoded = crate::packed::encode(&page_fields(page));
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

/// Experiment: a track's audio files from extended-metadata, then each one's CDN URL. Returns the lines to show.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeAudioTest(mut env: JNIEnv, _: JClass, uri: JString) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let mut lines = Vec::new();
    match crate::spotify::metadata::audio_files(&uri, "US", "free") {
        Err(e) => lines.push(format!("AUDIO_FILES error: {e}")),
        Ok(files) if files.is_empty() => lines.push("AUDIO_FILES: no files in reply".into()),
        Ok(files) => {
            lines.push(format!("AUDIO_FILES: {} files", files.len()));
            for (format, id, size) in files.iter().take(4) {
                lines.push(format!("fmt {format} id {id} size {size}"));
                match bridge::resolve_audio(id) {
                    Ok(url) => lines.push(format!("  -> {url}")),
                    Err(e) => lines.push(format!("  -> resolve error: {e}")),
                }
            }
        }
    }
    packed_strings(&env, &lines)
}

/// Experiment: pull a whole track through the core's own decrypting streamer and write it to a file.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeStreamTest(mut env: JNIEnv, _: JClass, uri: JString) -> jbyteArray {
    use crate::bridge;
    use crate::spotify::{downloads, metadata};
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let mut lines = Vec::new();
    let id = uri.trim_start_matches("spotify:track:").to_string();
    let files = match metadata::audio_files(&uri, "US", "free") {
        Ok(files) if !files.is_empty() => files,
        Ok(_) => return packed_strings(&env, &["no audio files".into()]),
        Err(e) => return packed_strings(&env, &[format!("audio files: {e}")]),
    };
    let url = match bridge::resolve_audio(&files[0].1) {
        Ok(url) => url,
        Err(e) => return packed_strings(&env, &[format!("resolve: {e}")]),
    };
    lines.push(format!("track id {id}"));
    let handle = downloads::media_handle(&id).unwrap_or_default();
    lines.push(format!("handle {handle}"));
    let streamer = match downloads::progressive_streamer(&url) {
        Ok(s) => s,
        Err(e) => return packed_strings(&env, &[format!("streamer: {e}")]),
    };
    lines.push(format!("streamer {streamer}"));
    // One subscription: RequestData answers with a series of chunks, each carrying its own data.
    let (tx, rx) = std::sync::mpsc::channel::<(u64, u64, bool, u64, Vec<u8>)>();
    let request = downloads::request_data(streamer, 0, 4 << 20);
    let sub = match bridge::subscribe(downloads::DOWNLOAD, "RequestData", &request, move |reply| match reply {
        Ok(bytes) => { let _ = tx.send(downloads::decode_data(bytes)); }
        Err(e) => { log::warn!("chunk: {e}"); }
    }) {
        Ok(id) => id,
        Err(e) => return packed_strings(&env, &[format!("subscribe: {e}")]),
    };
    let mut out = Vec::new();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    let mut errors = 0;
    while std::time::Instant::now() < deadline && out.len() < 20 << 20 {
        match rx.recv_timeout(std::time::Duration::from_millis(800)) {
            Ok((_pos, total, _final, error, data)) => {
                errors = errors.max(error);
                lines.push(format!("chunk {} bytes, total {total}", data.len()));
                out.extend_from_slice(&data);
                if data.len() < 8 << 10 { break; }
            }
            Err(_) => break,
        }
    }
    bridge::cancel(sub);
    if errors > 0 { lines.push(format!("stream error code {errors}")); }
    // The preview: a plain MP3 over HTTPS, no key and no CDN resolution.
    match crate::spotify::metadata::preview_id(&uri, "US", "free") {
        Ok(preview) => {
            let url = format!("https://p.scdn.co/mp3-preview/{preview}");
            lines.push(format!("preview url {url}"));
            match bridge::fetch_bytes(&url) {
                Ok(bytes) => {
                    lines.push(format!("preview {} bytes, head {:02x?}", bytes.len(), &bytes[..4.min(bytes.len())]));
                    if let Some(dir) = crate::cache::dir() {
                        let path = dir.join("spatch-preview.mp3");
                        if std::fs::write(&path, &bytes).is_ok() {
                            lines.push(format!("preview saved {}", path.display()));
                        }
                    }
                }
                Err(e) => lines.push(format!("preview fetch: {e}")),
            }
        }
        Err(e) => lines.push(format!("preview id: {e}")),
    }
    // The per-track audio key path (playplay v2), which the decryptor needs.
    for (_, file_id, _) in files.iter().take(2) {
        match bridge::call_playplay(file_id) {
            Ok(key) => lines.push(format!("playplay key {} bytes {:02x?}", key.len(), &key[..key.len().min(40)])),
            Err(e) => lines.push(format!("playplay: {e}")),
        }
    }
    // What the core itself would stream: format and whether it is encrypted.
    match bridge::call(downloads::DOWNLOAD, "GetMediaManifest", &downloads::manifest_params(if handle.is_empty() { id.as_str() } else { handle.as_str() }, &id)) {
        Ok(reply) => {
            lines.push(format!("manifest reply {} bytes {:02x?}", reply.len(), &reply[..reply.len().min(24)]));
            let manifest = downloads::decode_data(&reply).4;
            lines.push(format!("manifest {} bytes head {:02x?}", manifest.len(), &manifest[..8.min(manifest.len())]));
            for needle in ["OGG_VORBIS_96", "OGG_VORBIS_160", "OGG_VORBIS_320", "MP3_96", "MP3_160", "MP3_256", "file_id_hex"] {
                if String::from_utf8_lossy(&manifest).contains(needle) { lines.push(format!("manifest has {needle}")); }
            }
        }
        Err(e) => lines.push(format!("manifest: {e}")),
    }
    lines.push(format!("collected {} bytes, head {:02x?}", out.len(), &out[..4.min(out.len())]));
    let path = match crate::cache::dir() {
        Some(dir) => format!("{}/spatch-stream.bin", dir.display()),
        None => return packed_strings(&env, &["no cache dir".into()]),
    };
    match std::fs::write(&path, &out) {
        Ok(()) => lines.push(format!("wrote {path}")),
        Err(e) => lines.push(format!("write: {e}")),
    }
    packed_strings(&env, &lines)
}

/// Experiment: decrypt a real downloaded file with the last key the core used, to prove the scheme.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDecryptTest(mut env: JNIEnv, _: JClass, uri: JString) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let mut lines = Vec::new();
    let files = match crate::spotify::metadata::audio_files(&uri, "US", "free") {
        Ok(files) if !files.is_empty() => files,
        Ok(_) => { return packed_strings(&env, &["no audio files".into()]); }
        Err(e) => { return packed_strings(&env, &[format!("audio files: {e}")]); }
    };
    let (format, id, _) = files[0].clone();
    lines.push(format!("file fmt {format} id {}", &id[..16.min(id.len())]));
    let url = match bridge::resolve_audio(&id) {
        Ok(url) => url,
        Err(e) => { return packed_strings(&env, &[format!("resolve: {e}")]); }
    };
    let body = match bridge::fetch_bytes(&url) {
        Ok(body) => body,
        Err(e) => { return packed_strings(&env, &[format!("download: {e}")]); }
    };
    lines.push(format!("downloaded {} bytes", body.len()));
    match bridge::aes_seen() {
        Some((key, nonce)) => lines.push(bridge::decrypt_file(&key, &nonce, &[], &body)),
        None => lines.push("no key captured yet; play a track first".into()),
    }
    packed_strings(&env, &lines)
}

/// Fetches a track's preview MP3 into the cache and replies "saved <path>", or why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePreview(mut env: JNIEnv, _: JClass, uri: JString) -> jbyteArray {
    let Some(uri) = read(&mut env, &uri) else { return std::ptr::null_mut() };
    let preview = match crate::spotify::metadata::preview_id(&uri, "US", "free") {
        Ok(id) => id,
        Err(e) => return packed_strings(&env, &[format!("no preview id: {e}")]),
    };
    let Some(dir) = crate::cache::dir() else { return packed_strings(&env, &["no cache dir".into()]) };
    let bytes = match bridge::fetch_bytes(&format!("https://p.scdn.co/mp3-preview/{preview}")) {
        Ok(bytes) if bytes.len() > 1024 => bytes,
        Ok(bytes) => return packed_strings(&env, &[format!("preview too short: {} bytes", bytes.len())]),
        Err(e) => return packed_strings(&env, &[format!("preview: {e}")]),
    };
    let path = dir.join("spatch-preview.mp3");
    match std::fs::write(&path, &bytes) {
        Ok(()) => packed_strings(&env, &[format!("saved {} ({} bytes)", path.display(), bytes.len())]),
        Err(e) => packed_strings(&env, &[format!("write: {e}")]),
    }
}

/// Starts recording the tapped stream to a WAV in the cache folder: the core has already decrypted it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRecordStart(env: JNIEnv, _: JClass) -> jbyteArray {
    let Some(dir) = crate::cache::dir() else { return packed_strings(&env, &["no cache dir".into()]) };
    let path = format!("{}/spatch-recording.wav", dir.display());
    let (rate, channels) = crate::audio_tap::recorder_format();
    match crate::audio_tap::recorder::start(&path, rate, channels) {
        Ok(()) => packed_strings(&env, &[format!("recording to {path}")]),
        Err(e) => packed_strings(&env, &[format!("record: {e}")]),
    }
}

/// Stops the recording and reports what was captured.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRecordStop(mut env: JNIEnv, _: JClass, share: JString) -> jbyteArray {
    let share_dir = env.get_string(&share).ok().map(|s| std::path::PathBuf::from(String::from(s)));
    let Some((text, samples, rate)) = crate::audio_tap::recorder::stop() else {
        return packed_strings(&env, &["was not recording".into()]);
    };
    let seconds = samples as f64 / rate.max(1) as f64;
    let Some(dir) = crate::cache::dir() else { return packed_strings(&env, &[format!("recorded {text}")]) };
    let source = dir.join("spatch-recording.wav");
    // The private cache is not readable over adb, so leave a copy where a file manager reaches it.
    let note = match share_dir {
        Some(out) if std::fs::create_dir_all(&out).is_ok() => {
            let target = out.join("spatch-recording.wav");
            match std::fs::copy(&source, &target) {
                Ok(_) => format!(", copied to {}", target.display()),
                Err(e) => format!(", copy failed: {e}"),
            }
        }
        _ => String::new(),
    };
    packed_strings(&env, &[format!("recorded {text}, {seconds:.1} s, {}{note}", source.display())])
}

/// For a UI row: "recording <n> s" or "idle".
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRecordState(env: JNIEnv, _: JClass) -> jbyteArray {
    let state = crate::audio_tap::recorder::state();
    let text = if state.recording {
        format!("recording {:.1} s", state.seconds)
    } else {
        "idle".to_string()
    };
    packed_strings(&env, &[text])
}
