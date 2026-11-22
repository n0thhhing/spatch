//! The visualizer's analysis, from Android's Visualizer FFT of Spotify's audio session: band *amplitudes*
//! (linear, not dB) over one slow shared reference, so balance between bands survives and a hit still lands tall, then a soft shoulder; dB scaling flattens it into equal bars.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray};
use jni::sys::{jfloat, jint, jlong};
use std::sync::Mutex;

pub const BANDS: usize = 48;
const MIN_REFERENCE: f32 = 0.05;
const INITIAL_REFERENCE: f32 = 0.25;
const KNEE: f32 = 1.8;

pub struct Analyzer {
    /// 48 log-spaced bands from 40 Hz to 16 kHz, 0..1.
    pub bands: [f32; BANDS],
    /// 0..1, eased: the low end, the whole spectrum, and the last kick (decays to 0).
    pub bass: f32,
    pub level: f32,
    pub kick: f32,
    pub strongest: f32,
    pub reference: f32,
    average: f32,
    kick_at: i64,
}

impl Default for Analyzer {
    fn default() -> Self {
        Self::new()
    }
}

impl Analyzer {
    pub const fn new() -> Self {
        Analyzer { bands: [0.0; BANDS], bass: 0.0, level: 0.0, kick: 0.0, strongest: 0.0, reference: INITIAL_REFERENCE, average: 0.0, kick_at: 0 }
    }

    /// One capture: `fft` is Android's interleaved re/im bytes, `rate_hz` the sampling rate.
    pub fn step(&mut self, fft: &[i8], rate_hz: f32, dt_ms: f32, now_ms: i64) {
        let ease = |value: &mut f32, target: f32, rise: f32, fall: f32| {
            *value += (target - *value) * (1.0 - (-dt_ms / if target > *value { rise } else { fall }).exp());
        };
        let low = display_level(fft, rate_hz, 40.0, 160.0);
        let all = display_level(fft, rate_hz, 40.0, 12_000.0);
        ease(&mut self.bass, low, 18.0, 120.0);
        ease(&mut self.level, all, 30.0, 200.0);
        self.average += (low - self.average) * (1.0 - (-dt_ms / 900.0).exp());
        // A kick: the low end jumps well above its recent average, at most ~4 per second.
        if low > 0.18 && low > self.average * 1.32 && now_ms - self.kick_at > 240 {
            self.kick_at = now_ms;
            self.kick = ((low - self.average) * 3.0 + 0.35).min(1.0);
        }
        self.kick *= (-dt_ms / 260.0).exp();

        let seconds = dt_ms / 1000.0;
        let mut amplitude = [0.0f32; BANDS];
        for (i, a) in amplitude.iter_mut().enumerate() {
            let from = 40.0 * 400f32.powf(i as f32 / BANDS as f32);
            let to = 40.0 * 400f32.powf((i + 1) as f32 / BANDS as f32);
            *a = band_amplitude(fft, rate_hz, from, to);
        }
        self.strongest = amplitude.iter().copied().fold(0.0, f32::max);
        if self.strongest > 0.005 {
            let target = self.strongest.max(MIN_REFERENCE);
            let tc = if target > self.reference { 0.35 } else { 3.0 };
            self.reference += (target - self.reference) * (1.0 - (-seconds / tc).exp());
        }
        let attack = 1.0 - (-seconds / 0.008).exp();
        let release = 1.0 - (-seconds / 0.065).exp();
        for (band, a) in self.bands.iter_mut().zip(amplitude) {
            let relative = (a - 0.0001).max(0.0) / self.reference;
            let target = 1.0 - (-KNEE * relative.powf(0.85)).exp();
            *band += (target - *band) * if target > *band { attack } else { release };
            if *band < 0.0001 {
                *band = 0.0;
            }
        }
    }
}

/// The FFT bins between two frequencies (Hz): (first, last), at least one bin.
fn bins(len: usize, rate_hz: f32, from: f32, to: f32) -> Option<(usize, usize)> {
    if rate_hz <= 0.0 || len < 4 {
        return None;
    }
    let max = len / 2 - 1;
    let start = ((from * len as f32 / rate_hz).round() as usize).clamp(1, max);
    let end = ((to * len as f32 / rate_hz).round() as usize).max(start).min(max);
    Some((start, end))
}

fn power(fft: &[i8], start: usize, end: usize) -> f64 {
    (start..=end).map(|k| (fft[k * 2] as f64).powi(2) + (fft[k * 2 + 1] as f64).powi(2)).sum::<f64>() / (128.0 * 128.0)
}

/// Amplitude of a frequency range: the root of its summed power, 0..~1.
fn band_amplitude(fft: &[i8], rate_hz: f32, from: f32, to: f32) -> f32 {
    bins(fft.len(), rate_hz, from, to).map_or(0.0, |(s, e)| power(fft, s, e).sqrt() as f32)
}

/// A range's mean power as a 0..1 level on a dB scale (silence 0); used for the glow, not the bars.
fn display_level(fft: &[i8], rate_hz: f32, from: f32, to: f32) -> f32 {
    let Some((s, e)) = bins(fft.len(), rate_hz, from, to) else { return 0.0 };
    let mean = power(fft, s, e) / (e - s + 1) as f64;
    if mean <= 0.0 { 0.0 } else { (((10.0 * mean.log10() + 48.0) / 44.0) as f32).clamp(0.0, 1.0) }
}

