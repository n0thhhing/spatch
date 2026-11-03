//! A Spatch source: the exact supported APKM under an immutable name, plus `index.json` describing
//! it and every version staged into the same folder before, for Spatch Manager to download with an
//! invite. Only prepares files in a local folder; where they are served from, and who may fetch
//! them, is up to whoever hosts them.

use crate::Result;
use crate::input::{self, APKM_SHA256, APKS, PACKAGE, VERSION_CODE, VERSION_NAME};
use crate::tools::Tools;
use std::fs;
use std::path::Path;

const ABIS: [&str; 4] = ["arm64_v8a", "armeabi_v7a", "x86_64", "x86"];

/// Verifies the APKM and every APK inside it, then writes `<out>/<immutable name>.apkm` (unless
/// `index_only`, when the file is served from elsewhere), `<out>/entries/<versionCode>.json`, and
/// `<out>/index.json` listing every entry there, newest first, so older versions stay downloadable.
/// Each file is written beside its final name and renamed into place, the APKM before the index, so
/// a reader never sees an index naming a file that isn't complete.
pub fn stage(apkm: &Path, out: &Path, index_only: bool, tools: &Tools) -> Result<()> {
    input::check_identity(apkm)?;
    let work = out.join(".staging");
    if work.exists() {
        fs::remove_dir_all(&work)?;
    }
    fs::create_dir_all(&work)?;
    input::extract_apks(apkm, &work)?;
    let badging = tools.run(
        &tools.aapt2,
        &[
            "dump".as_ref(),
            "badging".as_ref(),
            work.join("base.apk").as_os_str(),
        ],
    )?;
    let min_sdk = min_sdk(&badging).ok_or("base.apk declares no minimum SDK")?;
    fs::remove_dir_all(&work)?;

    let name = object_name();
    if !index_only {
        let object = out.join(&name);
        if !object.exists() || input::sha256_file(&object)? != APKM_SHA256 {
            let part = out.join(format!("{name}.part"));
            fs::copy(apkm, &part)?;
            if input::sha256_file(&part)? != APKM_SHA256 {
                return Err("the copied APKM does not match its source".into());
            }
            fs::rename(&part, &object)?;
        }
        println!("wrote {}", object.display());
    }
    let entries = out.join("entries");
    fs::create_dir_all(&entries)?;
    write(
        &entries.join(format!("{VERSION_CODE}.json")),
        &entry_json(fs::metadata(apkm)?.len(), min_sdk, &name),
    )?;
    let mut all = Vec::new();
    for e in fs::read_dir(&entries)? {
        let path = e?.path();
        let code = path
            .file_stem()
            .and_then(|s| s.to_str())
            .and_then(|s| s.parse::<u64>().ok());
        if let (Some(code), Some("json")) = (code, path.extension().and_then(|e| e.to_str())) {
            all.push((code, fs::read_to_string(&path)?));
        }
    }
    all.sort_by_key(|a| std::cmp::Reverse(a.0));
    let index = out.join("index.json");
    write(&index, &index_json(all.iter().map(|(_, e)| e.trim_end())))?;
    println!("wrote {} with {} version(s)", index.display(), all.len());
    Ok(())
}

fn write(path: &Path, text: &str) -> Result<()> {
    let part = path.with_extension("json.part");
    fs::write(&part, text)?;
    fs::rename(&part, path)?;
    Ok(())
}

/// The APKM's name in a source: never reused for different bytes.
fn object_name() -> String {
    format!("spotify-{VERSION_CODE}-{}.apkm", &APKM_SHA256[..16])
}

/// The ABIs the APKM's own splits cover, in Android's spelling.
fn abis() -> Vec<String> {
    APKS.iter()
        .filter_map(|(name, _)| name.strip_prefix("split_config.")?.strip_suffix(".apk"))
        .filter(|abi| ABIS.contains(abi))
        .map(|abi| abi.replace('_', "-"))
        .collect()
}

/// aapt2 spells it minSdkVersion; older build-tools printed sdkVersion.
fn min_sdk(badging: &str) -> Option<u32> {
    badging.lines().find_map(|l| {
        let value = l
            .strip_prefix("minSdkVersion:")
            .or_else(|| l.strip_prefix("sdkVersion:"))?;
        value.trim_matches('\'').parse().ok()
    })
}

/// One version's entry in the index, as its own file so later versions can be added beside it.
fn entry_json(size: u64, min_sdk: u32, object: &str) -> String {
    let abis: Vec<String> = abis().iter().map(|a| format!("\"{a}\"")).collect();
    format!(
        "    {{\n      \"versionName\": \"{VERSION_NAME}\",\n      \"versionCode\": {VERSION_CODE},\n      \
         \"sha256\": \"{APKM_SHA256}\",\n      \"size\": {size},\n      \"minSdk\": {min_sdk},\n      \
         \"abis\": [{}],\n      \"urls\": [\"{object}\"]\n    }}\n",
        abis.join(", ")
    )
}

fn index_json<'a>(entries: impl Iterator<Item = &'a str>) -> String {
    let entries: Vec<&str> = entries.collect();
    format!(
        "{{\n  \"schema\": 1,\n  \"package\": \"{PACKAGE}\",\n  \"sources\": [\n{}\n  ]\n}}\n",
        entries.join(",\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_describes_the_pinned_input() {
        let entry = entry_json(60_000_000, 28, &object_name());
        let json = index_json([entry.trim_end()].into_iter());
        assert!(json.contains("\"schema\": 1"));
        assert!(json.contains(&format!("\"sha256\": \"{APKM_SHA256}\"")));
        assert!(json.contains(&format!("\"versionCode\": {VERSION_CODE},")));
        assert!(json.contains("\"size\": 60000000,"));
        assert!(json.contains("\"minSdk\": 28,"));
        assert!(json.contains("\"abis\": [\"arm64-v8a\"]"));
        assert!(json.contains(&format!(
            "\"urls\": [\"spotify-{VERSION_CODE}-1b966decbcaaa5d1.apkm\"]"
        )));
    }

    #[test]
    fn index_lists_every_entry_in_order() {
        let json = index_json(["    {\"versionCode\": 2}", "    {\"versionCode\": 1}"].into_iter());
        assert!(json.contains("[\n    {\"versionCode\": 2},\n    {\"versionCode\": 1}\n  ]"));
    }

    #[test]
    fn min_sdk_comes_from_badging() {
        let badging = "package: name='com.spotify.music' versionCode='148115007'\nsdkVersion:'28'\ntargetSdkVersion:'35'\n";
        assert_eq!(min_sdk(badging), Some(28));
        assert_eq!(
            min_sdk("minSdkVersion:'24'\ntargetSdkVersion:'37'\n"),
            Some(24)
        );
        assert_eq!(min_sdk("package: name='x'\n"), None);
    }

    #[test]
    fn staging_refuses_another_file() {
        let dir = input::tests::temp_dir("source-refuse");
        let fake = dir.join("fake.apkm");
        fs::write(&fake, b"not spotify").unwrap();
        let out = dir.join("out");
        let err = input::check_identity(&fake).unwrap_err().to_string();
        assert!(err.contains("does not match the only supported build"));
        assert!(!out.exists());
    }
}
