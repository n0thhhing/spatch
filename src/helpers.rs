//! Production helper sources and compile-only Spotify/RxJava signatures.

include!(concat!(env!("OUT_DIR"), "/helpers.rs"));

/// Stand-ins used by javac, never packaged in the helper DEX.
pub const STUBS: &[(&str, &str)] = &[
    ("dmt0.java", include_str!("../java/dex/stubs/p/dmt0.java")),
    ("osm.java", include_str!("../java/dex/stubs/p/osm.java")),
    (
        "VerifyRequest.java",
        include_str!(
            "../java/dex/stubs/com/spotify/integrity/integrityimpl/proto/VerifyRequest.java"
        ),
    ),
    (
        "VerifyResponse.java",
        include_str!(
            "../java/dex/stubs/com/spotify/integrity/integrityimpl/proto/VerifyResponse.java"
        ),
    ),
    ("x740.java", include_str!("../java/dex/stubs/p/x740.java")),
    ("brs0.java", include_str!("../java/dex/stubs/p/brs0.java")),
    ("gf60.java", include_str!("../java/dex/stubs/p/gf60.java")),
    ("xmb.java", include_str!("../java/dex/stubs/p/xmb.java")),
    ("ngr0.java", include_str!("../java/dex/stubs/p/ngr0.java")),
    ("Action.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/functions/Action.java")),
    ("Consumer.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/functions/Consumer.java")),
    ("Disposable.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/disposables/Disposable.java")),
    ("Observable.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Observable.java")),
    ("Single.java", include_str!("../java/dex/stubs/io/reactivex/rxjava3/core/Single.java")),
    (
        "LyricsWrapperResponse.java",
        include_str!(
            "../java/dex/stubs/com/spotify/lyrics/serviceretrofit/proto/v3/LyricsWrapperResponse.java"
        ),
    ),
    (
        "BatchedExtensionResponse.java",
        include_str!(
            "../java/dex/stubs/com/spotify/extendedmetadata/proto/BatchedExtensionResponse.java"
        ),
    ),
    ("Empty.java", include_str!("../java/dex/stubs/com/google/protobuf/Empty.java")),
    ("Playlist4ApiProto.java", include_str!("../java/dex/stubs/com/spotify/playlist4/proto/Playlist4ApiProto.java")),
    ("Transport.java", include_str!("../java/dex/stubs/com/spotify/esperanto/esperanto/Transport.java")),
];
