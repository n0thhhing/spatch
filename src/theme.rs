//! Patch-time palettes. Every colour the patch writes is one of 9 roles; the sources under `theme/`
//! and the colour edits in `dex`/`arsc` are written with the lavender values, which act as role tokens
//! and are substituted with the selected theme's values.

use crate::Result;

pub struct Theme {
    pub name: &'static str,
    /// surface, elevated, highlight, text, secondary text, subdued text, accent, accent highlight,
    /// accent pressed (RGB). Accent highlight/pressed are the accent mixed 25% to white / 15% to black.
    pub rgb: [u32; 9],
}

/// The role tokens: the lavender theme, as written in `theme/` and the edit tables.
const TOKENS: [u32; 9] = THEMES[0].rgb;

/// Public palette values; secondary/subdued text pairs are checked for >= 4.5:1 contrast
/// (Dracula's subdued text is its comment colour lightened halfway to its foreground for that reason).
pub const THEMES: [Theme; 18] = [
    theme(
        "lavender",
        [
            0x14121c, 0x1d1a2a, 0x2b2640, 0xf3eeff, 0xc6b8ff, 0xbcb3dc, 0xd0bcff, 0xe0d2ff,
            0xb7a3ee,
        ],
    ),
    theme(
        "gruvbox",
        [
            0x282828, 0x3c3836, 0x504945, 0xfbf1c7, 0xd5c4a1, 0xa89984, 0xfe8019, 0xfea052,
            0xd86d15,
        ],
    ),
    theme(
        "catppuccin-mocha",
        [
            0x1e1e2e, 0x313244, 0x45475a, 0xcdd6f4, 0xf5c2e7, 0xa6adc8, 0xcba6f7, 0xd8bcf9,
            0xad8dd2,
        ],
    ),
    theme(
        "rose-pine",
        [
            0x191724, 0x1f1d2e, 0x26233a, 0xe0def4, 0xebbcba, 0x908caa, 0xc4a7e7, 0xd3bded,
            0xa78ec4,
        ],
    ),
    theme(
        "nord",
        [
            0x2e3440, 0x3b4252, 0x434c5e, 0xeceff4, 0x8fbcbb, 0xd8dee9, 0x88c0d0, 0xa6d0dc,
            0x74a3b1,
        ],
    ),
    theme(
        "tokyo-night",
        [
            0x1a1b26, 0x24283b, 0x292e42, 0xc0caf5, 0xbb9af7, 0x9aa5ce, 0x7aa2f7, 0x9bb9f9,
            0x688ad2,
        ],
    ),
    theme(
        "dracula",
        [
            0x282a36, 0x343746, 0x44475a, 0xf8f8f2, 0xff79c6, 0xadb5cb, 0xbd93f9, 0xceaefa,
            0xa17dd4,
        ],
    ),
    // Gruvbox Material (github.com/sainnhe/gruvbox-material, MIT, dark medium): bg0, bg1, bg3, fg1,
    // fg0, grey2, green, then green mixed like the other themes' accent roles.
    theme(
        "gruvbox-material-dark",
        [
            0x282828, 0x32302f, 0x45403d, 0xddc7a1, 0xd4be98, 0xa89984, 0xa9b665, 0xbec88b,
            0x8f9a55,
        ],
    ),
    theme(
        "default",
        [
            0x19191b, 0x232326, 0x2e2e31, 0xececee, 0xa9a9ae, 0x818187, 0xececed, 0xf1f1f2,
            0xc9c9ca,
        ],
    ),
    theme(
        "spotify",
        [
            0x121212, 0x1f1f1f, 0x2a2a2a, 0xffffff, 0xb3b3b3, 0x7d7d7d, 0x1ed760, 0x56e188,
            0x1ab752,
        ],
    ),
    theme(
        "catppuccin-macchiato",
        [
            0x24273a, 0x363a4f, 0x494d64, 0xcad3f5, 0xa5adcb, 0x888ea8, 0xc6a0f6, 0xd4b8f8,
            0xa888d1,
        ],
    ),
    theme(
        "catppuccin-frappe",
        [
            0x303446, 0x414559, 0x51576d, 0xc6d0f5, 0xa5adce, 0x969cb4, 0xca9ee6, 0xd7b6ec,
            0xac86c4,
        ],
    ),
    theme(
        "rose-pine-moon",
        [
            0x2a273f, 0x393552, 0x44415a, 0xe0def4, 0x918dab, 0x918ea3, 0xc4a7e7, 0xd3bded,
            0xa78ec4,
        ],
    ),
    theme(
        "tokyo-night-storm",
        [
            0x24283b, 0x292e42, 0x3b4261, 0xc0caf5, 0xa9b1d6, 0x898fac, 0x7aa2f7, 0x9bb9f9,
            0x688ad2,
        ],
    ),
    theme(
        "everforest",
        [
            0x2d353b, 0x3d484d, 0x475258, 0xd3c6aa, 0x9da9a0, 0x949f97, 0xa7c080, 0xbdd0a0,
            0x8ea36d,
        ],
    ),
    theme(
        "kanagawa",
        [
            0x1f1f28, 0x2a2a37, 0x363646, 0xdcd7ba, 0xc8c093, 0x898881, 0x7e9cd8, 0x9eb5e2,
            0x6b85b8,
        ],
    ),
    theme(
        "solarized",
        [
            0x002b36, 0x073642, 0x0d4452, 0x9fabab, 0x839496, 0x809196, 0x3794d6, 0x69afe0,
            0x2f7eb6,
        ],
    ),
    theme(
        "one",
        [
            0x282c34, 0x2c313a, 0x3e4451, 0xabb2bf, 0x9da5b4, 0x8f939c, 0x61afef, 0x88c3f3,
            0x5295cb,
        ],
    ),
];

