//! In-place colour swaps in base `resources.arsc`, mirroring the Compose palette edits in `dex`.
//!
//! A colour is an 8-byte `Res_value` {size=8, res0=0, dataType, data}; only literal ARGB8 (0x1c) and
//! RGB8 (0x1d) values are touched. Same size in, same size out, so no offsets or chunks move.

use crate::Result;
use crate::theme::Theme;

/// (original ARGB, role token ARGB mapped through the theme, expected occurrences across both data types).
pub const COLOR_SWAPS: [(u32, u32, usize); 7] = [
    (0xff121212, 0xff14121c, 12), // gray_7 / dark_base_background_base: page backgrounds
    (0xff1f1f1f, 0xff1d1a2a, 6),  // elevated base
    (0xff2a2a2a, 0xff2b2640, 6),  // elevated highlight
    (0xffb3b3b3, 0xffbcb3dc, 3),  // subdued text
    (0xff1ed760, 0xffd0bcff, 28), // Spotify green accent (also positive/"saved" states)
    (0xff1db954, 0xffd0bcff, 1),  // older brand green
    (0xff3be477, 0xffe0d2ff, 4),  // accent highlight
];

fn value(data_type: u8, argb: u32) -> [u8; 8] {
    let mut v = [8, 0, 0, data_type, 0, 0, 0, 0];
    v[4..].copy_from_slice(&argb.to_le_bytes());
    v
}

/// Returns a copy of `arsc` with every swap applied, failing if any count differs from the pinned one.
pub fn swap_colors(arsc: &[u8], theme: &Theme) -> Result<Vec<u8>> {
    let mut out = arsc.to_vec();
    for (from, to, expected) in COLOR_SWAPS {
        let mut found = 0;
        for data_type in [0x1c, 0x1d] {
            let (old, new) = (value(data_type, from), value(data_type, theme.map(to)));
            let mut i = 0;
            while let Some(pos) = out[i..].windows(8).position(|w| w == old) {
                out[i + pos..i + pos + 8].copy_from_slice(&new);
                i += pos + 8;
                found += 1;
            }
        }
        if found != expected {
            return Err(format!(
                "resources.arsc: colour {from:#010x} found {found} times, expected {expected}"
            )
            .into());
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn theme_colours_never_match_a_later_search() {
        // Swaps run in order; a themed value equal to an original colour would be swapped again.
        for t in &crate::theme::THEMES {
            for (from, _, _) in COLOR_SWAPS {
                assert!(!t.rgb.contains(&(from & 0xffffff)), "{} {from:#x}", t.name);
            }
        }
    }

    #[test]
    fn swaps_exact_counts_in_place() {
        let mut arsc = Vec::new();
        for (from, _, n) in COLOR_SWAPS {
            for k in 0..n {
                arsc.extend_from_slice(&value(if k % 2 == 0 { 0x1c } else { 0x1d }, from));
            }
        }
        let g = crate::theme::by_name("gruvbox").unwrap();
        let out = swap_colors(&arsc, g).unwrap();
        assert_eq!(out.len(), arsc.len());
        assert_eq!(&out[..8], &value(0x1c, 0xff282828));
        // A missing occurrence (other build or already patched) is rejected.
        assert!(swap_colors(&arsc[8..], g).is_err());
        assert!(swap_colors(&out, g).is_err());
    }
}
