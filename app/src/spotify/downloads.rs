//! Spotify's downloads, as the core keeps them (`Offline` service): which playlists, albums and shows are
//! downloaded or downloading, how far along, adding and removing them, and the storage they take.
//!
//! The messages are small and read by hand (see `wire`):
//! - DownloadRequest { DeviceKey device = 1; string uri = 2 } -> DownloadResponse { int64 error = 1 }
//! - GetContextsRequest { ContextInfoPolicy policy = 1; DeviceKey device = 2; repeated string uris = 3 }
//!   -> GetContextsResponse { repeated ContextInfo contexts = 1 }, ContextInfo { Context context = 1 },
//!   Context { string uri = 1; State state = 2; float percent_complete = 3 }
//! - TotalProgressResponse { Progress progress = 1 }
//! - Storage.GetStats -> Stats { ... int64 current_size = 4; current_locked_size = 5; free_space = 6; total_space = 7 }
//!
//! `SubscribeContexts` and `SubscribeTotalProgress` keep the state here live; a full `GetContexts` reconciles it.

use super::wire::{self, fields, first};
use std::collections::BTreeMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

pub const SERVICE: &str = "spotify.offline_esperanto.proto.Offline";
pub const STORAGE: &str = "spotify.storage.esperanto.proto.Storage";

/// Context.State in the core.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    NotDownloaded,
    Error,
    Requested,
    Downloaded,
    Downloading,
    Waiting,
}

impl State {
    fn from(n: u64) -> State {
        match n {
            1 => State::Error,
            2 => State::Requested,
            3 => State::Downloaded,
            4 => State::Downloading,
            5 => State::Waiting,
            _ => State::NotDownloaded,
        }
    }

    /// The number the UI uses: 0 not downloaded, 1 error, 2 requested, 3 downloaded, 4 downloading, 5 waiting.
    pub fn code(self) -> i32 {
        self as i32
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Download {
    pub uri: String,
    pub state: State,
    /// 0 to 1.
    pub percent: f32,
}

/// Percentages come as 0..1 or 0..100 depending on the message; the UI gets 0..1.
fn unit(p: f32) -> f32 {
    (if p > 1.0 { p / 100.0 } else { p }).clamp(0.0, 1.0)
}

pub fn decode_contexts(bytes: &[u8]) -> Vec<Download> {
    fields(bytes)
        .into_iter()
        .filter(|(f, _)| *f == 1)
        .filter_map(|(_, info)| {
            let info = fields(info.bytes());
            let context = fields(first(&info, 1)?.bytes());
            let uri = first(&context, 1)?.text();
            (!uri.is_empty()).then(|| Download {
                uri,
                state: State::from(first(&context, 2).map_or(0, |v| v.number())),
                percent: unit(first(&context, 3).map_or(0.0, |v| v.float())),
            })
        })
        .collect()
}

/// A DeviceKey (device id and cache id) taken from a Device message.
pub fn device_key(device: &[u8]) -> Vec<u8> {
    let parsed = fields(device);
    let mut out = Vec::new();
    for field in [1, 2] {
        if let Some(v) = first(&parsed, field) {
            wire::bytes_field(&mut out, field, v.bytes());
        }
    }
    out
}

pub fn contexts_request(device: &[u8], uris: &[String]) -> Vec<u8> {
    let mut out = Vec::new();
    if !device.is_empty() {
        wire::bytes_field(&mut out, 2, device);
    }
    for uri in uris {
        wire::string_field(&mut out, 3, uri);
    }
    out
}

pub fn download_request(device: &[u8], uri: &str) -> Vec<u8> {
    let mut out = Vec::new();
    if !device.is_empty() {
        wire::bytes_field(&mut out, 1, device);
    }
    wire::string_field(&mut out, 2, uri);
    out
}

/// Why a download call failed, from DownloadResponse.error (the core's Error.ErrorCode); "" for success.
pub fn error_message(bytes: &[u8]) -> String {
    match first(&fields(bytes), 1).map_or(0, |v| v.number()) {
        0 => String::new(),
        8 => "There isn’t enough space on the phone".into(),
        14 => "The offline licence expired; connect to the internet to renew it".into(),
        19 => "Too many songs are already downloaded".into(),
        23 => "Downloads need Spotify Premium".into(),
        36 => "Downloads are off while Spotify is in offline mode".into(),
        702 => "This account has downloads on too many devices".into(),
        other => format!("Spotify refused (code {other})"),
    }
}

/// What the core says about the whole download queue.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Progress {
    pub queued_tracks: u64,
    pub downloaded_tracks: u64,
    pub total_tracks: u64,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub downloading: bool,
    pub speed: u64,
    pub percent: f32,
    pub seconds_left: u64,
}

/// A Progress message.
fn progress(bytes: &[u8]) -> Progress {
    let f = fields(bytes);
    let n = |field| first(&f, field).map_or(0, |v| v.number());
    Progress {
        queued_tracks: n(1),
        downloaded_bytes: n(4),
        downloaded_tracks: n(3),
        total_tracks: n(5),
        total_bytes: n(6),
        downloading: n(7) != 0,
        speed: n(8),
        percent: unit(first(&f, 9).map_or(0.0, |v| v.float())),
        seconds_left: n(10),
    }
}

/// A TotalProgressResponse.
pub fn decode_total(bytes: &[u8]) -> Progress {
    first(&fields(bytes), 1).map(|v| progress(v.bytes())).unwrap_or_default()
}

/// What the cache holds, in bytes: everything, the part locked for downloads, free space on the phone, the
/// phone's total space.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Storage {
    pub current: u64,
    pub locked: u64,
    pub free: u64,
    pub total: u64,
}

