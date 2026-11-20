//! Production helper sources and compile-only Spotify/RxJava signatures.

/// Compiled together into the helper DEX. Keep this list in class-name order.
pub const HELPERS: &[(&str, &str)] = &[
    ("AboutScreen", include_str!("../java/dex/src/spatch/AboutScreen.java")),
    ("ArtworkViews", include_str!("../java/dex/src/spatch/ArtworkViews.java")),
    ("Audio", include_str!("../java/dex/src/spatch/Audio.java")),
    ("AutoEqCatalog", include_str!("../java/dex/src/spatch/AutoEqCatalog.java")),
    ("Confetti", include_str!("../java/dex/src/spatch/Confetti.java")),
    ("CoreBridge", include_str!("../java/dex/src/spatch/CoreBridge.java")),
    ("CreationSheet", include_str!("../java/dex/src/spatch/CreationSheet.java")),
    ("MediaEntry", include_str!("../java/dex/src/spatch/MediaEntry.java")),
    ("PackedStrings", include_str!("../java/dex/src/spatch/PackedStrings.java")),
    ("ProgressMotion", include_str!("../java/dex/src/spatch/ProgressMotion.java")),
    ("SoundSettings", include_str!("../java/dex/src/spatch/SoundSettings.java")),
    ("SpatchApp", include_str!("../java/dex/src/spatch/SpatchApp.java")),
    ("SpatchUi", include_str!("../java/dex/src/spatch/SpatchUi.java")),
    ("SpatchWidget", include_str!("../java/dex/src/spatch/SpatchWidget.java")),
    ("Subscriptions", include_str!("../java/dex/src/spatch/Subscriptions.java")),
];

/// Stand-ins used by javac, never packaged in the helper DEX.
pub const STUBS: &[(&str, &str)] = &[
    ("Action.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/functions/Action.java")),
    ("Consumer.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/functions/Consumer.java")),
    ("Disposable.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/disposables/Disposable.java")),
    ("Observable.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Observable.java")),
    ("Single.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Single.java")),
    ("Transport.java", include_str!("../java/dex/stubs/com/spotify/esperanto/esperanto/Transport.java")),
];
