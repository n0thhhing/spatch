#!/usr/bin/env python3
"""Component changes schedule only their own nightly artifacts; no network or builds."""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="spatch-nightly-test-") as temp:
    repo = Path(temp)
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()
    git("init", "-q")
    git("config", "user.email", "test@example.invalid")
    git("config", "user.name", "Test")
    for name in ["src/input.rs", "theme/test", "manager/test", "scripts/port/aliases.tsv", "Cargo.toml"]:
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("original\n")
    shutil.copy(root / "scripts/nightly.sh", repo / "scripts/nightly.sh")
    git("add", ".")
    git("commit", "-qm", "baseline")
    labels = repo / "labels"
    labels.write_text("")
    mock = repo / "bin"
    mock.mkdir()
    gh = mock / "gh"
    gh.write_text('#!/bin/sh\ncase "$*" in *assets*) cat "$MOCK_LABELS" ;; esac\n')
    gh.chmod(0o755)
    env = dict(os.environ, PATH=str(mock) + os.pathsep + os.environ["PATH"],
               MOCK_LABELS=str(labels), NIGHTLY_CHECK="1")
    def check(patches, manager):
        actual = subprocess.check_output(["sh", str(repo / "scripts/nightly.sh")], env=env, text=True)
        assert actual == f"patches={str(patches).lower()}\nmanager={str(manager).lower()}\n", actual
    def published():
        tree = subprocess.check_output(["git", "-C", str(repo), "ls-tree", "-r", "HEAD", "--",
                                        "src", "theme", "Cargo.toml", "Cargo.lock", "scripts/port/aliases.tsv"])
        patch = subprocess.check_output(["git", "hash-object", "--stdin"], input=tree).decode().strip()
        manager = git("rev-parse", "HEAD:manager")
        labels.write_text(f"spatch-bundle.zip\tnightly-{patch}\nspatch-bundle-debug.zip\tnightly-{patch}\n"
                          f"spatch-manager.apk\tnightly-{manager}\n")
    check(True, True)
    published()
    check(False, False)
    (repo / "manager/test").write_text("manager changed\n")
    git("add", "manager")
    git("commit", "-qm", "manager only")
    check(False, True)
    published()
    (repo / "theme/test").write_text("patch changed\n")
    git("add", "theme")
    git("commit", "-qm", "patches only")
    check(True, False)
    published()
    (repo / "README.md").write_text("documentation\n")
    git("add", "README.md")
    git("commit", "-qm", "documentation only")
    check(False, False)
    labels.write_text(labels.read_text().splitlines()[0] + "\n")
    check(True, True)  # missing debug or manager assets must be repaired
print("PASS independent nightly identities, unchanged inputs and missing-asset repair")
