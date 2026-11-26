//! Production helper sources and compile-only Spotify/RxJava signatures.

include!(concat!(env!("OUT_DIR"), "/helpers.rs"));

/// Stand-ins used by javac, never packaged in the helper DEX.
pub const STUBS: &[(&str, &str)] = &[
    ("x740.java", include_str!("../../../android/stubs/p/x740.java")),
    ("brs0.java", include_str!("../../../android/stubs/p/brs0.java")),
    ("gf60.java", include_str!("../../../android/stubs/p/gf60.java")),
    ("xmb.java", include_str!("../../../android/stubs/p/xmb.java")),
    ("ngr0.java", include_str!("../../../android/stubs/p/ngr0.java")),
    ("pjz0.java", include_str!("../../../android/stubs/p/pjz0.java")),
    ("pc60.java", include_str!("../../../android/stubs/p/pc60.java")),
    ("Action.java", include_str!("../../../android/stubs/io/reactivex/rxjava3/functions/Action.java")),
    ("Consumer.java", include_str!("../../../android/stubs/io/reactivex/rxjava3/functions/Consumer.java")),
    ("Disposable.java", include_str!("../../../android/stubs/io/reactivex/rxjava3/disposables/Disposable.java")),
    ("Observable.java", include_str!("../../../android/stubs/io/reactivex/rxjava3/core/Observable.java")),
    ("Single.java", include_str!("../../../android/stubs/io/reactivex/rxjava3/core/Single.java")),
    (
        "LyricsWrapperResponse.java",
        include_str!(
            "../../../android/stubs/com/spotify/lyrics/serviceretrofit/proto/v3/LyricsWrapperResponse.java"
        ),
    ),
    (
        "BatchedExtensionResponse.java",
        include_str!(
            "../../../android/stubs/com/spotify/extendedmetadata/proto/BatchedExtensionResponse.java"
        ),
    ),
    (
        "MainViewResponse.java",
        include_str!(
            "../../../android/stubs/com/spotify/searchview/proto/MainViewResponse.java"
        ),
    ),
    ("Empty.java", include_str!("../../../android/stubs/com/google/protobuf/Empty.java")),
    ("Playlist4ApiProto.java", include_str!("../../../android/stubs/com/spotify/playlist4/proto/Playlist4ApiProto.java")),
    ("Transport.java", include_str!("../../../android/stubs/com/spotify/esperanto/esperanto/Transport.java")),
];
