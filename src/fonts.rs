//! Bundled fonts, unmodified official releases under the SIL OFL 1.1 (see theme/fonts/README.md).
//! Fonts are stored uncompressed so spatch.Fonts can map them instead of copying.

macro_rules! bundled {
    ($($name:literal),+ $(,)?) => {
        [$((concat!("assets/spatch-fonts/", $name), include_bytes!(concat!("../theme/fonts/", $name)).as_slice())),+]
    };
}

pub const FILES: [(&str, &[u8]); 14] = bundled![
    "roboto.ttf",
    "noto-sans.ttf",
    "atkinson-regular.ttf",
    "atkinson-bold.ttf",
    "monaspace-neon.ttf",
    "monaspace-argon.ttf",
    "monaspace-xenon.ttf",
    "monaspace-radon.ttf",
    "monaspace-krypton.ttf",
    "licenses/OFL-Roboto.txt",
    "licenses/OFL-NotoSans.txt",
    "licenses/OFL-Atkinson.txt",
    "licenses/OFL-Monaspace.txt",
    "README.md",
];

/// Added entries the runtime maps directly.
pub fn stored(name: &str) -> bool {
    name.starts_with("assets/spatch-fonts/") && name.ends_with(".ttf")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_font_is_sfnt_and_every_license_travels_with_it() {
        for (name, bytes) in FILES.iter().filter(|(n, _)| stored(n)) {
            assert_eq!(&bytes[..4], [0, 1, 0, 0], "{name}");
        }
        let licenses = FILES.iter().filter(|(n, _)| n.contains("/licenses/"));
        assert_eq!(licenses.clone().count(), 4);
        for (name, bytes) in licenses {
            let text = std::str::from_utf8(bytes).unwrap();
            assert!(text.contains("SIL OPEN FONT LICENSE Version 1.1"), "{name}");
        }
    }
}