pub fn decode_storage(bytes: &[u8]) -> Storage {
    let f = fields(bytes);
    let n = |field| first(&f, field).map_or(0, |v| v.number());
    Storage { current: n(4), locked: n(5), free: n(6), total: n(7) }
}

// ---- shared, live state ----

#[derive(Default)]
struct Shared {
    map: BTreeMap<String, (State, f32)>,
    total: Progress,
    /// This phone's DeviceKey once the core has been asked for it.
    #[cfg(not(feature = "preview"))]
    device: Option<Vec<u8>>,
}

impl Shared {
    /// Records a context; true if its state (not just its percentage) changed.
    fn put(&mut self, d: &Download) -> bool {
        if d.state == State::NotDownloaded {
            return self.map.remove(&d.uri).is_some();
        }
        let before = self.map.insert(d.uri.clone(), (d.state, d.percent));
        before.is_none_or(|(state, _)| state != d.state)
    }
}

static SHARED: Mutex<Option<Shared>> = Mutex::new(None);
/// Bumps on any change (including progress); the UI redraws what shows progress.
static REVISION: AtomicU64 = AtomicU64::new(0);
/// Bumps when something is added, removed or changes state; the UI redraws lists and badges.
static SET_REVISION: AtomicU64 = AtomicU64::new(0);

fn with<T>(f: impl FnOnce(&mut Shared) -> T) -> T {
    f(SHARED.lock().unwrap().get_or_insert_with(Shared::default))
}

fn bump_revision(set_changed: bool) {
    REVISION.fetch_add(1, Ordering::SeqCst);
    if set_changed {
        SET_REVISION.fetch_add(1, Ordering::SeqCst);
    }
    crate::bridge::notify_ui();
}

fn record(downloads: &[Download]) {
    let changed = with(|s| downloads.iter().fold(false, |acc, d| s.put(d) | acc));
    bump_revision(changed);
}

pub fn reset() {
    *SHARED.lock().unwrap() = None;
    #[cfg(not(feature = "preview"))]
    core::reset_watches();
    bump_revision(true);
}

pub fn revision() -> u64 {
    REVISION.load(Ordering::SeqCst)
}

pub fn set_revision() -> u64 {
    SET_REVISION.load(Ordering::SeqCst)
}

