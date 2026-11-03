//! Spatch's own names for the obfuscated Spotify classes it uses. Code, stubs and smali edits write
//! `S_<alias>`; before anything is compiled or edited, `rewrite` swaps in the class's name in the
//! supported build. `port/aliases.tsv` has a column per Spotify build, filled by `port/port.py`.

use crate::Result;
use crate::input::VERSION_NAME;
use std::collections::HashMap;
use std::sync::OnceLock;

const ALIASES: &str = include_str!("../port/aliases.tsv");

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
        .ok_or("port/aliases.tsv is empty")?
        .split('\t')
        .collect();
    let column = header
        .iter()
        .position(|h| *h == build)
        .ok_or_else(|| format!("port/aliases.tsv has no column for Spotify {build}"))?;
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

/// The text with every `S_<alias>` replaced by the class's name in the supported build.
pub fn rewrite(text: &str) -> Result<String> {
    let table = table().as_ref().map_err(|e| e.clone())?;
    let bytes = text.as_bytes();
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    let (mut out, mut copied, mut i) = (String::with_capacity(text.len()), 0, 0);
    while let Some(found) = text[i..].find("S_") {
        let at = i + found;
        let end = at
            + 2
            + text[at + 2..]
                .find(|c: char| !c.is_ascii_alphanumeric())
                .unwrap_or(text.len() - at - 2);
        let starts_word = at == 0 || !word(bytes[at - 1]);
        if starts_word && end > at + 2 && bytes[at + 2].is_ascii_uppercase() {
            let alias = &text[at + 2..end];
            let (_, class) = table
                .get(alias)
                .ok_or_else(|| format!("unknown Spotify class alias S_{alias}"))?;
            out.push_str(&text[copied..at]);
            out.push_str(class);
            copied = end;
        }
        i = end.max(at + 2);
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
        let alias = ALIASES
            .lines()
            .find(|l| l.starts_with("ProfileCard\t"))
            .unwrap();
        assert!(alias.contains("/ph81"));
        assert_eq!(
            rewrite("Lp/S_ProfileCard;->e:Z p.S_ProfileCard x").unwrap(),
            "Lp/ph81;->e:Z p.ph81 x"
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
            "smali_classes11/p/ph81.smali"
        );
        assert_eq!(
            smali_file("smali/p/abc.smali").unwrap(),
            "smali/p/abc.smali"
        );
    }
}
