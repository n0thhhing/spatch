//! The app's colours: one seed (from the cover, or the chosen accent) turned into a dark or light palette.

use jni::JNIEnv;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jintArray};

/// bg, surf, surfHi, ink, inkDim, accent, accent2, onAccent as 0xAARRGGBB.
pub fn palette(seed: Option<u32>, dark: bool) -> [u32; 8] {
    let (h, c) = seed.map_or((262.0, 0.5), |s| {
        let (h, s, _) = hsv(s);
        (h, s.clamp(0.35, 0.85))
    });
    if dark {
        let a = (c + 0.1).min(0.9);
        // Mixed 12% toward white: Android's default focus highlight used to lay exactly that over the whole app,
        // and the app is known by that look; the highlight is now off, so the lift lives here instead.
        [hsl(h, 0.28 * c, 0.06), hsl(h, 0.22 * c, 0.115), hsl(h, 0.22 * c, 0.17), hsl(h, 0.2 * c, 0.94),
         hsl(h, 0.18 * c, 0.72), hsl(h, a, 0.74), hsl((h + 42.0) % 360.0, a, 0.70), hsl(h, 0.5, 0.13)]
            .map(|colour| toward_white(colour, 0.12))
    } else {
        let a = (c + 0.05).min(0.85);
        [hsl(h, 0.3 * c, 0.965), hsl(h, 0.3 * c, 0.925), hsl(h, 0.3 * c, 0.875), hsl(h, 0.25 * c, 0.10),
         hsl(h, 0.2 * c, 0.36), hsl(h, a, 0.40), hsl((h + 42.0) % 360.0, a, 0.46), hsl(h, 0.2, 0.98)]
    }
}

/// The cover's seed: the most colourful of its pixels (the cover scaled to a few pixels), not too dark or bright.
pub fn seed(pixels: &[u32]) -> Option<u32> {
    pixels.iter().copied().max_by(|a, b| score(*a).total_cmp(&score(*b)))
}

fn score(pixel: u32) -> f32 {
    let (_, s, v) = hsv(pixel);
    s * (1.0 - (v - 0.6).abs())
}

fn hsv(argb: u32) -> (f32, f32, f32) {
    let [r, g, b] = [16, 8, 0].map(|shift| ((argb >> shift) & 0xff) as f32 / 255.0);
    let max = r.max(g).max(b);
    let d = max - r.min(g).min(b);
    let h = if d == 0.0 {
        0.0
    } else if max == r {
        60.0 * ((g - b) / d).rem_euclid(6.0)
    } else if max == g {
        60.0 * ((b - r) / d + 2.0)
    } else {
        60.0 * ((r - g) / d + 4.0)
    };
    (h, if max == 0.0 { 0.0 } else { d / max }, max)
}

fn toward_white(argb: u32, amount: f32) -> u32 {
    let channel = |shift: u32| {
        let v = ((argb >> shift) & 0xff) as f32;
        ((v + (255.0 - v) * amount).round() as u32) << shift
    };
    0xff00_0000 | channel(16) | channel(8) | channel(0)
}

fn hsl(h: f32, s: f32, l: f32) -> u32 {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;
    let (r, g, b) = match h as u32 {
        0..60 => (c, x, 0.0),
        60..120 => (x, c, 0.0),
        120..180 => (0.0, c, x),
        180..240 => (0.0, x, c),
        240..300 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let byte = |v: f32| (((v + m) * 255.0).round().clamp(0.0, 255.0)) as u32;
    0xff00_0000 | byte(r) << 16 | byte(g) << 8 | byte(b)
}

/// The palette for a seed (0 = none) in dark or light.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativePalette(env: JNIEnv, _: JClass, seed: jint, has_seed: jboolean, dark: jboolean) -> jintArray {
    let colours = palette((has_seed != 0).then_some(seed as u32), dark != 0).map(|c| c as i32);
    let Ok(out) = env.new_int_array(8) else { return std::ptr::null_mut() };
    let _ = env.set_int_array_region(&out, 0, &colours);
    out.into_raw()
}

/// The seed colour among a cover's sampled pixels.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeSeed(env: JNIEnv, _: JClass, pixels: JIntArray) -> jint {
    let Ok(len) = env.get_array_length(&pixels) else { return 0 };
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(&pixels, 0, &mut values).is_err() {
        return 0;
    }
    seed(&values.iter().map(|&p| p as u32).collect::<Vec<_>>()).unwrap_or(0) as jint
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hsl_matches_known_colours() {
        assert_eq!(hsl(0.0, 1.0, 0.5), 0xffff0000);
        assert_eq!(hsl(120.0, 1.0, 0.5), 0xff00ff00);
        assert_eq!(hsl(240.0, 1.0, 0.25), 0xff000080);
        assert_eq!(hsl(0.0, 0.0, 1.0), 0xffffffff);
    }

    #[test]
    fn the_palette_keeps_the_seed_hue_and_flips_for_light() {
        let red = 0xffd6304a;
        let dark = palette(Some(red), true);
        let light = palette(Some(red), false);
        let lum = |c: u32| ((c >> 16 & 0xff) + (c >> 8 & 0xff) + (c & 0xff)) / 3;
        assert!((35..60).contains(&lum(dark[0])) && lum(dark[3]) > 220, "dark bg (with the 12% lift) / ink: {}", lum(dark[0]));
        // The true background measured from a screenshot (#120d0e) lifts to the familiar one (#2f2a2b), within rounding.
        let lifted = toward_white(0xff120d0e, 0.12);
        for (shift, want) in [(16, 0x2f), (8, 0x2a), (0, 0x2b)] {
            assert!(((lifted >> shift & 0xff) as i32 - want).abs() <= 1, "{lifted:x}");
        }
        assert!(lum(light[0]) > 230 && lum(light[3]) < 40, "light bg / ink");
        let (h, _, _) = hsv(dark[5]);
        assert!((h - hsv(red).0).abs() < 4.0, "accent keeps the hue: {h}");
        assert_eq!(palette(None, true), palette(None, true));
    }

    #[test]
    fn the_seed_is_the_most_colourful_pixel() {
        assert_eq!(seed(&[0xff808080, 0xff2060e0, 0xff101010, 0xfff0f0f0]), Some(0xff2060e0));
        assert_eq!(seed(&[]), None);
    }
}
