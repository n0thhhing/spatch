//! Spatch's own names for what changes between Spotify builds. Code, stubs and smali edits write
//! `S_<alias>` for an obfuscated class and `R_<type>_<name>` for a Spotify resource; before anything
//! is compiled or edited, `rewrite` swaps in the class's name (from `scripts/port/aliases.tsv`, a column per
//! build, filled by `scripts/port/port.py`) and the resource's ID (read from the build's own table).

use crate::Result;
use crate::input::VERSION_NAME;
use std::collections::HashMap;
use std::sync::OnceLock;

const ALIASES: &str = include_str!("../scripts/port/aliases.tsv");
const TYPES: [&str; 13] = [
    "anim", "array", "attr", "bool", "color", "dimen", "drawable", "font", "id", "integer",
    "layout", "string", "style",
];

static RESOURCES: std::sync::Mutex<Option<HashMap<String, String>>> = std::sync::Mutex::new(None);

/// Takes the build's resource IDs from `aapt2 dump resources` of its base.apk.
pub fn load_resources(dump: &str) {
    let ids: Vec<(String, String)> = dump
        .lines()
        .filter_map(|l| {
            let mut words = l.trim().strip_prefix("resource ")?.split_whitespace();
            let id = words.next()?;
            let (ty, name) = words.next()?.split_once('/')?;
            Some((format!("R_{ty}_{name}"), id.to_string()))
        })
        .collect();
    RESOURCES
        .lock()
        .unwrap()
        .get_or_insert_with(HashMap::new)
        .extend(ids);
}

fn resource(token: &str) -> Result<String> {
    RESOURCES
        .lock()
        .unwrap()
        .as_ref()
        .ok_or("resource IDs are not loaded")?
        .get(token)
        .cloned()
        .ok_or_else(|| format!("{token}: no such resource in this Spotify build").into())
}

/// alias -> (smali folder, class) in the supported build.
fn table() -> &'static Parsed<'static> {
    static TABLE: OnceLock<Parsed<'static>> = OnceLock::new();
    TABLE.get_or_init(|| parse(ALIASES, VERSION_NAME))
}

type Parsed<'a> = std::result::Result<HashMap<&'a str, (&'a str, &'a str)>, String>;

fn parse<'a>(text: &'a str, build: &str) -> Parsed<'a> {
    let mut rows = text
        .lines()
        .filter(|l| !l.is_empty() && !l.starts_with('#'));
    let header: Vec<&str> = rows
        .next()
        .ok_or("scripts/port/aliases.tsv is empty")?
        .split('\t')
        .collect();
    let column = header
        .iter()
        .position(|h| *h == build)
        .ok_or_else(|| format!("scripts/port/aliases.tsv has no column for Spotify {build}"))?;
    let mut table = HashMap::new();
    for row in rows {
        let cells: Vec<&str> = row.split('\t').collect();
        let cell = cells.get(column).copied().unwrap_or("");
        if cell.ends_with('?') {
            return Err(format!(
                "S_{} is an unconfirmed match in Spotify {build} ({cell}); check it and drop the ?",
                cells[0]
            ));
        }
        let (folder, class) = cell
            .split_once('/')
            .ok_or_else(|| format!("S_{}: no class for Spotify {build}", cells[0]))?;
        if table.insert(cells[0], (folder, class)).is_some() {
            return Err(format!("S_{} is listed twice", cells[0]));
        }
    }
    Ok(table)
}

/// The text with every `S_<alias>` and `R_<type>_<name>` replaced by what it is in the supported build.
pub fn rewrite(text: &str) -> Result<String> {
    let classes = rewrite_with(text, "S_", |alias| {
        let table = table().as_ref().map_err(|e| e.clone())?;
        let (_, class) = table
            .get(alias)
            .ok_or_else(|| format!("unknown Spotify class alias S_{alias}"))?;
        Ok(class.to_string())
    })?;
    rewrite_with(&classes, "R_", |token| resource(&format!("R_{token}")))
}

