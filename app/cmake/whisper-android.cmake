# Run inside whisper-rs-sys's CMake build (CMAKE_PROJECT_INCLUDE, set in .cargo/config.toml). Its build script names
# link libraries itself, so two of them are answered with files in the build tree, which it adds to the link path.

# It asks for ggml-blas whenever the HOST is a Mac, though the Android build never makes one: an empty archive.
file(WRITE "${CMAKE_BINARY_DIR}/libggml-blas.a" "!<arch>\n")

# It links libc++_shared.so, which would resolve to Spotify's own copy (NDK r27) at runtime; that copy lacks
# symbols whisper.cpp needs, so the whole library would fail to load. This linker script, found first, links
# the NDK's static libc++ into our library instead.
file(WRITE "${CMAKE_BINARY_DIR}/libc++_shared.so" "INPUT(-lc++_static -lc++abi)\n")