/// Everything downloaded or on its way, by uri.
pub fn snapshot() -> Vec<Download> {
    with(|s| s.map.iter().map(|(uri, (state, percent))| Download { uri: uri.clone(), state: *state, percent: *percent }).collect())
}

pub fn total() -> Progress {
    with(|s| s.total.clone())
}

#[cfg(not(feature = "preview"))]
mod core {
    use super::*;
    use crate::bridge;
    use std::sync::atomic::AtomicBool;

    static WATCHING: AtomicBool = AtomicBool::new(false);
    static PROGRESS_WATCHING: AtomicBool = AtomicBool::new(false);

    pub(super) fn reset_watches() {
        WATCHING.store(false, Ordering::Release);
        PROGRESS_WATCHING.store(false, Ordering::Release);
    }

    /// This phone's DeviceKey, asked of the core once (empty if it won't say: the core then assumes this phone).
    fn device() -> Vec<u8> {
        if let Some(known) = with(|s| s.device.clone()) {
            return known;
        }
        let epoch = bridge::session();
        let key = bridge::call(SERVICE, "GetLocalDevice", &[]).map(|bytes| device_key(&bytes)).unwrap_or_default();
        bridge::in_session(epoch, || with(|s| s.device = Some(key.clone())));
        key
    }

    /// Replaces what's known with the core's full answer. If it won't list everything when asked for no uris in
    /// particular, it's asked about each library item instead.
    pub fn refresh(library_uris: &[String]) -> Result<(), String> {
        let epoch = bridge::session();
        let device = device();
        let mut found = decode_contexts(&bridge::call(SERVICE, "GetContexts", &contexts_request(&device, &[]))?);
        if found.is_empty() && !library_uris.is_empty() {
            found = decode_contexts(&bridge::call(SERVICE, "GetContexts", &contexts_request(&device, library_uris))?);
        }
        found.retain(|d| d.state != State::NotDownloaded);
        bridge::in_session(epoch, || {
            with(|s| s.map = found.iter().map(|d| (d.uri.clone(), (d.state, d.percent))).collect());
            bump_revision(true);
        }).ok_or_else(|| "Account session changed".into())
    }

    /// Follows the core's own streams so states and progress stay live.
    pub fn watch() {
        if !WATCHING.swap(true, Ordering::Relaxed) {
            let device = device();
            let opened = bridge::subscribe(SERVICE, "SubscribeContexts", &contexts_request(&device, &[]), |reply| match reply {
                Ok(bytes) => record(&decode_contexts(bytes)),
                Err(error) => {
                    log::warn!("Downloads stream ended: {error}");
                    WATCHING.store(false, Ordering::Relaxed);
                }
            });
            if let Err(error) = opened {
                log::warn!("Downloads stream: {error}");
                WATCHING.store(false, Ordering::Relaxed);
            }
        }
        if !PROGRESS_WATCHING.swap(true, Ordering::Relaxed) {
            let opened = bridge::subscribe(SERVICE, "SubscribeTotalProgress", &[], |reply| match reply {
                Ok(bytes) => {
                    with(|s| s.total = decode_total(bytes));
                    bump_revision(false);
                }
                Err(_) => { PROGRESS_WATCHING.store(false, Ordering::Relaxed); }
            });
            if opened.is_err() { PROGRESS_WATCHING.store(false, Ordering::Relaxed); }
        }
    }

    pub fn add(uri: &str) -> Result<(), String> {
        let epoch = bridge::session();
        let reply = bridge::call(SERVICE, "AddDownload", &download_request(&device(), uri))?;
        match error_message(&reply) {
            m if m.is_empty() => {
                bridge::in_session(epoch, || record(&[Download { uri: uri.into(), state: State::Requested, percent: 0.0 }]))
                    .ok_or_else(|| "Account session changed".into())
            }
            m => Err(m),
        }
    }

