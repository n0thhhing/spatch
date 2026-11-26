//! Experimental lyric generation: keeps the last ~40 s of the song's own audio (from the AAudio tap in `audio_tap`)
//! and runs whisper.cpp over a window of it, so songs no service has lyrics for can get rough, timed ones.
//! Java (spatch.LyricGen) decides which windows to take and maps their times onto the song.

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloatArray, jint, jlong, jstring};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering};

/// Whisper's input rate.
pub const RATE_OUT: i32 = 16_000;
/// Capture length in frames (a power of two): about 43 s at 48 kHz, room for a 28 s window plus the time to transcribe it.
const CAP_N: usize = 1 << 21;

pub static CAPTURE_ON: AtomicBool = AtomicBool::new(false);
static BUF: OnceLock<Box<[AtomicU32]>> = OnceLock::new();
/// Frames written since the start, never wrapping: Java names windows by these.
static FRAMES: AtomicU64 = AtomicU64::new(0);
static RATE: AtomicI32 = AtomicI32::new(48_000);

/// Called from the audio thread for each callback while capture is on: mono samples into the ring, no locks or allocation.
pub fn push(frames: usize, rate: i32, mut mono_at: impl FnMut(usize) -> f32) {
    let Some(buf) = BUF.get() else { return };
    let skip = frames.saturating_sub(CAP_N);
    let start = FRAMES.load(Ordering::Relaxed);
    for (n, frame) in (skip..frames).enumerate() {
        buf[((start + n as u64) as usize) & (CAP_N - 1)].store(mono_at(frame).to_bits(), Ordering::Relaxed);
    }
    if rate > 0 {
        RATE.store(rate, Ordering::Relaxed);
    }
    FRAMES.store(start + (frames - skip) as u64, Ordering::Release);
}

