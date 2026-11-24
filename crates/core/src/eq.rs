//! EQ / DSP curve engine: zero-regex GraphicEQ parser, log-frequency interpolation,
//! ISO-226:2003 equal-loudness contours, and batched 10/64-band solver.

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{JNI_FALSE, jboolean, jfloat, jfloatArray};
use std::sync::OnceLock;

const EQ_HZ: [f64; 10] = [31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];

// ISO 226:2003 table (29 frequencies, 20 Hz .. 12.5 kHz)
const ISO_LEN: usize = 29;
const ISO_FREQS: [f64; ISO_LEN] = [
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
    200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
    2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
];
const ISO_ALPHA: [f64; ISO_LEN] = [
    0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
    0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244,
    0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301,
];
const ISO_LU: [f64; ISO_LEN] = [
    -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
    -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1,
    -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1,
];
const ISO_TF: [f64; ISO_LEN] = [
    78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
    14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
    -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
];
const ISO_1KHZ: usize = 17;
const REF_PHONS: f64 = 80.0;
const MIN_PHONS: f64 = 20.0;

struct Tables {
    eq_logs: [f64; 10],
    iso_logs: [f64; ISO_LEN],
    iso_tf_term: [f64; ISO_LEN],
    iso_ref_rel: [f64; ISO_LEN],
}

fn tables() -> &'static Tables {
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(|| {
        let eq_logs = EQ_HZ.map(f64::ln);
        let iso_logs = ISO_FREQS.map(f64::ln);
        let mut iso_tf_term = [0.0f64; ISO_LEN];
        let mut ref_spl = [0.0f64; ISO_LEN];
        let ref_phons_term = 4.47e-3 * (10.0f64.powf(0.025 * REF_PHONS) - 1.15);
        for i in 0..ISO_LEN {
            let term = (0.4 * 10.0f64.powf((ISO_TF[i] + ISO_LU[i]) / 10.0 - 9.0)).powf(ISO_ALPHA[i]);
            iso_tf_term[i] = term;
            ref_spl[i] = (10.0 / ISO_ALPHA[i]) * (ref_phons_term + term).log10() - ISO_LU[i] + 94.0;
        }
        let ref_1k = ref_spl[ISO_1KHZ];
        let mut iso_ref_rel = [0.0f64; ISO_LEN];
        for i in 0..ISO_LEN {
            iso_ref_rel[i] = ref_spl[i] - ref_1k;
        }
        Tables { eq_logs, iso_logs, iso_tf_term, iso_ref_rel }
    })
}

/// Computes the 29-point ISO-226 compensation curve once for a given `(volume, threshold)`.
fn iso226_table(volume: f32, threshold: f32, t: &Tables) -> Option<[f64; ISO_LEN]> {
    let thresh = threshold.clamp(0.0, 1.0);
    let vol = volume.clamp(0.0, 1.0);
    if thresh <= 0.001 || vol >= thresh {
        return None;
    }
    let ratio = vol / thresh;
    let current_phons = MIN_PHONS + (REF_PHONS - MIN_PHONS) * ratio as f64;
    let cur_phons_term = 4.47e-3 * (10.0f64.powf(0.025 * current_phons) - 1.15);
    let mut cur_spl = [0.0f64; ISO_LEN];
    for i in 0..ISO_LEN {
        cur_spl[i] = (10.0 / ISO_ALPHA[i]) * (cur_phons_term + t.iso_tf_term[i]).log10() - ISO_LU[i] + 94.0;
    }
    let cur_1k = cur_spl[ISO_1KHZ];
    let mut table = [0.0f64; ISO_LEN];
    for i in 0..ISO_LEN {
        table[i] = (cur_spl[i] - cur_1k) - t.iso_ref_rel[i];
    }
    Some(table)
}