/// Replaces each whole word `<prefix><rest>` whose rest is a token (an alias, or `<type>_<name>`).
fn rewrite_with(text: &str, prefix: &str, find: impl Fn(&str) -> Result<String>) -> Result<String> {
    let bytes = text.as_bytes();
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    let (mut out, mut copied, mut i) = (String::with_capacity(text.len()), 0, 0);
    while let Some(found) = text[i..].find(prefix) {
        let at = i + found;
        let start = at + prefix.len();
        let end = start
            + text[start..]
                .find(|c: char| !(c.is_ascii_alphanumeric() || (prefix == "R_" && c == '_')))
                .unwrap_or(text.len() - start);
        let token = &text[start..end];
        let wanted = if prefix == "S_" {
            token.starts_with(|c: char| c.is_ascii_uppercase())
        } else {
            TYPES.iter().any(|t| {
                token
                    .strip_prefix(t)
                    .is_some_and(|r| r.starts_with('_') && r.len() > 1)
            })
        };
        if (at == 0 || !word(bytes[at - 1])) && wanted {
            out.push_str(&text[copied..at]);
            out.push_str(&find(token)?);
            copied = end;
        }
        i = end.max(start);
    }
    out.push_str(&text[copied..]);
    Ok(out)
}

/// A smali file named by an alias (`S_ProfileCard`) or by its path, as it sits in the decoded tree.
pub fn smali_file(file: &str) -> Result<String> {
    match file.strip_prefix("S_") {
        Some(alias) => {
            let table = table().as_ref().map_err(|e| e.clone())?;
            let (folder, class) = table
                .get(alias)
                .ok_or_else(|| format!("unknown Spotify class alias S_{alias}"))?;
            Ok(format!("{folder}/p/{class}.smali"))
        }
        None => Ok(file.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TABLE: &str =
        "# note\nalias\t1.0\t2.0\nCard\tsmali_classes2/ab1\tsmali/cd2\nTile\tsmali/x9\tsmali/y8?\n";

    #[test]
    fn every_alias_has_a_class_in_the_supported_build() {
        let table = table().as_ref().unwrap();
        assert!(table.len() > 150);
        assert!(
            table
                .values()
                .all(|(f, c)| f.starts_with("smali") && !c.is_empty())
        );
    }

    #[test]
    fn a_column_is_chosen_by_build() {
        let t = parse(TABLE, "1.0").unwrap();
        assert_eq!(t["Card"], ("smali_classes2", "ab1"));
        assert!(
            parse(TABLE, "2.0")
                .unwrap_err()
                .contains("S_Tile is an unconfirmed match")
        );
        assert!(parse(TABLE, "3.0").unwrap_err().contains("no column"));
    }

    #[test]
    fn rewrite_swaps_whole_aliases_only() {
        let (folder, card) = table().as_ref().unwrap()["ProfileCard"];
        assert_eq!(
            rewrite("Lp/S_ProfileCard;->e:Z p.S_ProfileCard x").unwrap(),
            format!("Lp/{card};->e:Z p.{card} x")
        );
        assert_eq!(
            rewrite("MY_S_Thing S_lower S_").unwrap(),
            "MY_S_Thing S_lower S_"
        );
        assert!(
            rewrite("S_NoSuchClass")
                .unwrap_err()
                .to_string()
                .contains("S_NoSuchClass")
        );
        assert_eq!(
            smali_file("S_ProfileCard").unwrap(),
            format!("{folder}/p/{card}.smali")
        );
        assert_eq!(
            smali_file("smali/p/abc.smali").unwrap(),
            "smali/p/abc.smali"
        );
    }

    #[test]
    fn resources_come_from_the_build() {
        load_resources(
            "Package name=com.spotify.music id=7f\n    resource 0x7f0b0c19 id/play_pause_button\n      () (file) x\n    resource 0x7f090002 font/spotify_mix\n",
        );
        assert_eq!(
            rewrite("const v0, R_id_play_pause_button\nint f = R_font_spotify_mix;").unwrap(),
            "const v0, 0x7f0b0c19\nint f = 0x7f090002;"
        );
        assert_eq!(
            rewrite("SOME_R_id_x R_nope_x").unwrap(),
            "SOME_R_id_x R_nope_x"
        );
        assert!(
            rewrite("R_id_missing_view")
                .unwrap_err()
                .to_string()
                .contains("R_id_missing_view")
        );
    }
}
