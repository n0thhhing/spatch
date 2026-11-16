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

#[cfg(test)]
mod tests {
    use super::*;

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