#[inline]
fn iso226_from_table(hz: f64, log_hz: f64, table: &[f64; ISO_LEN], log_freqs: &[f64; ISO_LEN]) -> f32 {
    if hz <= ISO_FREQS[0] {
        return (table[0] as f32).clamp(-12.0, 18.0);
    }
    if hz >= ISO_FREQS[ISO_LEN - 1] {
        return (table[ISO_LEN - 1] as f32).clamp(-12.0, 18.0);
    }
    let i = ISO_FREQS.partition_point(|&f| f < hz);
    if i == 0 {
        return (table[0] as f32).clamp(-12.0, 18.0);
    }
    let frac = (log_hz - log_freqs[i - 1]) / (log_freqs[i] - log_freqs[i - 1]);
    ((table[i - 1] + frac * (table[i] - table[i - 1])) as f32).clamp(-12.0, 18.0)
}

pub fn iso226_db(hz: f32, volume: f32, threshold: f32) -> f32 {
    let t = tables();
    let Some(table) = iso226_table(volume, threshold, t) else { return 0.0 };
    let h = hz as f64;
    iso226_from_table(h, h.ln(), &table, &t.iso_logs)
}

/// Parses an AutoEQ / EqualizerAPO GraphicEQ profile with zero regexes.
/// Returns `(freqs, gains)` sorted and deduped, or `Err(message)` on invalid input.
pub fn parse_graphic_eq(raw: &str) -> Result<(Vec<f32>, Vec<f32>), &'static str> {
    if raw.len() > 256 * 1024 {
        return Err("GraphicEQ profile is too large");
    }
    let mut body = raw.trim();
    if body.is_empty() {
        return Err("GraphicEQ text is empty");
    }
    if body.len() >= 10 && body.as_bytes()[..10].eq_ignore_ascii_case(b"GraphicEQ:") {
        body = body[10..].trim();
    }

    let mut points: Vec<(f32, f32)> = Vec::with_capacity(128);
    for entry in body.split([';', '\r', '\n']) {
        let trimmed = entry.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = trimmed.split(|c: char| c.is_whitespace() || c == ',').filter(|s| !s.is_empty());
        let (Some(p0), Some(p1), None) = (parts.next(), parts.next(), parts.next()) else {
            return Err("Invalid GraphicEQ point");
        };
        let hz: f32 = p0.parse().map_err(|_| "Non-numeric GraphicEQ point")?;
        let db: f32 = p1.parse().map_err(|_| "Non-numeric GraphicEQ point")?;
        if !hz.is_finite() || hz <= 0.0 || hz > 48000.0 {
            return Err("Invalid frequency in GraphicEQ");
        }
        if !db.is_finite() || !(-36.0..=36.0).contains(&db) {
            return Err("Invalid gain in GraphicEQ");
        }
        points.push((hz, db));
    }
    if points.len() < 2 {
        return Err("GraphicEQ requires at least 2 points");
    }
    points.sort_by(|a, b| a.0.total_cmp(&b.0));

    let mut freqs: Vec<f32> = Vec::with_capacity(points.len());
    let mut gains: Vec<f32> = Vec::with_capacity(points.len());
    for (hz, db) in points {
        if let Some(&last_hz) = freqs.last() {
            if (last_hz - hz).abs() < 1e-4 {
                *gains.last_mut().unwrap() = db;
                continue;
            }
        }
        freqs.push(hz);
        gains.push(db);
    }
    if freqs.len() < 2 {
        return Err("GraphicEQ requires at least 2 distinct frequencies");
    }
    Ok((freqs, gains))
}

#[inline]
fn eval_curve(hz: f64, log_hz: f64, freqs: &[f32], log_freqs: &[f64], gains: &[f32]) -> f32 {
    if freqs.is_empty() {
        return 0.0;
    }
    if hz <= freqs[0] as f64 {
        return gains[0];
    }
    let last = freqs.len() - 1;
    if hz >= freqs[last] as f64 {
        return gains[last];
    }
    let i = freqs.partition_point(|&f| (f as f64) < hz);
    if i == 0 {
        return gains[0];
    }
    let t = (log_hz - log_freqs[i - 1]) / (log_freqs[i] - log_freqs[i - 1]);
    (gains[i - 1] as f64 + t * (gains[i] as f64 - gains[i - 1] as f64)) as f32
}

