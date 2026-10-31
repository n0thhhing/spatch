# Obfuscated Spotify classes

Each file here is a compile-only stub for a class in Spotify 9.1.98.521. R8 renamed those classes into
package `p`, so the names mean nothing and change with every Spotify release. The stubs are never
packaged: the app provides the real classes at runtime.

The comment on line 1 of each stub says what the class is. "Role not confirmed" means helper code
never names it directly, so it was not traced. Every name is valid for this version only; `src/dex.rs`
pins the app by SHA-256, so a new version fails loudly instead of binding the wrong class.
