//! The library: fetching, watching and listing it, plus the entries, targets and recents the UI asks for.

use super::*;


pub(super) fn fetch_library() -> Result<bool, String> {
    let epoch = bridge::session();
    let bytes = bridge::call(library::SERVICE, "All", &library::request(300))?;
    let items = library::decode(&bytes)?;
    bridge::in_session(epoch, || {
        if let Some(name) = library::username(&bytes) { remember_username(&name); }
        crate::cache::put("library", &bytes);
        set_library(items)
    }).ok_or_else(|| "Account session changed".into())
}

pub(super) static LIBRARY_REVISION: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);
/// Subscribed to the core's StreamAll; `LIBRARY_LIVE` once it has delivered, so plain refreshes can stop.
#[cfg(not(feature = "preview"))]
pub(super) static LIBRARY_WATCHED: AtomicBool = AtomicBool::new(false);
pub(super) static LIBRARY_LIVE: AtomicBool = AtomicBool::new(false);

/// Replaces the library if it changed; true if it did.
pub(super) fn set_library(items: Vec<library::Item>) -> bool {
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
pub(super) fn watch_library() {
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

/// Logs the account's product state once per session: the keys that decide song details and the login hand-off.
#[cfg(not(feature = "preview"))]
pub(super) fn log_product_state() {
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

/// Loads the current account's library; "" on success, "cached" on a last-launch copy (a real refresh
/// follows close behind, same as the core's own screens), otherwise Spotify's error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeLibrary(mut env: JNIEnv, _: JClass) -> jstring {
    #[cfg(not(feature = "preview"))]
    watch_library();
    #[cfg(not(feature = "preview"))]
    log_product_state();
    if LIBRARY.lock().unwrap().is_some() {
        return string(&mut env, "");
    }
    // Show last launch's copy at once instead of blocking the first paint on a live round trip.
    if let Some(bytes) = crate::cache::get("library") {
        if let Ok(items) = library::decode(&bytes) {
            if let Some(name) = library::username(&bytes) { remember_username(&name); }
            set_library(items);
            return string(&mut env, "cached");
        }
    }
    let status = match fetch_library() {
        Ok(_) => String::new(),
        // The StreamAll subscription opened above may have delivered the library while this request was timing out.
        Err(_) if LIBRARY.lock().unwrap().is_some() => String::new(),
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

pub(super) fn item_fields<'a>(items: impl IntoIterator<Item = &'a library::Item>) -> Vec<String> {
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