/// Light counterparts use the same families as the local Swiftify palette catalogue.
pub const LIGHT: [[u32; 9]; THEMES.len()] = [
    [
        0xfffbfe, 0xf3edf7, 0xe8def8, 0x1d1b20, 0x49454f, 0x625b71, 0x6750a4, 0x8d76bc, 0x57448b,
    ],
    [
        0xfbf1c7, 0xebdbb2, 0xd5c4a1, 0x3c3836, 0x776b60, 0x786b5f, 0xaf3a03, 0xc36b42, 0x953103,
    ],
    [
        0xeff1f5, 0xdce0e8, 0xccd0da, 0x4c4f69, 0x6a6d82, 0x6a6d7a, 0x8839ef, 0xa66af3, 0x7430cb,
    ],
    [
        0xfffaf3, 0xf2e9e1, 0xdfdad9, 0x575279, 0x74708d, 0x75717f, 0x7f6b95, 0x9f90b0, 0x6c5b7f,
    ],
    [
        0xeceff4, 0xd8dee9, 0xc8d0dc, 0x2e3440, 0x4c566a, 0x666d7b, 0x506e92, 0x7c92ad, 0x445e7c,
    ],
    [
        0xe1e2e7, 0xc4c8da, 0xb7bcd2, 0x2a4992, 0x536297, 0x5e6381, 0x2463b8, 0x5b8aca, 0x1f549c,
    ],
    [
        0xfffbeb, 0xe8e4d0, 0xcfcfde, 0x1f1f1f, 0x45423a, 0x6c664b, 0x644ac9, 0x8b77d6, 0x553fab,
    ],
    [
        0xfbf1c7, 0xebdbb2, 0xd5c4a1, 0x3c3836, 0x776b60, 0x786b5f, 0xaf3a03, 0xc36b42, 0x953103,
    ],
    [
        0xffffff, 0xebebec, 0xe1e1e3, 0x1b1b1d, 0x5c5c61, 0x75757a, 0x1b1b1d, 0x545456, 0x171719,
    ],
    [
        0xffffff, 0xf6f6f6, 0xededed, 0x121212, 0x6a6a6a, 0x757575, 0x16873d, 0x50a56e, 0x137334,
    ],
    [
        0xeff1f5, 0xdce0e8, 0xccd0da, 0x4c4f69, 0x6a6d82, 0x6a6d7a, 0x8839ef, 0xa66af3, 0x7430cb,
    ],
    [
        0xeff1f5, 0xdce0e8, 0xccd0da, 0x4c4f69, 0x6a6d82, 0x6a6d7a, 0x8839ef, 0xa66af3, 0x7430cb,
    ],
    [
        0xfffaf3, 0xf2e9e1, 0xdfdad9, 0x575279, 0x74708d, 0x75717f, 0x7f6b95, 0x9f90b0, 0x6c5b7f,
    ],
    [
        0xe1e2e7, 0xc4c8da, 0xb7bcd2, 0x2a4992, 0x536297, 0x5e6381, 0x2463b8, 0x5b8aca, 0x1f549c,
    ],
    [
        0xfdf6e3, 0xe6e2cc, 0xe0dcc7, 0x556269, 0x687467, 0x6a7268, 0x687701, 0x8e9940, 0x586501,
    ],
    [
        0xf2ecbc, 0xe7dba0, 0xe4d794, 0x545464, 0x6c6a5d, 0x6a6963, 0x4d699b, 0x7a8eb4, 0x415984,
    ],
    [
        0xfdf6e3, 0xe6dfcb, 0xddd6c1, 0x073642, 0x586e75, 0x687272, 0x2076b2, 0x5898c5, 0x1b6497,
    ],
    [
        0xfafafa, 0xe5e5e6, 0xdbdbdc, 0x383a42, 0x696c77, 0x727277, 0x3a6ddc, 0x6b92e5, 0x315dbb,
    ],
];

