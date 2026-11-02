//! The Java that ships in the helper DEX, and the compile-only stubs it builds against.

/// Helper classes (theme tokens substituted before compiling), shipped together in `HELPER_DEX`.
pub const HELPERS: [(&str, &str); 54] = [
    (
        "Fonts",
        include_str!("../theme/dex/src/spatch/look/Fonts.java"),
    ),
    (
        "FontFile",
        include_str!("../theme/dex/src/spatch/look/FontFile.java"),
    ),
    (
        "FontImport",
        include_str!("../theme/dex/src/spatch/look/FontImport.java"),
    ),
    (
        "Typography",
        include_str!("../theme/dex/src/spatch/look/Typography.java"),
    ),
    (
        "HomeReview",
        include_str!("../theme/dex/src/spatch/lists/HomeReview.java"),
    ),
    (
        "ProfileBanner",
        include_str!("../theme/dex/src/spatch/lists/ProfileBanner.java"),
    ),
    (
        "BannerImport",
        include_str!("../theme/dex/src/spatch/settings/BannerImport.java"),
    ),
    (
        "FilterRow",
        include_str!("../theme/dex/src/spatch/lists/FilterRow.java"),
    ),
    (
        "Expansion",
        include_str!("../theme/dex/src/spatch/player/Expansion.java"),
    ),
    (
        "ClickHaptic",
        include_str!("../theme/dex/src/spatch/motion/ClickHaptic.java"),
    ),
    (
        "SaveBurst",
        include_str!("../theme/dex/src/spatch/player/SaveBurst.java"),
    ),
    (
        "Surfaces",
        include_str!("../theme/dex/src/spatch/lists/Surfaces.java"),
    ),
    (
        "MiniRing",
        include_str!("../theme/dex/src/spatch/mini/MiniRing.java"),
    ),
    (
        "MiniSurface",
        include_str!("../theme/dex/src/spatch/mini/MiniSurface.java"),
    ),
    (
        "Haptics",
        include_str!("../theme/dex/src/spatch/motion/Haptics.java"),
    ),
    (
        "MiniBar",
        include_str!("../theme/dex/src/spatch/mini/MiniBar.java"),
    ),
    (
        "MiniEq",
        include_str!("../theme/dex/src/spatch/mini/MiniEq.java"),
    ),
    (
        "MiniSkip",
        include_str!("../theme/dex/src/spatch/mini/MiniSkip.java"),
    ),
    (
        "NavIndicator",
        include_str!("../theme/dex/src/spatch/lists/NavIndicator.java"),
    ),
    (
        "M3Switch",
        include_str!("../theme/dex/src/spatch/settings/M3Switch.java"),
    ),
    (
        "SettingsHero",
        include_str!("../theme/dex/src/spatch/settings/SettingsHero.java"),
    ),
    (
        "ThemeReveal",
        include_str!("../theme/dex/src/spatch/settings/ThemeReveal.java"),
    ),
    (
        "ThemeSwatch",
        include_str!("../theme/dex/src/spatch/settings/ThemeSwatch.java"),
    ),
    (
        "Content",
        include_str!("../theme/dex/src/spatch/lists/Content.java"),
    ),
    (
        "Spectrum",
        include_str!("../theme/dex/src/spatch/audio/Spectrum.java"),
    ),
    (
        "SpectrumPreview",
        include_str!("../theme/dex/src/spatch/audio/SpectrumPreview.java"),
    ),
    (
        "TextSlide",
        include_str!("../theme/dex/src/spatch/player/TextSlide.java"),
    ),
    (
        "SpatchWidget",
        include_str!("../theme/dex/src/spatch/player/SpatchWidget.java"),
    ),
    (
        "MiniArt",
        include_str!("../theme/dex/src/spatch/mini/MiniArt.java"),
    ),
    (
        "ScallopFill",
        include_str!("../theme/dex/src/spatch/player/ScallopFill.java"),
    ),
    (
        "ScallopShape",
        include_str!("../theme/dex/src/spatch/player/ScallopShape.java"),
    ),
    (
        "Palette",
        include_str!("../theme/dex/src/spatch/look/Palette.java"),
    ),
    (
        "PaletteResources",
        include_str!("../theme/dex/src/spatch/look/PaletteResources.java"),
    ),
    (
        "ImmersiveButton",
        include_str!("../theme/dex/src/spatch/player/ImmersiveButton.java"),
    ),
    (
        "AudioSpectrum",
        include_str!("../theme/dex/src/spatch/audio/AudioSpectrum.java"),
    ),
    (
        "SpatchSettings",
        include_str!("../theme/dex/src/spatch/settings/SpatchSettings.java"),
    ),
    (
        "Back",
        include_str!("../theme/dex/src/spatch/motion/Back.java"),
    ),
    (
        "Tint",
        include_str!("../theme/dex/src/spatch/look/Tint.java"),
    ),
    (
        "Confetti",
        include_str!("../theme/dex/src/spatch/motion/Confetti.java"),
    ),
    (
        "Flourish",
        include_str!("../theme/dex/src/spatch/lists/Flourish.java"),
    ),
    (
        "ArtMotion",
        include_str!("../theme/dex/src/spatch/player/ArtMotion.java"),
    ),
    (
        "HoldSeek",
        include_str!("../theme/dex/src/spatch/player/HoldSeek.java"),
    ),
    (
        "SeekBubble",
        include_str!("../theme/dex/src/spatch/player/SeekBubble.java"),
    ),
    (
        "RowCards",
        include_str!("../theme/dex/src/spatch/lists/RowCards.java"),
    ),
    (
        "Springy",
        include_str!("../theme/dex/src/spatch/motion/Springy.java"),
    ),
    (
        "Settings",
        include_str!("../theme/dex/src/spatch/settings/Settings.java"),
    ),
    (
        "TapBounce",
        include_str!("../theme/dex/src/spatch/motion/TapBounce.java"),
    ),
    (
        "PlayerArt",
        include_str!("../theme/dex/src/spatch/player/PlayerArt.java"),
    ),
    (
        "Atmosphere",
        include_str!("../theme/dex/src/spatch/player/Atmosphere.java"),
    ),
    (
        "Transport",
        include_str!("../theme/dex/src/spatch/player/Transport.java"),
    ),
    (
        "Scallop",
        include_str!("../theme/dex/src/spatch/player/Scallop.java"),
    ),
    (
        "Wave",
        include_str!("../theme/dex/src/spatch/player/Wave.java"),
    ),
    (
        "Pill",
        include_str!("../theme/dex/src/spatch/motion/Pill.java"),
    ),
    (
        "Immersive",
        include_str!("../theme/dex/src/spatch/player/Immersive.java"),
    ),
];
/// Added by `--debug` only, and found by name at runtime, so other builds contain none of this code.
pub const DEBUG_HELPERS: [(&str, &str); 4] = [
    (
        "DevSampler",
        include_str!("../theme/dex/src/spatch/dev/DevSampler.java"),
    ),
    (
        "DevOptions",
        include_str!("../theme/dex/src/spatch/dev/DevOptions.java"),
    ),
    (
        "DevTests",
        include_str!("../theme/dex/src/spatch/dev/DevTests.java"),
    ),
    (
        "DevOverlay",
        include_str!("../theme/dex/src/spatch/dev/DevOverlay.java"),
    ),
];

