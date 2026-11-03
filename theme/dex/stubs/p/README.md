# Obfuscated Spotify classes

Each file here is a compile-only stub for a class Spotify's R8 build renamed into package `p`. The
names R8 gives mean nothing and change with every Spotify release, so Spatch uses its own:
`S_<alias>`, listed with each build's real name in `scripts/port/aliases.tsv`. Before anything is compiled
or edited, `src/names.rs` swaps in the supported build's names, so stubs, Spatch's Java and the smali
edits never mention them. The stubs are never packaged: the app provides the real classes at runtime.

The comment on line 1 of each stub says what the class is. "Role not confirmed" means helper code
never names it directly, so it was not traced. Field and method names inside a stub are still the
build's own (R8 renames those too); `scripts/port/port.py` maps them when Spotify updates.