const fn theme(name: &'static str, rgb: [u32; 9]) -> Theme {
    Theme { name, rgb }
}

pub fn by_name(name: &str) -> Result<&'static Theme> {
    THEMES.iter().find(|t| t.name == name).ok_or_else(|| {
        let names: Vec<_> = THEMES.iter().map(|t| t.name).collect();
        format!("unknown theme {name:?}; available: {}", names.join(", ")).into()
    })
}

impl Theme {
    /// Maps a token RGB (alpha in the top byte is kept) to this theme; other values pass through.
    pub fn map(&self, argb: u32) -> u32 {
        match TOKENS.iter().position(|&t| t == argb & 0xffffff) {
            Some(i) => (argb & 0xff00_0000) | self.rgb[i],
            None => argb,
        }
    }

    /// Replaces every 6-hex-digit token (any case) in `text` in one pass, so replacements are never
    /// re-matched.
    pub fn apply(&self, text: &str) -> String {
        let b = text.as_bytes();
        let mut out = String::with_capacity(text.len());
        let mut i = 0;
        while i < b.len() {
            let hit = b.get(i..i + 6).and_then(|w| {
                let w = std::str::from_utf8(w).ok()?;
                let v = u32::from_str_radix(w, 16).ok()?;
                let pos = TOKENS.iter().position(|&t| t == v)?;
                Some(format!("{:06x}", self.rgb[pos]))
            });
            match hit {
                Some(rep) => {
                    out.push_str(&rep);
                    i += 6;
                }
                None => {
                    let c = text[i..].chars().next().unwrap();
                    out.push(c);
                    i += c.len_utf8();
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn substitutes_tokens_keeping_alpha_and_other_text() {
        let g = by_name("gruvbox").unwrap();
        assert_eq!(
            g.apply(r##"color="#eb1d1a2a" x="#D0BCFF" keep="#ff1ed760" 0xff14121cL"##),
            r##"color="#eb3c3836" x="#fe8019" keep="#ff1ed760" 0xff282828L"##
        );
        assert_eq!(g.map(0xffd0bcff), 0xfffe8019);
        assert_eq!(g.map(0x12345678), 0x12345678);
        assert_eq!(by_name("lavender").unwrap().apply("#ff14121c"), "#ff14121c");
        assert!(by_name("nope").is_err());
    }

    fn luminance(rgb: u32) -> f64 {
        let channel = |shift: u32| {
            let v = ((rgb >> shift) & 255) as f64 / 255.0;
            if v <= 0.03928 {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fn contrast(a: u32, b: u32) -> f64 {
        let (a, b) = (luminance(a), luminance(b));
        (a.max(b) + 0.05) / (a.min(b) + 0.05)
    }

    #[test]
    fn home_filter_chips_are_readable_in_every_theme() {
        for t in &THEMES {
            let [surface, _, high, text, _, _, accent, ..] = t.rgb;
            assert!(contrast(text, high) >= 4.5, "{} unselected", t.name);
            assert!(contrast(surface, accent) >= 4.5, "{} selected", t.name);
        }
    }

    #[test]
    fn light_palettes_keep_text_and_controls_readable() {
        for (theme, row) in THEMES.iter().zip(LIGHT) {
            assert!(contrast(row[3], row[2]) >= 4.5, "{} light text", theme.name);
            assert!(
                contrast(row[0], row[6]) >= 4.5,
                "{} light accent",
                theme.name
            );
        }
    }

    #[test]
    fn theme_values_are_distinct_from_tokens_of_other_roles() {
        // One-pass substitution cannot chain, but a theme reusing another role's token would make
        // `map` ambiguous for already-themed values; keep palettes free of that.
        for t in &THEMES[1..] {
            assert_eq!(
                t.rgb.iter().collect::<std::collections::HashSet<_>>().len(),
                9,
                "{} reverses unambiguously into the runtime template",
                t.name
            );
            for (i, v) in t.rgb.iter().enumerate() {
                assert!(
                    TOKENS
                        .iter()
                        .enumerate()
                        .all(|(j, &tok)| i == j || tok != *v),
                    "{}",
                    t.name
                );
            }
        }
    }
}
