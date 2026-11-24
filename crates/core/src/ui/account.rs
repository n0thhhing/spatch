//! Account profile, queue-based playlist creation and song additions through the shared JNI contract.

use super::*;
#[cfg(not(feature = "preview"))]
use crate::spotify::profile;
#[cfg(not(feature = "preview"))]
use super::liked::find_username;

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
    let epoch = bridge::session();
    if let Some(name) = USERNAME.lock().unwrap().clone() {
        return Some(name);
    }
    if LIBRARY.lock().unwrap().is_none() {
        let _ = fetch_library();
    }
    let listed = LIBRARY.lock().unwrap().iter().flatten()
        .find(|i| library::is_liked(&i.uri)).map(|i| i.uri.clone());
    if let Some(uri) = listed { bridge::in_session(epoch, || learn_username(&uri)); }
    if let Some(name) = USERNAME.lock().unwrap().clone() {
        return Some(name);
    }
    let found = find_username();
    if let Some(ref name) = found {
        bridge::in_session(epoch, || remember_username(name));
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
            can_add: true,
            subtitle: if description.is_empty() { "Playlist".into() } else { description },
            uri: uri.clone(),
            image,
        });
        LIBRARY_REVISION.fetch_add(1, Ordering::SeqCst);
    }
    bridge::notify_ui();
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
            let _ = fetch_library();
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
    let editable = LIBRARY.lock().unwrap().iter().flatten().any(|item| item.uri == playlist_uri && item.can_add);
    let mut playlists = PREVIEW_PLAYLISTS.lock().unwrap();
    let map = playlists.get_or_insert_with(std::collections::HashMap::new);
    if !map.contains_key(playlist_uri) {
        if !editable { return "This playlist is not editable in the preview".into(); }
        map.insert(playlist_uri.to_string(), crate::demo::playlist());
    }
    let tracks = map.get_mut(playlist_uri).unwrap();
    let added: Vec<playlist::Track> = uris
        .iter()
        .map(|u| crate::demo::track_from_entry(u, "", "", ""))
        .collect();
    let first_image = added.first().map(|t| t.image.clone()).unwrap_or_default();
    tracks.extend(added);
    let mut bumped = false;
    if !first_image.is_empty() {
        let mut library = LIBRARY.lock().unwrap();
        if let Some(item) = library.as_mut().and_then(|items| items.iter_mut().find(|i| i.uri == playlist_uri)) {
            if item.image.is_empty() {
                item.image = first_image;
                LIBRARY_REVISION.fetch_add(1, Ordering::SeqCst);
                bumped = true;
            }
        }
    }
    drop(playlists);
    if bumped {
        bridge::notify_ui();
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

/// What the account lookup knows, for troubleshooting: how much of the library has loaded, the username if it has
/// been learned, and what the last username lookup reported.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeConnection(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let library = LIBRARY.lock().unwrap().as_ref().map_or_else(|| "not loaded yet".to_string(), |l| format!("{} items", l.len()));
    let username = USERNAME.lock().unwrap().clone().unwrap_or_default();
    let note = LIKED_NOTE.lock().unwrap().clone();
    strings(&mut env, vec![library, username, note])
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
