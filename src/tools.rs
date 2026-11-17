//! Discovery and checked invocation of the external Android tools.

use crate::Result;
use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug)]
pub struct Tools {
    pub java_home: PathBuf,
    pub apktool: PathBuf,
    pub d8: PathBuf,
    pub dexdump: PathBuf,
}

const JAVA_HOMES: [&str; 1] = ["/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"];
const SDK_ROOTS: [&str; 1] = ["/opt/homebrew/share/android-commandlinetools"];

impl Tools {
    /// Finds the DEX build tools or reports all missing ones at once.
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
        let (d8, dexdump) = (find_sdk_tool("d8"), find_sdk_tool("dexdump"));
        for (name, found) in [
            ("JDK with javac and jar (JAVA_HOME)", java_home.is_some()),
            ("apktool", apktool.is_some()),
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
        let mut command = Command::new(program);
        command.env("JAVA_HOME", &self.java_home).env("PATH", path);
        checked_output(command.args(args))
    }
}

pub fn run(program: &Path, args: &[&std::ffi::OsStr]) -> Result<String> {
    checked_output(Command::new(program).args(args))
}

fn checked_output(command: &mut Command) -> Result<String> {
    let result = command.output();
    let program = Path::new(command.get_program());
    let out = result.map_err(|e| format!("cannot run {}: {e}", program.display()))?;
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

fn on_path(name: &str) -> Option<PathBuf> {
    env::split_paths(&env::var_os("PATH")?)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
}

fn sdk_roots() -> impl Iterator<Item = PathBuf> {
    ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(|v| env::var_os(v).map(PathBuf::from))
        .chain(env::var_os("HOME").map(|h| PathBuf::from(h).join("Library/Android/sdk")))
        .chain(SDK_ROOTS.iter().map(PathBuf::from))
}

pub fn android_jar() -> Option<PathBuf> {
    sdk_roots()
        .filter_map(|root| std::fs::read_dir(root.join("platforms")).ok())
        .flat_map(|entries| entries.filter_map(|entry| entry.ok().map(|entry| entry.path())))
        .filter(|path| path.join("android.jar").is_file())
        .max_by_key(|path| {
            let name = path.file_name().and_then(|name| name.to_str()).unwrap_or("");
            name.strip_prefix("android-").unwrap_or("").split('.')
                .map(|part| part.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>()
        })
        .map(|path| path.join("android.jar"))
}

pub fn sdk_tool(name: &str) -> Result<PathBuf> {
    find_sdk_tool(name).ok_or_else(|| format!("missing tool: {name} (SDK build-tools)").into())
}

fn find_sdk_tool(name: &str) -> Option<PathBuf> {
    sdk_roots()
        .filter_map(|r| std::fs::read_dir(r.join("build-tools")).ok())
        .flat_map(|rd| rd.filter_map(|e| e.ok().map(|e| e.path())))
        .filter(|p| p.join(name).is_file())
        .max_by_key(|p| version_key(p))
        .map(|p| p.join(name))
}

fn version_key(p: &Path) -> Vec<u32> {
    p.file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .split('.')
        .map(|s| s.parse().unwrap_or(0))
        .collect()
}