    pub fn remove(uri: &str) -> Result<(), String> {
        let epoch = bridge::session();
        let reply = bridge::call(SERVICE, "RemoveDownload", &download_request(&device(), uri))?;
        match error_message(&reply) {
            m if m.is_empty() => {
                bridge::in_session(epoch, || record(&[Download { uri: uri.into(), state: State::NotDownloaded, percent: 0.0 }]))
                    .ok_or_else(|| "Account session changed".into())
            }
            m => Err(m),
        }
    }

    pub fn remove_all() -> Result<(), String> {
        let epoch = bridge::session();
        bridge::call(SERVICE, "RemoveAllDownloads", &[])?;
        bridge::in_session(epoch, || {
            with(|s| s.map.clear());
            bump_revision(true);
        }).ok_or_else(|| "Account session changed".into())
    }

    pub fn storage() -> Result<Storage, String> {
        bridge::call(STORAGE, "GetStats", &[]).map(|bytes| decode_storage(&bytes))
    }
}

#[cfg(not(feature = "preview"))]
pub use core::{add, refresh, remove, remove_all, storage, watch};

/// The preview app has no core: a few demo downloads, one still in progress, and adding one starts a pretend
/// download that fills up over a few seconds, so the screens can be seen working.
#[cfg(feature = "preview")]
mod demo {
    use super::*;
    use std::sync::atomic::AtomicBool;

    static SEEDED: AtomicBool = AtomicBool::new(false);

    fn ticker(uri: String, from: f32, step: f32) {
        std::thread::spawn(move || {
            let mut p = from;
            while p < 1.0 {
                std::thread::sleep(std::time::Duration::from_millis(350));
                p = (p + step).min(1.0);
                let state = if p >= 1.0 { State::Downloaded } else { State::Downloading };
                record(&[Download { uri: uri.clone(), state, percent: p }]);
                refresh_total();
            }
        });
    }

    fn refresh_total() {
        let all = snapshot();
        let downloading = all.iter().any(|d| d.state == State::Downloading);
        let tracks = 40 * all.len() as u64;
        let done: u64 = all.iter().map(|d| (40.0 * d.percent) as u64).sum();
        with(|s| {
            s.total = Progress {
                downloaded_tracks: done,
                total_tracks: tracks,
                downloaded_bytes: done * 6_000_000,
                total_bytes: tracks * 6_000_000,
                downloading,
                speed: 2_400_000,
                percent: if tracks == 0 { 0.0 } else { done as f32 / tracks as f32 },
                seconds_left: if downloading { 70 } else { 0 },
                ..Default::default()
            }
        });
    }

    pub fn refresh(_: &[String]) -> Result<(), String> {
        if !SEEDED.swap(true, Ordering::Relaxed) {
            record(&[
                Download { uri: "spotify:preview:1".into(), state: State::Downloaded, percent: 1.0 },
                Download { uri: "spotify:album:1".into(), state: State::Downloaded, percent: 1.0 },
                Download { uri: "spotify:album:0".into(), state: State::Downloading, percent: 0.42 },
            ]);
            ticker("spotify:album:0".into(), 0.42, 0.015); // slow, so it can be looked at
            refresh_total();
        }
        Ok(())
    }

    pub fn watch() {}

    pub fn add(uri: &str) -> Result<(), String> {
        record(&[Download { uri: uri.into(), state: State::Requested, percent: 0.0 }]);
        ticker(uri.into(), 0.0, 0.07);
        Ok(())
    }

    pub fn remove(uri: &str) -> Result<(), String> {
        record(&[Download { uri: uri.into(), state: State::NotDownloaded, percent: 0.0 }]);
        refresh_total();
        Ok(())
    }

    pub fn remove_all() -> Result<(), String> {
        with(|s| s.map.clear());
        bump_revision(true);
        refresh_total();
        Ok(())
    }

    pub fn storage() -> Result<Storage, String> {
        let t = total();
        Ok(Storage { current: t.downloaded_bytes + 310_000_000, locked: t.downloaded_bytes, free: 41_000_000_000, total: 128_000_000_000 })
    }
}

#[cfg(feature = "preview")]
pub use demo::{add, refresh, remove, remove_all, storage, watch};

