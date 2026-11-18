//! The Java that ships in the helper DEX, and the compile-only stubs it builds against.

/// Helper classes, shipped together in `HELPER_DEX`.
pub const HELPERS: [(&str, &str); 4] = [
    (
        "Audio",
        include_str!("../java/dex/src/spatch/core/Audio.java"),
    ),
    (
        "CoreBridge",
        include_str!("../java/dex/src/spatch/core/CoreBridge.java"),
    ),
    (
        "SpatchApp",
        include_str!("../java/dex/src/spatch/core/SpatchApp.java"),
    ),
    (
        "SpatchUi",
        include_str!("../java/dex/src/spatch/core/SpatchUi.java"),
    ),
];

/// Compile-only stand-ins for Spotify dependencies, never packaged.
pub const STUBS: [(&str, &str); 5] = [
    (
        "Transport.java",
        include_str!("../java/dex/stubs/com/spotify/esperanto/esperanto/Transport.java"),
    ),
    (
        "Single.java",
        include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Single.java"),
    ),
    (
        "Observable.java",
        include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Observable.java"),
    ),
    (
        "Consumer.java",
        include_str!("../java/dex/stubs/io/reactivex/rxjava3/functions/Consumer.java"),
    ),
    (
        "Disposable.java",
        include_str!("../java/dex/stubs/io/reactivex/rxjava3/disposables/Disposable.java"),
    ),
];