#[inline]
fn eval_geq10(hz: f64, log_hz: f64, gains: &[f32; 10], log_eq: &[f64; 10]) -> f32 {
    if hz <= EQ_HZ[0] {
        return gains[0];
    }
    if hz >= EQ_HZ[9] {
        return gains[9];
    }
    let i = EQ_HZ.partition_point(|&f| f < hz);
    if i == 0 {
        return gains[0];
    }
    let t = (log_hz - log_eq[i - 1]) / (log_eq[i] - log_eq[i - 1]);
    (gains[i - 1] as f64 + t * (gains[i] as f64 - gains[i - 1] as f64)) as f32
}

/// Evaluates all target EQ bands in one pass (GEQ + AutoEq + ISO-226) and returns `peak_eq_boost_db`.
#[allow(clippy::too_many_arguments)]
pub fn evaluate_bands(
    eq_hz: &[f32],
    geq_enabled: bool,
    geq_gains: Option<&[f32; 10]>,
    auto_freqs: Option<&[f32]>,
    auto_gains: Option<&[f32]>,
    auto_intensity: f32,
    loudness_enabled: bool,
    volume_fraction: f32,
    threshold_fraction: f32,
    out: &mut [f32],
) -> f32 {
    let t = tables();
    let auto_scale = auto_intensity.clamp(0.0, 200.0) / 100.0;
    let auto_logs: Vec<f64> = auto_freqs
        .map(|fs| fs.iter().map(|&f| (f as f64).ln()).collect())
        .unwrap_or_default();
    let iso = if loudness_enabled {
        iso226_table(volume_fraction, threshold_fraction, t)
    } else {
        None
    };

    let mut peak = 0.0f32;
    let count = eq_hz.len().min(out.len());
    for idx in 0..count {
        let hz = eq_hz[idx] as f64;
        let log_hz = hz.ln();
        let mut total = 0.0f32;
        if geq_enabled {
            if let Some(g) = geq_gains {
                total += eval_geq10(hz, log_hz, g, &t.eq_logs);
            }
        }
        if let (Some(af), Some(ag)) = (auto_freqs, auto_gains) {
            if af.len() == ag.len() && af.len() >= 2 {
                total += eval_curve(hz, log_hz, af, &auto_logs, ag) * auto_scale;
            }
        }
        if let Some(ref table) = iso {
            total += iso226_from_table(hz, log_hz, table, &t.iso_logs);
        }
        out[idx] = total;
        if total > peak {
            peak = total;
        }
    }
    peak
}

/// Parses GraphicEQ text into `[freqs..., gains...]` (`2 * n` floats), or null if invalid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_Audio_nativeParseGraphicEq(mut env: JNIEnv, _: JClass, raw: JString) -> jfloatArray {
    let Ok(text) = env.get_string(&raw).map(String::from) else { return std::ptr::null_mut() };
    let Ok((freqs, gains)) = parse_graphic_eq(&text) else { return std::ptr::null_mut() };
    let n = freqs.len();
    let mut flat = Vec::with_capacity(n * 2);
    flat.extend_from_slice(&freqs);
    flat.extend_from_slice(&gains);
    let Ok(out) = env.new_float_array(flat.len() as i32) else { return std::ptr::null_mut() };
    if env.set_float_array_region(&out, 0, &flat).is_err() {
        return std::ptr::null_mut();
    }
    out.into_raw()
}

