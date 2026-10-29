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
pub const THEMES: [Theme; 7] = [
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

    #[test]
    fn theme_values_are_distinct_from_tokens_of_other_roles() {
        // One-pass substitution cannot chain, but a theme reusing another role's token would make
        // `map` ambiguous for already-themed values; keep palettes free of that.
        for t in &THEMES[1..] {
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