pub fn helpers(debug: bool) -> Vec<(&'static str, &'static str)> {
    let extra: &[(&str, &str)] = if debug { &DEBUG_HELPERS } else { &[] };
    HELPERS.iter().chain(extra).copied().collect()
}

/// Compile-only stand-ins for platform and Spotify classes; never packaged.
pub const STUBS: &[(&str, &str)] = &[
    (
        "Application.java",
        include_str!("../theme/dex/stubs/android/app/Application.java"),
    ),
    ("i7h.java", include_str!("../theme/dex/stubs/p/i7h.java")),
    ("shc0.java", include_str!("../theme/dex/stubs/p/shc0.java")),
    (
        "LayerDrawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/LayerDrawable.java"),
    ),
    (
        "InputMethodManager.java",
        include_str!("../theme/dex/stubs/android/view/inputmethod/InputMethodManager.java"),
    ),
    (
        "IBinder.java",
        include_str!("../theme/dex/stubs/android/os/IBinder.java"),
    ),
    ("ud5.java", include_str!("../theme/dex/stubs/p/ud5.java")),
    ("kex.java", include_str!("../theme/dex/stubs/p/kex.java")),
    ("ggx.java", include_str!("../theme/dex/stubs/p/ggx.java")),
    ("gsc.java", include_str!("../theme/dex/stubs/p/gsc.java")),
    ("w6b1.java", include_str!("../theme/dex/stubs/p/w6b1.java")),
    (
        "Camera.java",
        include_str!("../theme/dex/stubs/android/graphics/Camera.java"),
    ),
    (
        "Log.java",
        include_str!("../theme/dex/stubs/android/util/Log.java"),
    ),
    (
        "PackageInfo.java",
        include_str!("../theme/dex/stubs/android/content/pm/PackageInfo.java"),
    ),
    (
        "Layout.java",
        include_str!("../theme/dex/stubs/android/text/Layout.java"),
    ),
    (
        "ViewTreeObserver.java",
        include_str!("../theme/dex/stubs/android/view/ViewTreeObserver.java"),
    ),
    (
        "ColorDrawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/ColorDrawable.java"),
    ),
    (
        "LayoutInflater.java",
        include_str!("../theme/dex/stubs/android/view/LayoutInflater.java"),
    ),
    (
        "Function0.java",
        include_str!("../theme/dex/stubs/kotlin/jvm/functions/Function0.java"),
    ),
    (
        "Font.java",
        include_str!("../theme/dex/stubs/android/graphics/fonts/Font.java"),
    ),
    (
        "FontFamily.java",
        include_str!("../theme/dex/stubs/android/graphics/fonts/FontFamily.java"),
    ),
    (
        "FontStyle.java",
        include_str!("../theme/dex/stubs/android/graphics/fonts/FontStyle.java"),
    ),
    (
        "Uri.java",
        include_str!("../theme/dex/stubs/android/net/Uri.java"),
    ),
    (
        "ContentResolver.java",
        include_str!("../theme/dex/stubs/android/content/ContentResolver.java"),
    ),
    (
        "ClipData.java",
        include_str!("../theme/dex/stubs/android/content/ClipData.java"),
    ),
    (
        "AssetFileDescriptor.java",
        include_str!("../theme/dex/stubs/android/content/res/AssetFileDescriptor.java"),
    ),
    (
        "InsetDrawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/InsetDrawable.java"),
    ),
    (
        "HorizontalScrollView.java",
        include_str!("../theme/dex/stubs/android/widget/HorizontalScrollView.java"),
    ),
    (
        "Button.java",
        include_str!("../theme/dex/stubs/android/widget/Button.java"),
    ),
    (
        "EditText.java",
        include_str!("../theme/dex/stubs/android/widget/EditText.java"),
    ),
    (
        "Fragment.java",
        include_str!("../theme/dex/stubs/android/app/Fragment.java"),
    ),
    (
        "FragmentManager.java",
        include_str!("../theme/dex/stubs/android/app/FragmentManager.java"),
    ),
    (
        "FragmentTransaction.java",
        include_str!("../theme/dex/stubs/android/app/FragmentTransaction.java"),
    ),
    (
        "Bundle.java",
        include_str!("../theme/dex/stubs/android/os/Bundle.java"),
    ),
    (
        "RecyclerView.java",
        include_str!("../theme/dex/stubs/androidx/recyclerview/widget/RecyclerView.java"),
    ),
    (
        "a.java",
        include_str!("../theme/dex/stubs/androidx/recyclerview/widget/a.java"),
    ),
    (
        "LinearLayoutManager.java",
        include_str!("../theme/dex/stubs/androidx/recyclerview/widget/LinearLayoutManager.java"),
    ),
    ("r411.java", include_str!("../theme/dex/stubs/p/r411.java")),
    ("evx.java", include_str!("../theme/dex/stubs/p/evx.java")),
    ("zyq0.java", include_str!("../theme/dex/stubs/p/zyq0.java")),
    ("vyq0.java", include_str!("../theme/dex/stubs/p/vyq0.java")),
    ("zfx.java", include_str!("../theme/dex/stubs/p/zfx.java")),
    ("dvx.java", include_str!("../theme/dex/stubs/p/dvx.java")),
    ("e6x0.java", include_str!("../theme/dex/stubs/p/e6x0.java")),
    ("l6x0.java", include_str!("../theme/dex/stubs/p/l6x0.java")),
    ("tu61.java", include_str!("../theme/dex/stubs/p/tu61.java")),
    (
        "Brush.java",
        include_str!("../theme/dex/stubs/androidx/compose/ui/graphics/Brush.java"),
    ),
    (
        "Shadow.java",
        include_str!("../theme/dex/stubs/androidx/compose/ui/graphics/Shadow.java"),
    ),
    (
        "BitmapShader.java",
        include_str!("../theme/dex/stubs/android/graphics/BitmapShader.java"),
    ),
    (
        "SweepGradient.java",
        include_str!("../theme/dex/stubs/android/graphics/SweepGradient.java"),
    ),
    (
        "Window.java",
        include_str!("../theme/dex/stubs/android/view/Window.java"),
    ),
    (
        "Choreographer.java",
        include_str!("../theme/dex/stubs/android/view/Choreographer.java"),
    ),
    (
        "ClipboardManager.java",
        include_str!("../theme/dex/stubs/android/content/ClipboardManager.java"),
    ),
    (
        "Process.java",
        include_str!("../theme/dex/stubs/android/os/Process.java"),
    ),
    (
        "ComposeShader.java",
        include_str!("../theme/dex/stubs/android/graphics/ComposeShader.java"),
    ),
    (
        "Matrix.java",
        include_str!("../theme/dex/stubs/android/graphics/Matrix.java"),
    ),
    (
        "PorterDuff.java",
        include_str!("../theme/dex/stubs/android/graphics/PorterDuff.java"),
    ),
    (
        "BitmapFactory.java",
        include_str!("../theme/dex/stubs/android/graphics/BitmapFactory.java"),
    ),
    ("t0d0.java", include_str!("../theme/dex/stubs/p/t0d0.java")),
    ("x7v.java", include_str!("../theme/dex/stubs/p/x7v.java")),
    ("xmc0.java", include_str!("../theme/dex/stubs/p/xmc0.java")),
    ("ey50.java", include_str!("../theme/dex/stubs/p/ey50.java")),
    ("in60.java", include_str!("../theme/dex/stubs/p/in60.java")),
    ("jn60.java", include_str!("../theme/dex/stubs/p/jn60.java")),
    ("ln60.java", include_str!("../theme/dex/stubs/p/ln60.java")),
    ("wt30.java", include_str!("../theme/dex/stubs/p/wt30.java")),
    ("vse1.java", include_str!("../theme/dex/stubs/p/vse1.java")),
    ("wwm1.java", include_str!("../theme/dex/stubs/p/wwm1.java")),
    ("ptd1.java", include_str!("../theme/dex/stubs/p/ptd1.java")),
    ("nbd0.java", include_str!("../theme/dex/stubs/p/nbd0.java")),
    (
        "ViewConfiguration.java",
        include_str!("../theme/dex/stubs/android/view/ViewConfiguration.java"),
    ),
    (
        "ViewOverlay.java",
        include_str!("../theme/dex/stubs/android/view/ViewOverlay.java"),
    ),
    (
        "DecelerateInterpolator.java",
        include_str!("../theme/dex/stubs/android/view/animation/DecelerateInterpolator.java"),
    ),
    ("kk1.java", include_str!("../theme/dex/stubs/p/kk1.java")),
    ("lk1.java", include_str!("../theme/dex/stubs/p/lk1.java")),
    (
        "MotionLayout.java",
        include_str!(
            "../theme/dex/stubs/androidx/constraintlayout/motion/widget/MotionLayout.java"
        ),
    ),
    ("upf.java", include_str!("../theme/dex/stubs/p/upf.java")),
    ("e5c0.java", include_str!("../theme/dex/stubs/p/e5c0.java")),
    ("l4c0.java", include_str!("../theme/dex/stubs/p/l4c0.java")),
    ("u4c0.java", include_str!("../theme/dex/stubs/p/u4c0.java")),
    ("r4c0.java", include_str!("../theme/dex/stubs/p/r4c0.java")),
    ("ndy.java", include_str!("../theme/dex/stubs/p/ndy.java")),
    ("psn0.java", include_str!("../theme/dex/stubs/p/psn0.java")),
    ("z341.java", include_str!("../theme/dex/stubs/p/z341.java")),
    ("k1b.java", include_str!("../theme/dex/stubs/p/k1b.java")),
    (
        "OvershootInterpolator.java",
        include_str!("../theme/dex/stubs/android/view/animation/OvershootInterpolator.java"),
    ),
    (
        "TimeInterpolator.java",
        include_str!("../theme/dex/stubs/android/animation/TimeInterpolator.java"),
    ),
    (
        "RippleDrawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/RippleDrawable.java"),
    ),
    ("hqr0.java", include_str!("../theme/dex/stubs/p/hqr0.java")),
    ("zpo.java", include_str!("../theme/dex/stubs/p/zpo.java")),
    ("dsm1.java", include_str!("../theme/dex/stubs/p/dsm1.java")),
    ("s6e.java", include_str!("../theme/dex/stubs/p/s6e.java")),
    ("j360.java", include_str!("../theme/dex/stubs/p/j360.java")),
    ("w0x.java", include_str!("../theme/dex/stubs/p/w0x.java")),
    ("bp80.java", include_str!("../theme/dex/stubs/p/bp80.java")),
    ("tu70.java", include_str!("../theme/dex/stubs/p/tu70.java")),
    ("cu70.java", include_str!("../theme/dex/stubs/p/cu70.java")),
    ("tz71.java", include_str!("../theme/dex/stubs/p/tz71.java")),
    ("uz71.java", include_str!("../theme/dex/stubs/p/uz71.java")),
    ("oz71.java", include_str!("../theme/dex/stubs/p/oz71.java")),
    ("ifi0.java", include_str!("../theme/dex/stubs/p/ifi0.java")),
    ("jfi0.java", include_str!("../theme/dex/stubs/p/jfi0.java")),
    ("bfi0.java", include_str!("../theme/dex/stubs/p/bfi0.java")),
    ("np10.java", include_str!("../theme/dex/stubs/p/np10.java")),
    ("aj00.java", include_str!("../theme/dex/stubs/p/aj00.java")),
    ("q6k1.java", include_str!("../theme/dex/stubs/p/q6k1.java")),
    (
        "Editable.java",
        include_str!("../theme/dex/stubs/android/text/Editable.java"),
    ),
    (
        "TextWatcher.java",
        include_str!("../theme/dex/stubs/android/text/TextWatcher.java"),
    ),
    (
        "HandlerThread.java",
        include_str!("../theme/dex/stubs/android/os/HandlerThread.java"),
    ),
    (
        "Parcelable.java",
        include_str!("../theme/dex/stubs/android/os/Parcelable.java"),
    ),
    (
        "Handler.java",
        include_str!("../theme/dex/stubs/android/os/Handler.java"),
    ),
    (
        "Looper.java",
        include_str!("../theme/dex/stubs/android/os/Looper.java"),
    ),
    (
        "SizeF.java",
        include_str!("../theme/dex/stubs/android/util/SizeF.java"),
    ),
    (
        "PendingIntent.java",
        include_str!("../theme/dex/stubs/android/app/PendingIntent.java"),
    ),
    (
        "MediaMetadata.java",
        include_str!("../theme/dex/stubs/android/media/MediaMetadata.java"),
    ),
    (
        "PlaybackState.java",
        include_str!("../theme/dex/stubs/android/media/session/PlaybackState.java"),
    ),
    (
        "MediaSession.java",
        include_str!("../theme/dex/stubs/android/media/session/MediaSession.java"),
    ),
    (
        "MediaController.java",
        include_str!("../theme/dex/stubs/android/media/session/MediaController.java"),
    ),
    (
        "AppWidgetProviderInfo.java",
        include_str!("../theme/dex/stubs/android/appwidget/AppWidgetProviderInfo.java"),
    ),
    (
        "AppWidgetManager.java",
        include_str!("../theme/dex/stubs/android/appwidget/AppWidgetManager.java"),
    ),
    (
        "RemoteViews.java",
        include_str!("../theme/dex/stubs/android/widget/RemoteViews.java"),
    ),
    (
        "LinearGradient.java",
        include_str!("../theme/dex/stubs/android/graphics/LinearGradient.java"),
    ),
    (
        "ViewPropertyAnimator.java",
        include_str!("../theme/dex/stubs/android/view/ViewPropertyAnimator.java"),
    ),
    (
        "ProgressBar.java",
        include_str!("../theme/dex/stubs/android/widget/ProgressBar.java"),
    ),
    ("b971.java", include_str!("../theme/dex/stubs/p/b971.java")),
    ("ikb0.java", include_str!("../theme/dex/stubs/p/ikb0.java")),
    ("kat.java", include_str!("../theme/dex/stubs/p/kat.java")),
    ("v7s0.java", include_str!("../theme/dex/stubs/p/v7s0.java")),
    ("iw4.java", include_str!("../theme/dex/stubs/p/iw4.java")),
    ("lw4.java", include_str!("../theme/dex/stubs/p/lw4.java")),
    ("jsq0.java", include_str!("../theme/dex/stubs/p/jsq0.java")),
    (
        "Typeface.java",
        include_str!("../theme/dex/stubs/android/graphics/Typeface.java"),
    ),
    (
        "Gravity.java",
        include_str!("../theme/dex/stubs/android/view/Gravity.java"),
    ),
    (
        "AlertDialog.java",
        include_str!("../theme/dex/stubs/android/app/AlertDialog.java"),
    ),
    (
        "DialogInterface.java",
        include_str!("../theme/dex/stubs/android/content/DialogInterface.java"),
    ),
    ("cl61.java", include_str!("../theme/dex/stubs/p/cl61.java")),
    ("reb1.java", include_str!("../theme/dex/stubs/p/reb1.java")),
    ("knb1.java", include_str!("../theme/dex/stubs/p/knb1.java")),
    ("s9d1.java", include_str!("../theme/dex/stubs/p/s9d1.java")),
    ("q9d1.java", include_str!("../theme/dex/stubs/p/q9d1.java")),
    ("lu30.java", include_str!("../theme/dex/stubs/p/lu30.java")),
    ("h9d1.java", include_str!("../theme/dex/stubs/p/h9d1.java")),
    ("nvo1.java", include_str!("../theme/dex/stubs/p/nvo1.java")),
    ("cs61.java", include_str!("../theme/dex/stubs/p/cs61.java")),
    ("bs61.java", include_str!("../theme/dex/stubs/p/bs61.java")),
    ("bhh1.java", include_str!("../theme/dex/stubs/p/bhh1.java")),
    ("r9d1.java", include_str!("../theme/dex/stubs/p/r9d1.java")),
    ("j9d1.java", include_str!("../theme/dex/stubs/p/j9d1.java")),
    ("qeb1.java", include_str!("../theme/dex/stubs/p/qeb1.java")),
    ("r1i.java", include_str!("../theme/dex/stubs/p/r1i.java")),
    ("ap00.java", include_str!("../theme/dex/stubs/p/ap00.java")),
    ("lzf.java", include_str!("../theme/dex/stubs/p/lzf.java")),
    ("kp61.java", include_str!("../theme/dex/stubs/p/kp61.java")),
    ("qs61.java", include_str!("../theme/dex/stubs/p/qs61.java")),
    ("hoo1.java", include_str!("../theme/dex/stubs/p/hoo1.java")),
    ("b9.java", include_str!("../theme/dex/stubs/p/b9.java")),
    ("p280.java", include_str!("../theme/dex/stubs/p/p280.java")),
    ("vs61.java", include_str!("../theme/dex/stubs/p/vs61.java")),
    ("rtx.java", include_str!("../theme/dex/stubs/p/rtx.java")),
    ("cw21.java", include_str!("../theme/dex/stubs/p/cw21.java")),
    (
        "Intent.java",
        include_str!("../theme/dex/stubs/android/content/Intent.java"),
    ),
    (
        "ComponentName.java",
        include_str!("../theme/dex/stubs/android/content/ComponentName.java"),
    ),
    (
        "PackageManager.java",
        include_str!("../theme/dex/stubs/android/content/pm/PackageManager.java"),
    ),
    (
        "ScrollView.java",
        include_str!("../theme/dex/stubs/android/widget/ScrollView.java"),
    ),
    (
        "ResourcesLoader.java",
        include_str!("../theme/dex/stubs/android/content/res/loader/ResourcesLoader.java"),
    ),
    (
        "ResourcesProvider.java",
        include_str!("../theme/dex/stubs/android/content/res/loader/ResourcesProvider.java"),
    ),
    (
        "ParcelFileDescriptor.java",
        include_str!("../theme/dex/stubs/android/os/ParcelFileDescriptor.java"),
    ),
    (
        "AssetManager.java",
        include_str!("../theme/dex/stubs/android/content/res/AssetManager.java"),
    ),
    (
        "Visualizer.java",
        include_str!("../theme/dex/stubs/android/media/audiofx/Visualizer.java"),
    ),
    (
        "ContextWrapper.java",
        include_str!("../theme/dex/stubs/android/content/ContextWrapper.java"),
    ),
    (
        "Activity.java",
        include_str!("../theme/dex/stubs/android/app/Activity.java"),
    ),
    (
        "OnBackInvokedCallback.java",
        include_str!("../theme/dex/stubs/android/window/OnBackInvokedCallback.java"),
    ),
    (
        "OnBackInvokedDispatcher.java",
        include_str!("../theme/dex/stubs/android/window/OnBackInvokedDispatcher.java"),
    ),
    (
        "SharedPreferences.java",
        include_str!("../theme/dex/stubs/android/content/SharedPreferences.java"),
    ),
    (
        "DisplayMetrics.java",
        include_str!("../theme/dex/stubs/android/util/DisplayMetrics.java"),
    ),
    (
        "ColorStateList.java",
        include_str!("../theme/dex/stubs/android/content/res/ColorStateList.java"),
    ),
    (
        "GradientDrawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/GradientDrawable.java"),
    ),
    (
        "FrameLayout.java",
        include_str!("../theme/dex/stubs/android/widget/FrameLayout.java"),
    ),
    (
        "LinearLayout.java",
        include_str!("../theme/dex/stubs/android/widget/LinearLayout.java"),
    ),
    (
        "CompoundButton.java",
        include_str!("../theme/dex/stubs/android/widget/CompoundButton.java"),
    ),
    (
        "Switch.java",
        include_str!("../theme/dex/stubs/android/widget/Switch.java"),
    ),
    ("br20.java", include_str!("../theme/dex/stubs/p/br20.java")),
    ("jg81.java", include_str!("../theme/dex/stubs/p/jg81.java")),
    ("vtz.java", include_str!("../theme/dex/stubs/p/vtz.java")),
    ("dg81.java", include_str!("../theme/dex/stubs/p/dg81.java")),
    ("eg81.java", include_str!("../theme/dex/stubs/p/eg81.java")),
    ("ql81.java", include_str!("../theme/dex/stubs/p/ql81.java")),
    ("ig81.java", include_str!("../theme/dex/stubs/p/ig81.java")),
    ("rtx.java", include_str!("../theme/dex/stubs/p/rtx.java")),
    ("zsx.java", include_str!("../theme/dex/stubs/p/zsx.java")),
    ("cg81.java", include_str!("../theme/dex/stubs/p/cg81.java")),
    ("fg81.java", include_str!("../theme/dex/stubs/p/fg81.java")),
    ("ft01.java", include_str!("../theme/dex/stubs/p/ft01.java")),
    ("w2b1.java", include_str!("../theme/dex/stubs/p/w2b1.java")),
    ("z2b1.java", include_str!("../theme/dex/stubs/p/z2b1.java")),
    (
        "ViewParent.java",
        include_str!("../theme/dex/stubs/android/view/ViewParent.java"),
    ),
    (
        "TextView.java",
        include_str!("../theme/dex/stubs/android/widget/TextView.java"),
    ),
    (
        "SeekBar.java",
        include_str!("../theme/dex/stubs/android/widget/SeekBar.java"),
    ),
    (
        "AudioManager.java",
        include_str!("../theme/dex/stubs/android/media/AudioManager.java"),
    ),
    (
        "KeyEvent.java",
        include_str!("../theme/dex/stubs/android/view/KeyEvent.java"),
    ),
    (
        "MotionEvent.java",
        include_str!("../theme/dex/stubs/android/view/MotionEvent.java"),
    ),
    (
        "Context.java",
        include_str!("../theme/dex/stubs/android/content/Context.java"),
    ),
    (
        "Resources.java",
        include_str!("../theme/dex/stubs/android/content/res/Resources.java"),
    ),
    (
        "AttributeSet.java",
        include_str!("../theme/dex/stubs/android/util/AttributeSet.java"),
    ),
    (
        "View.java",
        include_str!("../theme/dex/stubs/android/view/View.java"),
    ),
    (
        "ViewGroup.java",
        include_str!("../theme/dex/stubs/android/view/ViewGroup.java"),
    ),
    (
        "ImageView.java",
        include_str!("../theme/dex/stubs/android/widget/ImageView.java"),
    ),
    (
        "Shader.java",
        include_str!("../theme/dex/stubs/android/graphics/Shader.java"),
    ),
    (
        "RadialGradient.java",
        include_str!("../theme/dex/stubs/android/graphics/RadialGradient.java"),
    ),
    (
        "RectF.java",
        include_str!("../theme/dex/stubs/android/graphics/RectF.java"),
    ),
    (
        "Bitmap.java",
        include_str!("../theme/dex/stubs/android/graphics/Bitmap.java"),
    ),
    (
        "Canvas.java",
        include_str!("../theme/dex/stubs/android/graphics/Canvas.java"),
    ),
    (
        "Paint.java",
        include_str!("../theme/dex/stubs/android/graphics/Paint.java"),
    ),
    (
        "Path.java",
        include_str!("../theme/dex/stubs/android/graphics/Path.java"),
    ),
    (
        "Rect.java",
        include_str!("../theme/dex/stubs/android/graphics/Rect.java"),
    ),
    (
        "ColorFilter.java",
        include_str!("../theme/dex/stubs/android/graphics/ColorFilter.java"),
    ),
    (
        "PixelFormat.java",
        include_str!("../theme/dex/stubs/android/graphics/PixelFormat.java"),
    ),
    (
        "Drawable.java",
        include_str!("../theme/dex/stubs/android/graphics/drawable/Drawable.java"),
    ),
    (
        "SystemClock.java",
        include_str!("../theme/dex/stubs/android/os/SystemClock.java"),
    ),
    (
        "Build.java",
        include_str!("../theme/dex/stubs/android/os/Build.java"),
    ),
    (
        "ValueAnimator.java",
        include_str!("../theme/dex/stubs/android/animation/ValueAnimator.java"),
    ),
    ("rhm0.java", include_str!("../theme/dex/stubs/p/rhm0.java")),
    ("bv61.java", include_str!("../theme/dex/stubs/p/bv61.java")),
    ("v7n.java", include_str!("../theme/dex/stubs/p/v7n.java")),
    ("vp31.java", include_str!("../theme/dex/stubs/p/vp31.java")),
    ("xp31.java", include_str!("../theme/dex/stubs/p/xp31.java")),
    ("mtg.java", include_str!("../theme/dex/stubs/p/mtg.java")),
    ("cxn1.java", include_str!("../theme/dex/stubs/p/cxn1.java")),
    ("n191.java", include_str!("../theme/dex/stubs/p/n191.java")),
    ("fwq.java", include_str!("../theme/dex/stubs/p/fwq.java")),
];
