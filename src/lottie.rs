//! Spotify green baked into Lottie animations (`res/raw/*.json`: save/heart buttons, play indicators,
//! download status, …) mapped to the theme accent. Raw resources ship byte-for-byte, so the original
//! entries are rewritten directly; no recompilation is involved.

use crate::Result;
use crate::input;
use crate::theme::Theme;
use std::path::Path;

/// Every base animation that contains Spotify green (inventory of the supported build); each must match.
pub const FILES: [&str; 21] = [
    "res/raw/auto_invite_on_loop.json",
    "res/raw/auto_invite_on_start.json",
    "res/raw/bell_notification_positive.json",
    "res/raw/bell_notification_undo.json",
    "res/raw/download_status_downloaded_to_downloadable.json",
    "res/raw/download_status_downloading.json",
    "res/raw/download_status_downloading_infinite.json",
    "res/raw/download_status_downloading_to_downloadable.json",
    "res/raw/download_status_downloading_to_downloaded.json",
    "res/raw/download_status_downloading_to_error.json",
    "res/raw/heart_positive_white.json",
    "res/raw/heart_undo_white.json",
    "res/raw/play_indicator_paused_to_playing.json",
    "res/raw/play_indicator_playing.json",
    "res/raw/play_indicator_playing_to_paused.json",
    "res/raw/save_now_positive_gray65.json",
    "res/raw/save_now_positive_white.json",
    "res/raw/save_now_undo_gray65.json",
    "res/raw/save_now_undo_white.json",
    "res/raw/stopwatch_activate.json",
    "res/raw/stopwatch_deactivate.json",
];

/// Spotify greens as they appear (0-1 floats at varying precision).
const GREENS: [u32; 2] = [0x1ed760, 0x1db954];
const ACCENT_TOKEN: u32 = 0xd0bcff;

pub fn patch_all(base: &Path, theme: &Theme) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let accent = theme.map(ACCENT_TOKEN) & 0xffffff;
    let mut out = Vec::new();
    for (f, bytes) in FILES.into_iter().zip(input::read_entries(base, &FILES)?) {
        let text = String::from_utf8(bytes)?;
        let (new, n) = recolor(&text, accent);
        if n == 0 {
            return Err(format!("{f}: no Spotify green found; not the supported build").into());
        }
        out.push((f, new.into_bytes()));
    }
    Ok(out)
}

fn channels(rgb: u32) -> [f64; 3] {
    [16, 8, 0].map(|s| ((rgb >> s) & 0xff) as f64 / 255.0)
}

/// Rewrites every `[r,g,b(,a)]` array whose RGB is within 0.004 of a Spotify green; returns the count.
pub fn recolor(json: &str, accent: u32) -> (String, usize) {
    let new: String = channels(accent).map(|c| format!("{c:.6}")).join(",");
    let mut out = String::with_capacity(json.len());
    let (mut rest, mut count) = (json, 0);
    while let Some(i) = rest.find('[') {
        out.push_str(&rest[..=i]);
        rest = &rest[i + 1..];
        // The first three comma-separated numbers after '['.
        let mut end = 0;
        let mut nums = Vec::new();
        for _ in 0..3 {
            let part = &rest[end..];
            let len = part
                .find(|c: char| !(c.is_ascii_digit() || c == '.' || c == '-' || c == 'e'))
                .unwrap_or(part.len());
            match part[..len].parse::<f64>() {
                Ok(v) => nums.push(v),
                Err(_) => break,
            }
            end += len;
            if nums.len() < 3 {
                if !rest[end..].starts_with(',') {
                    break;
                }
                end += 1;
            }
        }
        let is_green = nums.len() == 3
            && GREENS.iter().any(|&g| {
                channels(g)
                    .iter()
                    .zip(&nums)
                    .all(|(a, b)| (a - b).abs() < 0.004)
            });
        if is_green {
            out.push_str(&new);
            rest = &rest[end..];
            count += 1;
        }
    }
    out.push_str(rest);
    (out, count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recolors_green_arrays_only() {
        let src = r#"{"k":[0.117647058824,0.843137254902,0.376470588235,1],"o":[0.1137254,0.72549021244,0.3294117],"w":[1,1,1,1],"p":[0.5,0.2]}"#;
        let (out, n) = recolor(src, 0xfe8019);
        assert_eq!(n, 2);
        assert_eq!(
            out,
            r#"{"k":[0.996078,0.501961,0.098039,1],"o":[0.996078,0.501961,0.098039],"w":[1,1,1,1],"p":[0.5,0.2]}"#
        );
        assert_eq!(recolor("no arrays [", 0).1, 0);
    }
}
