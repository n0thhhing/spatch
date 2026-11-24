//! Spatch's Rust side: typed access to Spotify's core and the data the Android views draw.
//!
//! `bridge` reaches the core through Java (spatch.CoreBridge), `spotify` speaks its services,
//! `ui` is what SpatchUi.java calls, `shapes` supplies icon and button geometry.

pub mod audio_tap;
pub mod autoeq;
pub mod bridge;
pub mod cache;
pub mod lyric_gen;
pub mod palette;
pub mod shapes;
pub mod spectrum;
pub mod spotify;
pub mod ui;

#[cfg(feature = "preview")]
mod demo;

/// Sends `log` lines to Android's log under the tag "Spatch" (adb logcat -s Spatch).
struct AndroidLog;

impl log::Log for AndroidLog {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record) {
        #[cfg(target_os = "android")]
        {
            unsafe extern "C" {
                fn __android_log_write(priority: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
            }
            // Android's priorities: 4 info, 5 warn, 6 error.
            let priority = match record.level() {
                log::Level::Error => 6,
                log::Level::Warn => 5,
                _ => 4,
            };
            let text = std::ffi::CString::new(record.args().to_string().replace('\0', " ")).unwrap_or_default();
            unsafe { __android_log_write(priority, c"Spatch".as_ptr(), text.as_ptr()) };
        }
        #[cfg(not(target_os = "android"))]
        let _ = record;
    }

    fn flush(&self) {}
}

/// Installs the Android log once; later calls do nothing.
pub fn init_log() {
    static LOG: AndroidLog = AndroidLog;
    if log::set_logger(&LOG).is_ok() {
        log::set_max_level(log::LevelFilter::Info);
    }
}

/// Types for Spotify's Esperanto protos (app/proto, rebuilt from Spotify's own classes).
#[allow(clippy::all, non_camel_case_types)]
pub mod protos {
    include!(concat!(env!("OUT_DIR"), "/protos.rs"));
}