/// Batched EQ solver: fills `outGains` with the combined gain per frequency and returns `peakEqBoostDb`.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_spatch_Audio_nativeEvaluateBands(
    env: JNIEnv,
    _: JClass,
    eq_hz: JFloatArray,
    geq_enabled: jboolean,
    geq_gains: JFloatArray,
    auto_freqs: JFloatArray,
    auto_gains: JFloatArray,
    auto_intensity: jfloat,
    loudness_enabled: jboolean,
    volume_fraction: jfloat,
    threshold_fraction: jfloat,
    out_gains: JFloatArray,
) -> jfloat {
    let Ok(n) = env.get_array_length(&eq_hz) else { return 0.0 };
    let n = n.max(0) as usize;
    let mut hz = vec![0.0f32; n];
    if env.get_float_array_region(&eq_hz, 0, &mut hz).is_err() {
        return 0.0;
    }

    let mut geq = [0.0f32; 10];
    let geq_ref = if geq_enabled != JNI_FALSE && !geq_gains.is_null()
        && env.get_array_length(&geq_gains).unwrap_or(0) >= 10
        && env.get_float_array_region(&geq_gains, 0, &mut geq).is_ok()
    {
        Some(&geq)
    } else {
        None
    };

    let (af_vec, ag_vec) = if !auto_freqs.is_null() && !auto_gains.is_null() {
        let an = env.get_array_length(&auto_freqs).unwrap_or(0).max(0) as usize;
        let bn = env.get_array_length(&auto_gains).unwrap_or(0).max(0) as usize;
        if an >= 2 && an == bn {
            let mut af = vec![0.0f32; an];
            let mut ag = vec![0.0f32; bn];
            if env.get_float_array_region(&auto_freqs, 0, &mut af).is_ok()
                && env.get_float_array_region(&auto_gains, 0, &mut ag).is_ok()
            {
                (Some(af), Some(ag))
            } else {
                (None, None)
            }
        } else {
            (None, None)
        }
    } else {
        (None, None)
    };

    let mut out = vec![0.0f32; n];
    let peak = evaluate_bands(
        &hz,
        geq_enabled != JNI_FALSE,
        geq_ref,
        af_vec.as_deref(),
        ag_vec.as_deref(),
        auto_intensity,
        loudness_enabled != JNI_FALSE,
        volume_fraction,
        threshold_fraction,
        &mut out,
    );
    let _ = env.set_float_array_region(&out_gains, 0, &out);
    peak
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn graphic_eq_parse_sort_dedup_and_reject() {
        let (f, g) = parse_graphic_eq("GraphicEQ: 1000 0.0; 20 -6.0; 20000 6.0; 100 3.0\n").unwrap();
        assert_eq!(f, vec![20.0, 100.0, 1000.0, 20000.0]);
        assert_eq!(g, vec![-6.0, 3.0, 0.0, 6.0]);

        let (df, dg) = parse_graphic_eq("20 -2.0; 20 -4.0; 200 2.0").unwrap();
        assert_eq!(df, vec![20.0, 200.0]);
        assert_eq!(dg, vec![-4.0, 2.0]);

        for bad in ["", "GraphicEQ:  ", "100 2.0", "0 1; 100 2", "-50 1; 100 2", "100 NaN; 200 1", "100 50; 200 1", "100 abc; 200 1", "100 1; 100 2"] {
            assert!(parse_graphic_eq(bad).is_err(), "expected error for {bad:?}");
        }
    }

    #[test]
    fn fallback_centres_require_their_own_headroom() {
        let mut gains = [0.0; 10];
        gains[5] = 12.0;
        let dense: Vec<f32> = (0..64).map(|i| 20.0 * 1000_f32.powf(i as f32 / 63.0)).collect();
        let mut dense_gains = [0.0; 64];
        let dense_peak = evaluate_bands(&dense, true, Some(&gains), None, None, 100.0, false, 1.0, 0.6, &mut dense_gains);
        let actual = [1000.0];
        let mut actual_gains = [0.0];
        let actual_peak = evaluate_bands(&actual, true, Some(&gains), None, None, 100.0, false, 1.0, 0.6, &mut actual_gains);
        assert!(actual_peak > dense_peak + 0.5);
        assert_eq!(actual_gains[0] - actual_peak, 0.0);
    }

    #[test]
    fn iso226_and_batch_eval_match_spec() {
        assert_eq!(iso226_db(40.0, 0.6, 0.6), 0.0);
        assert!(iso226_db(1000.0, 0.2, 0.6).abs() < 1e-4);
        assert!(iso226_db(40.0, 0.2, 0.6) > 3.0);
        let hz = [20.0, 100.0, 1000.0, 12500.0];
        let geq = [2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -2.0];
        let mut out = [0.0; 4];
        let peak = evaluate_bands(&hz, true, Some(&geq), None, None, 100.0, true, 0.2, 0.6, &mut out);
        assert!(peak >= out[0] && out[0] > 5.0);
    }
}
