//! Discovery and checked invocation of the external Android tools.

use crate::Result;
use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug)]
pub struct Tools {
    pub java_home: PathBuf,
    pub apktool: PathBuf,
    pub aapt2: PathBuf,
    pub zipalign: PathBuf,
    pub apksigner: PathBuf,
    pub d8: PathBuf,
    pub dexdump: PathBuf,
}

const JAVA_HOMES: [&str; 1] = ["/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"];
const SDK_ROOTS: [&str; 1] = ["/opt/homebrew/share/android-commandlinetools"];

impl Tools {
    /// Finds every tool or reports all missing ones at once.
    pub fn discover() -> Result<Tools> {
        let mut missing = Vec::new();
        let java_home = env::var_os("JAVA_HOME")
            .map(PathBuf::from)
            .into_iter()
            .chain(JAVA_HOMES.iter().map(PathBuf::from))
            .find(|p| {
                ["bin/java", "bin/javac", "bin/jar"]
                    .iter()
                    .all(|b| p.join(b).is_file())
            });
        let apktool = on_path("apktool");
        let build_tools = build_tools_dir();
        let bt = |name: &str| {
            build_tools
                .as_ref()
                .map(|d| d.join(name))
                .filter(|p| p.is_file())
        };
        let (aapt2, zipalign, apksigner) = (bt("aapt2"), bt("zipalign"), bt("apksigner"));
        let (d8, dexdump) = (bt("d8"), bt("dexdump"));
        for (name, found) in [
            ("JDK with javac and jar (JAVA_HOME)", java_home.is_some()),
            ("apktool", apktool.is_some()),
            ("aapt2 (SDK build-tools)", aapt2.is_some()),
            ("zipalign (SDK build-tools)", zipalign.is_some()),
            ("apksigner (SDK build-tools)", apksigner.is_some()),
            ("d8 (SDK build-tools)", d8.is_some()),
            ("dexdump (SDK build-tools)", dexdump.is_some()),
        ] {
            if !found {
                missing.push(name);
            }
        }
        if !missing.is_empty() {
            return Err(format!("missing tools: {}", missing.join(", ")).into());
        }
        Ok(Tools {
            java_home: java_home.unwrap(),
            apktool: apktool.unwrap(),
            aapt2: aapt2.unwrap(),
            zipalign: zipalign.unwrap(),
            apksigner: apksigner.unwrap(),
            d8: d8.unwrap(),
            dexdump: dexdump.unwrap(),
        })
    }

    /// Runs a tool with an argument array and the discovered JDK; non-zero exit is an error.
    pub fn run(&self, program: &Path, args: &[&std::ffi::OsStr]) -> Result<String> {
        let path = env::join_paths(
            std::iter::once(self.java_home.join("bin"))
                .chain(env::split_paths(&env::var_os("PATH").unwrap_or_default())),
        )?;
        let out = Command::new(program)
            .args(args)
            .env("JAVA_HOME", &self.java_home)
            .env("PATH", path)
            .output()
            .map_err(|e| format!("cannot run {}: {e}", program.display()))?;
        let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
        if !out.status.success() {
            let stderr = String::from_utf8_lossy(&out.stderr);
            let tail: Vec<&str> = stderr.lines().rev().take(15).collect();
            return Err(format!(
                "{} failed ({}):\n{}",
                program.display(),
                out.status,
                tail.into_iter().rev().collect::<Vec<_>>().join("\n")
            )
            .into());
        }
        Ok(stdout)
    }
}

fn on_path(name: &str) -> Option<PathBuf> {
    env::split_paths(&env::var_os("PATH")?)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
}

/// Highest-versioned build-tools directory under ANDROID_HOME, ANDROID_SDK_ROOT or known installs.
fn build_tools_dir() -> Option<PathBuf> {
    let roots = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(|v| env::var_os(v).map(PathBuf::from))
        .chain(env::var_os("HOME").map(|h| PathBuf::from(h).join("Library/Android/sdk")))
        .chain(SDK_ROOTS.iter().map(PathBuf::from));
    roots
        .filter_map(|r| std::fs::read_dir(r.join("build-tools")).ok())
        .flat_map(|rd| rd.filter_map(|e| e.ok().map(|e| e.path())))
        .filter(|p| p.join("apksigner").is_file())
        .max_by_key(|p| version_key(p))
}

fn version_key(p: &Path) -> Vec<u32> {
    p.file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .split('.')
        .map(|s| s.parse().unwrap_or(0))
        .collect()
}