/// Frames `from..to` resampled to 16 kHz mono, or None once the ring has already overwritten part of them.
fn read(from: u64, to: u64) -> Option<Vec<f32>> {
    let buf = BUF.get()?;
    let written = FRAMES.load(Ordering::Acquire);
    if to <= from || to > written || written - from > CAP_N as u64 {
        return None;
    }
    let rate = RATE.load(Ordering::Relaxed).max(8_000) as f64;
    let at = |i: u64| f32::from_bits(buf[(i as usize) & (CAP_N - 1)].load(Ordering::Relaxed));
    let step = rate / RATE_OUT as f64;
    let n = ((to - from) as f64 / step) as usize;
    let mut out = Vec::with_capacity(n);
    for k in 0..n {
        let pos = k as f64 * step;
        let i = pos as u64;
        let frac = (pos - i as f64) as f32;
        let (a, b) = (at(from + i), at((from + i + 1).min(to - 1)));
        out.push(a + (b - a) * frac);
    }
    // The ring may have moved on while copying: drop the window rather than hand Whisper mixed audio.
    if FRAMES.load(Ordering::Acquire) - from > CAP_N as u64 {
        return None;
    }
    Some(out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_LyricGen_nativeCapture(_: JNIEnv, _: JClass, on: jboolean) {
    if on != 0 {
        BUF.get_or_init(|| (0..CAP_N).map(|_| AtomicU32::new(0)).collect());
        crate::audio_tap::install();
    } else {
        whisper::release();
    }
    CAPTURE_ON.store(on != 0, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_LyricGen_nativeFrames(_: JNIEnv, _: JClass) -> jlong {
    FRAMES.load(Ordering::Acquire) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_LyricGen_nativeRate(_: JNIEnv, _: JClass) -> jint {
    RATE.load(Ordering::Relaxed)
}

/// The window as 16 kHz samples (null if it was overwritten).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_LyricGen_nativeRead(env: JNIEnv, _: JClass, from: jlong, to: jlong) -> jfloatArray {
    let Some(pcm) = read(from.max(0) as u64, to.max(0) as u64) else { return std::ptr::null_mut() };
    let Ok(array) = env.new_float_array(pcm.len() as i32) else { return std::ptr::null_mut() };
    if env.set_float_array_region(&array, 0, &pcm).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Transcribes 16 kHz samples with the model at `model`: one "startMs\tendMs\ttext" line per segment, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_LyricGen_nativeTranscribe(
    mut env: JNIEnv, _: JClass, model: JString, pcm: JFloatArray,
) -> jstring {
    let Ok(model) = env.get_string(&model).map(String::from) else { return std::ptr::null_mut() };
    let Ok(len) = env.get_array_length(&pcm) else { return std::ptr::null_mut() };
    let mut samples = vec![0f32; len as usize];
    if env.get_float_array_region(&pcm, 0, &mut samples).is_err() {
        return std::ptr::null_mut();
    }
    match whisper::transcribe(&model, &samples) {
        Some(text) => env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
mod whisper {
    use std::sync::Mutex;
    use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

    /// The loaded model, kept between windows: loading it takes about a second.
    static CONTEXT: Mutex<Option<(String, WhisperContext)>> = Mutex::new(None);

    pub fn release() {
        if let Ok(mut guard) = CONTEXT.lock() {
            *guard = None;
        }
    }

    pub fn transcribe(model: &str, samples: &[f32]) -> Option<String> {
        let mut guard = CONTEXT.lock().ok()?;
        if guard.as_ref().is_none_or(|(path, _)| path != model) {
            let ctx = WhisperContext::new_with_params(model, WhisperContextParameters::default())
                .map_err(|e| log::warn!("lyric_gen: model failed to load: {e}"))
                .ok()?;
            *guard = Some((model.to_string(), ctx));
        }
        let (_, ctx) = guard.as_ref()?;
        let mut state = ctx.create_state().ok()?;
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_language(Some("auto"));
        params.set_n_threads(4);
        params.set_no_context(true); // windows are independent; carrying text over makes Whisper repeat itself
        params.set_suppress_nst(true); // no "[Music]" or "(instrumental)" lines
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_special(false);
        params.set_print_timestamps(false);
        state.full(params, samples).map_err(|e| log::warn!("lyric_gen: transcription failed: {e}")).ok()?;
        let mut out = String::new();
        for i in 0..state.full_n_segments() {
            let Some(segment) = state.get_segment(i) else { continue };
            let Ok(text) = segment.to_str_lossy() else { continue };
            let text = text.trim().replace(['\t', '\n'], " ");
            if text.is_empty() {
                continue;
            }
            // Whisper's times are in centiseconds from the window's start.
            out.push_str(&format!("{}\t{}\t{}\n", segment.start_timestamp() * 10, segment.end_timestamp() * 10, text));
        }
        Some(out)
    }
}

#[cfg(not(target_os = "android"))]
mod whisper {
    /// Off the phone (the desktop preview) there is no model: nothing is generated.
    pub fn transcribe(_: &str, _: &[f32]) -> Option<String> {
        None
    }

    pub fn release() {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_resamples_to_16k_and_refuses_overwritten_audio() {
        BUF.get_or_init(|| (0..CAP_N).map(|_| AtomicU32::new(0)).collect());
        let start = FRAMES.load(Ordering::Acquire);
        push(48_000, 48_000, |f| f as f32 / 48_000.0); // one second of a ramp
        let pcm = read(start, start + 48_000).unwrap();
        assert_eq!(pcm.len(), 16_000);
        assert!((pcm[8_000] - 0.5).abs() < 1e-3, "resampling keeps the ramp's shape");
        assert!(read(start, start + 48_001).is_none(), "nothing past what was written");
        for _ in 0..(CAP_N / 48_000 + 1) {
            push(48_000, 48_000, |_| 0.0);
        }
        assert!(read(start, start + 48_000).is_none(), "overwritten audio is refused");
    }
}