#[cfg(test)]
mod tests {
    use super::*;

    fn context(uri: &str, state: u64, percent: f32) -> Vec<u8> {
        let mut c = Vec::new();
        wire::string_field(&mut c, 1, uri);
        wire::number_field(&mut c, 2, state);
        c.push(0x1d);
        c.extend_from_slice(&percent.to_le_bytes());
        let mut info = Vec::new();
        wire::bytes_field(&mut info, 1, &c);
        info
    }

    #[test]
    fn reads_contexts_with_their_state_and_percentage() {
        let mut reply = Vec::new();
        wire::bytes_field(&mut reply, 1, &context("spotify:playlist:a", 4, 0.25));
        wire::bytes_field(&mut reply, 1, &context("spotify:album:b", 3, 100.0));
        wire::bytes_field(&mut reply, 1, &context("", 3, 1.0));
        let found = decode_contexts(&reply);
        assert_eq!(found.len(), 2, "a context without a uri is dropped");
        assert_eq!((found[0].state, found[0].percent), (State::Downloading, 0.25));
        assert_eq!((found[1].state, found[1].percent), (State::Downloaded, 1.0), "0..100 becomes 0..1");
    }

    #[test]
    fn requests_name_the_device_and_the_list() {
        let mut device = Vec::new();
        wire::string_field(&mut device, 1, "dev");
        wire::string_field(&mut device, 2, "cache");
        wire::string_field(&mut device, 3, "Pixel");
        let key = device_key(&device);
        assert_eq!(fields(&key).len(), 2, "only the device and cache ids");
        let add = download_request(&key, "spotify:album:b");
        let parsed = fields(&add);
        assert_eq!(first(&parsed, 2).map(|v| v.text()), Some("spotify:album:b".into()));
        assert_eq!(download_request(&[], "x"), vec![0x12, 1, b'x']);
        let ask = contexts_request(&key, &["a".into(), "b".into()]);
        assert_eq!(fields(&ask).iter().filter(|(f, _)| *f == 3).count(), 2);
    }

    #[test]
    fn errors_say_what_went_wrong() {
        assert_eq!(error_message(&[]), "");
        assert!(error_message(&[0x08, 8]).contains("space"));
        assert!(error_message(&[0x08, 23]).contains("Premium"));
        assert!(error_message(&[0x08, 99]).contains("99"));
    }

    #[test]
    fn totals_and_storage_decode() {
        let mut p = Vec::new();
        wire::number_field(&mut p, 3, 12);
        wire::number_field(&mut p, 5, 40);
        wire::number_field(&mut p, 7, 1);
        wire::number_field(&mut p, 10, 90);
        p.push(0x4d);
        p.extend_from_slice(&0.3f32.to_le_bytes());
        let mut reply = Vec::new();
        wire::bytes_field(&mut reply, 1, &p);
        let t = decode_total(&reply);
        assert_eq!((t.downloaded_tracks, t.total_tracks, t.downloading, t.seconds_left), (12, 40, true, 90));
        assert!((t.percent - 0.3).abs() < 1e-6);
        let mut stats = Vec::new();
        wire::number_field(&mut stats, 4, 500);
        wire::number_field(&mut stats, 5, 300);
        wire::number_field(&mut stats, 6, 7000);
        assert_eq!(decode_storage(&stats), Storage { current: 500, locked: 300, free: 7000, total: 0 });
    }

    #[test]
    fn only_a_change_of_state_changes_the_set() {
        let mut s = Shared::default();
        let d = |state, percent| Download { uri: "u".into(), state, percent };
        assert!(s.put(&d(State::Requested, 0.0)));
        assert!(s.put(&d(State::Downloading, 0.1)), "a new state");
        assert!(!s.put(&d(State::Downloading, 0.5)), "progress alone");
        assert!(s.put(&d(State::Downloaded, 1.0)));
        assert!(s.put(&d(State::NotDownloaded, 0.0)), "removed");
        assert!(!s.put(&d(State::NotDownloaded, 0.0)), "removing nothing");
    }
}
