//! Material 3 roles from a Spatch theme's nine colours (look/Palette.java's BASE order: background,
//! surface, card, text, accent 2, secondary text, accent, light accent, accent 3), so the app wears
//! whichever theme Spatch has. Light themes keep the same roles with light values.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Roles {
    pub background: u32,
    pub surface_container: u32,
    pub surface_container_high: u32,
    pub surface_container_highest: u32,
    pub on_surface: u32,
    pub on_surface_variant: u32,
    pub primary: u32,
    pub on_primary: u32,
    pub primary_container: u32,
    pub on_primary_container: u32,
    pub secondary_container: u32,
    pub on_secondary_container: u32,
    pub outline: u32,
    pub outline_variant: u32,
}

/// `a` moved towards `b` by `t` (0..1), per channel; alpha opaque.
pub fn mix(a: u32, b: u32, t: f32) -> u32 {
    let ch = |c: u32, s: u32| (c >> s & 255) as f32;
    let m = |s: u32| ((ch(a, s) + (ch(b, s) - ch(a, s)) * t).round() as u32) << s;
    0xff00_0000 | m(16) | m(8) | m(0)
}

pub fn roles(theme: &[u32; 9]) -> Roles {
    let [bg, surface, card, text, _accent2, text2, accent, light_accent, _accent3] = *theme;
    Roles {
        background: bg,
        surface_container: surface,
        surface_container_high: card,
        surface_container_highest: mix(card, text, 0.08),
        on_surface: text,
        on_surface_variant: text2,
        primary: accent,
        on_primary: mix(bg, accent, 0.15),
        primary_container: mix(accent, bg, 0.6),
        on_primary_container: light_accent,
        secondary_container: mix(card, accent, 0.28),
        on_secondary_container: mix(text, accent, 0.2),
        outline: mix(text2, bg, 0.35),
        outline_variant: mix(text2, bg, 0.7),
    }
}

/// HSL (h in degrees, s and l 0..1) to opaque ARGB.
pub fn hsl(h: f32, s: f32, l: f32) -> u32 {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let hp = h.rem_euclid(360.0) / 60.0;
    let x = c * (1.0 - (hp % 2.0 - 1.0).abs());
    let (r, g, b) = match hp as u32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let m = l - c / 2.0;
    let to = |v: f32| (((v + m).clamp(0.0, 1.0)) * 255.0).round() as u32;
    0xff00_0000 | to(r) << 16 | to(g) << 8 | to(b)
}

/// Hue (degrees) and saturation (0..1) of an RGB colour.
pub fn hue_sat(argb: u32) -> (f32, f32) {
    let (r, g, b) = ((argb >> 16 & 255) as f32 / 255.0, (argb >> 8 & 255) as f32 / 255.0, (argb & 255) as f32 / 255.0);
    let (max, min) = (r.max(g).max(b), r.min(g).min(b));
    let (d, l) = (max - min, (max + min) / 2.0);
    if d < 1e-4 {
        return (0.0, 0.0);
    }
    let s = d / (1.0 - (2.0 * l - 1.0).abs()).max(1e-4);
    let h = if max == r { 60.0 * ((g - b) / d).rem_euclid(6.0) } else if max == g { 60.0 * ((b - r) / d + 2.0) } else { 60.0 * ((r - g) / d + 4.0) };
    (h, s.min(1.0))
}

/// The artwork's seed colour, as Pixel's player picks one: the average of its vivid pixels (dark,
/// pale and grey ones barely count), or None for a near-grey cover.
pub fn seed(rgba: &[u8]) -> Option<u32> {
    let (mut r, mut g, mut b, mut w) = (0f32, 0f32, 0f32, 0f32);
    for p in rgba.chunks_exact(4).step_by(7) {
        let (pr, pg, pb) = (p[0] as f32, p[1] as f32, p[2] as f32);
        let (max, min) = (pr.max(pg).max(pb), pr.min(pg).min(pb));
        let chroma = (max - min) / 255.0;
        let weight = chroma * chroma * (max / 255.0);
        r += pr * weight;
        g += pg * weight;
        b += pb * weight;
        w += weight;
    }
    if w < 1.0 {
        return None;
    }
    Some(0xff00_0000 | ((r / w) as u32) << 16 | ((g / w) as u32) << 8 | (b / w) as u32)
}

/// A dark theme's nine colours (the order `roles` takes) around a seed's hue: tinted near-black
/// surfaces, a light accent, and text with a touch of the hue.
pub fn from_seed(seed: u32) -> [u32; 9] {
    let (h, s) = hue_sat(seed);
    let tint = s.min(0.35);
    let vivid = s.clamp(0.35, 0.75);
    [
        hsl(h, tint * 0.5, 0.07),
        hsl(h, tint * 0.55, 0.11),
        hsl(h, tint * 0.6, 0.17),
        hsl(h, tint * 0.4, 0.93),
        hsl(h + 30.0, vivid * 0.7, 0.78),
        hsl(h, tint * 0.5, 0.78),
        hsl(h, vivid, 0.80),
        hsl(h, vivid, 0.90),
        hsl(h + 60.0, vivid * 0.7, 0.78),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seeds_follow_the_cover() {
        let red: Vec<u8> = [200u8, 30, 40, 255].repeat(64);
        let s = seed(&red).unwrap();
        let (h, sat) = hue_sat(s);
        assert!(!(10.0..350.0).contains(&h) && sat > 0.6, "red cover gives a red seed: {h} {sat}");
        assert_eq!(seed(&[128u8, 128, 128, 255].repeat(64)), None, "grey covers keep the theme");
        let theme = from_seed(s);
        let (accent_h, _) = hue_sat(theme[6]);
        assert!(!(10.0..350.0).contains(&accent_h), "the accent keeps the cover's hue");
        assert!((theme[0] >> 16 & 255) < 0x20, "the background stays dark");
    }

    #[test]
    fn hsl_round_trips_primaries() {
        assert_eq!(hsl(0.0, 1.0, 0.5), 0xffff0000);
        assert_eq!(hsl(120.0, 1.0, 0.5), 0xff00ff00);
        assert_eq!(hue_sat(0xff0000ff).0, 240.0);
    }

    #[test]
    fn mixes_per_channel() {
        assert_eq!(mix(0xff000000, 0xffffffff, 0.5), 0xff808080);
        assert_eq!(mix(0xff102030, 0xff102030, 0.7), 0xff102030);
    }

    #[test]
    fn lavender_keeps_its_accent_and_text() {
        let r = roles(&[0xff14121c, 0xff1d1a2a, 0xff2b2640, 0xfff3eeff, 0xffc6b8ff, 0xffbcb3dc, 0xffd0bcff, 0xffe0d2ff, 0xffb7a3ee]);
        assert_eq!((r.primary, r.on_surface, r.background), (0xffd0bcff, 0xfff3eeff, 0xff14121c));
        // the play button's icon must read on the accent: on_primary stays dark
        assert!((r.on_primary >> 16 & 255) < 0x40);
    }
}