/// `levels` spread over `per_side` bars with the bass given 46% of
/// them for 30% of the bands; a bar covering several bands shows the strongest, so a narrow peak survives.
pub fn bass_expanded(levels: &[f32], per_side: usize) -> Vec<f32> {
    if levels.is_empty() || per_side == 0 {
        return Vec::new();
    }
    let last = (levels.len() - 1) as f32;
    let positions: Vec<f32> = (0..per_side)
        .map(|i| {
            let p = if per_side == 1 { 0.0 } else { i as f32 / (per_side - 1) as f32 };
            let source = if p <= 0.46 { p / 0.46 * 0.30 } else { 0.30 + (p - 0.46) / 0.54 * 0.70 };
            source * last
        })
        .collect();
    let at = |pos: f32| {
        let lo = (pos.floor() as usize).min(levels.len() - 1);
        let hi = (lo + 1).min(levels.len() - 1);
        let t = pos - lo as f32;
        (levels[lo] * (1.0 - t) + levels[hi] * t).clamp(0.0, 1.0)
    };
    (0..per_side)
        .map(|i| {
            let pos = positions[i];
            let lower = if i == 0 { 0.0 } else { (positions[i - 1] + pos) / 2.0 };
            let upper = if i == per_side - 1 { last } else { (pos + positions[i + 1]) / 2.0 };
            if upper - lower <= 1.0 {
                return at(pos);
            }
            let inner = (lower.ceil() as usize..=upper.floor() as usize).map(|b| levels[b]);
            inner.fold(at(lower).max(at(upper)), f32::max).clamp(0.0, 1.0)
        })
        .collect()
}

static ANALYZER: Mutex<Analyzer> = Mutex::new(Analyzer::new());

/// Analyses one capture and writes bands, then bass, level, kick, strongest and reference, into `out` (53 floats).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_Audio_nativeAnalyse(
    env: JNIEnv, _: JClass, fft: JByteArray, rate_hz: jint, dt_ms: jfloat, now_ms: jlong, out: JFloatArray,
) {
    let Ok(len) = env.get_array_length(&fft) else { return };
    let mut bytes = vec![0i8; len as usize];
    if env.get_byte_array_region(&fft, 0, &mut bytes).is_err() {
        return;
    }
    let mut a = ANALYZER.lock().unwrap();
    a.step(&bytes, rate_hz as f32, dt_ms, now_ms);
    let mut values = a.bands.to_vec();
    values.extend([a.bass, a.level, a.kick, a.strongest, a.reference]);
    let _ = env.set_float_array_region(&out, 0, &values);
}

/// Back to silence and the starting reference, when capture stops.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_Audio_nativeReset(_: JNIEnv, _: JClass) {
    *ANALYZER.lock().unwrap() = Analyzer::new();
}

/// The ring's bars on one side (`per_side`), mapped from the latest bands; written into `out`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRing(env: JNIEnv, _: JClass, per_side: jint, out: JFloatArray) {
    let bars = bass_expanded(&ANALYZER.lock().unwrap().bands, per_side.max(0) as usize);
    let _ = env.set_float_array_region(&out, 0, &bars);
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A bin of `magnitude` at `hz`, in a 1024-byte capture at 44.1 kHz.
    fn tone(hz: f32, magnitude: i8) -> Vec<i8> {
        let mut fft = vec![0i8; 1024];
        let k = (hz * 1024.0 / 44_100.0).round() as usize;
        fft[k * 2] = magnitude;
        fft
    }

    #[test]
    fn a_bass_hit_lifts_the_bass_bars_and_not_the_treble() {
        let mut a = Analyzer::new();
        for i in 0..30 {
            a.step(&tone(60.0, 100), 44_100.0, 16.0, i * 16);
        }
        assert!(a.bands[3] > 0.6, "bass band {}", a.bands[3]);
        assert!(a.bands[44] < 0.01, "treble band {}", a.bands[44]);
        assert!(a.bass > 0.5 && a.kick >= 0.0);
    }

    #[test]
    fn silence_falls_back_to_zero() {
        let mut a = Analyzer::new();
        for i in 0..10 {
            a.step(&tone(60.0, 100), 44_100.0, 16.0, i * 16);
        }
        for i in 10..80 {
            a.step(&[0; 1024], 44_100.0, 16.0, i * 16);
        }
        assert!(a.bands.iter().all(|&b| b < 0.01));
    }

    #[test]
    fn bass_expanded_keeps_narrow_peaks_and_gives_bass_more_bars() {
        let mut levels = [0.0; BANDS];
        levels[40] = 0.9;
        let bars = bass_expanded(&levels, 36);
        assert_eq!(bars.len(), 36);
        assert!(bars.iter().any(|&b| b > 0.85), "the peak survived");
        // 46% of the bars read the first 30% of the bands.
        let mut bass = [0.0; BANDS];
        bass[..14].fill(1.0);
        let lit = bass_expanded(&bass, 36).iter().filter(|&&b| b > 0.5).count();
        assert!((15..=17).contains(&lit), "{lit} bars for the bass");
        assert!(bass_expanded(&[], 36).is_empty());
    }
}
