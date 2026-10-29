//! Guarded smali edits for the Now Playing controls plus the spatch.Scallop helper DEX.
//!
//! Only classes3.dex and classes9.dex are reassembled; the helper ships as a new classes16.dex.
//! classes9.dex is at the 65,536 method-reference limit, so its edits add field refs and constants only.

use crate::Result;
use crate::input;
use crate::theme::Theme;
use crate::tools::Tools;
use std::ffi::OsStr;
use std::fs;
use std::path::Path;

/// Original DEX entries that get reassembled, with their pinned SHA-256.
pub const DEX_TARGETS: [(&str, &str); 8] = [
    (
        "classes12.dex",
        "7b4424837bf4395e524dfe63c43bdc390756c3f8154a7fe3bc424edf0aab666d",
    ),
    (
        "classes4.dex",
        "f828186226fbb3f25f442f5a9a2a02f3830360abdfe71eae1e8875095d56d73b",
    ),
    (
        "classes2.dex",
        "ab778e21f6e76990f9cb6e9cac223e1efa9b3d6064b4400d5d039bcd1408f836",
    ),
    (
        "classes.dex",
        "fcbf149d55c9dfec9cae0f5a3f57a117ccf93a82e9ec0ba2418278b2c58c7db0",
    ),
    (
        "classes3.dex",
        "674c59d561e55247e3706d0d26d5ea9fccfd0ed363fcbf1ea83a7fd486fb839a",
    ),
    (
        "classes9.dex",
        "6103cda1ad35bfc69af12950b9dadd1363e61e078e92db2af0c9b5b868929685",
    ),
    (
        "classes8.dex",
        "b15fb9d6e63eb89b2dc4372a4b1fd31fff12072cb7b709c2f82c73c07a78de8e",
    ),
    (
        "classes11.dex",
        "4182af06a411edb9a02a1f9d104dd1bdfdc7b31d197fe8da6803aa6f58aafb90",
    ),
];
/// Next free multidex slot (base ships classes.dex..classes15.dex).
pub const HELPER_DEX: &str = "classes16.dex";
const MAX_REFS: u32 = 65536;

pub struct Edit {
    pub file: &'static str,
    pub anchor: &'static str,
    pub replacement: &'static str,
}

pub const EDITS: [Edit; 36] = [
    Edit { file: "smali_classes4/p/y2p1.smali", anchor: ".method public static final f(J)J\n    .locals 1\n\n    .line 1\n", replacement: ".method public static final f(J)J\n    .locals 1\n\n    .line 1\n    invoke-static {p0, p1}, Lspatch/Palette;->compose(J)J\n\n    move-result-wide p0\n\n" },
    Edit { file: "smali_classes12/p/e1b1.smali", anchor: "    invoke-static {p0}, Lcom/google/android/play/core/splitcompat/SplitCompat;->install(Landroid/content/Context;)Z\n", replacement: "    invoke-static {p0}, Lcom/google/android/play/core/splitcompat/SplitCompat;->install(Landroid/content/Context;)Z\n\n    invoke-static {p0}, Lspatch/Settings;->load(Landroid/content/Context;)V\n" },

    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: ".method public onPaused(ZI)V\n    .locals 0\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: ".method public onPaused(ZI)V\n    .locals 0\n\n    invoke-static {p1, p2}, Lspatch/AudioSpectrum;->paused(ZI)V\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result p1\n\n    .line 5\n    if-lez p1, :cond_0", replacement: "    move-result p1\n\n    invoke-static {p1}, Lspatch/AudioSpectrum;->created(I)V\n\n    .line 5\n    if-lez p1, :cond_0" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result v1\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: "    move-result v1\n\n    invoke-static {v1}, Lspatch/AudioSpectrum;->destroyed(I)V\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
    // Encore's current Compose artwork shape (eqo1 and its callers), distinct from the
    // legacy n37/yzb path. Keep its 33dp size breakpoint; round large art to 16dp,
    // small thumbnails to 8dp. Constants only: no new DEX references.
    Edit {
        file: "smali/p/kvx.smali",
        anchor: "    const/high16 p3, 0x40800000    # 4.0f\n",
        replacement: "    const/high16 p3, 0x41800000    # 16.0f\n",
    },
    Edit {
        file: "smali/p/kvx.smali",
        anchor: "    const/high16 p3, 0x40000000    # 2.0f\n",
        replacement: "    const/high16 p3, 0x41000000    # 8.0f\n",
    },
    // p.ggx (classes.dex): Encore Compose palette. Each colour is loaded once and reused through registers,
    // so one constant swap recolours every set that shares it. Constant swaps add no references.
    // base background
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v2, 0xff121212L\n",
        replacement: "    const-wide v2, 0xff14121cL\n",
    },
    // elevated base
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v2, 0xff1f1f1fL\n",
        replacement: "    const-wide v2, 0xff1d1a2aL\n",
    },
    // elevated highlight
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v4, 0xff2a2a2aL\n",
        replacement: "    const-wide v4, 0xff2b2640L\n",
    },
    // subdued text
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v7, 0xffb3b3b3L\n",
        replacement: "    const-wide v7, 0xffbcb3dcL\n",
    },
    // bright accent (also reused for the positive set)
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v7, 0xff1ed760L\n",
        replacement: "    const-wide v7, 0xffd0bcffL\n",
    },
    // bright accent highlight
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v3, 0xff3be477L\n",
        replacement: "    const-wide v3, 0xffe0d2ffL\n",
    },
    // bright accent press
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v5, 0xff1abc54L\n",
        replacement: "    const-wide v5, 0xffb7a3eeL\n",
    },
    // p.c3v (classes.dex): Compose CornerSize(dp). Corners <= 12dp are doubled (4->8, 6->12, 8->16,
    // 12->24, Spatch's 8/12/17/23 radius scale); larger radii, pills and NaN keep their value.
    Edit {
        file: "smali/p/c3v.smali",
        anchor: "    .locals 0\n\n    .line 1\n    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n\n    .line 2\n    .line 3\n    .line 4\n    iput p1, p0, Lp/c3v;->a:F\n",
        replacement: "    .locals 1\n\n    .line 1\n    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n\n    const/high16 v0, 0x41400000    # 12.0f\n\n    cmpl-float v0, p1, v0\n\n    if-gtz v0, :spatch_keep\n\n    const/high16 v0, 0x40000000    # 2.0f\n\n    mul-float/2addr p1, v0\n\n    :spatch_keep\n    iput p1, p0, Lp/c3v;->a:F\n",
    },
    // p.psn0.a() (classes2): the bottom-navigation tab icon's Modifier.size(24dp) call becomes
    // spatch.Pill.a(modifier, selected), which adds the selected-tab pill and then sizes the icon.
    // classes2 has 65,535 method refs, so this single new reference fills the last slot.
    Edit {
        file: "smali_classes2/p/psn0.smali",
        anchor: "    const/high16 p3, 0x41c00000    # 24.0f\n\n    .line 80\n    .line 81\n    invoke-static {p4, p3}, Lp/n191;->r(Lp/rhm0;F)Lp/rhm0;\n",
        replacement: "    iget-boolean p3, p0, Lp/psn0;->g:Z\n\n    .line 80\n    .line 81\n    invoke-static {p4, p3}, Lspatch/Pill;->a(Lp/rhm0;Z)Lp/rhm0;\n",
    },
    // p.lbg (classes3): per-instance bump count, 0 = original circle (p.o2a keeps that).
    Edit {
        file: "smali_classes3/p/lbg.smali",
        anchor: ".field public g:I\n",
        replacement: ".field public g:I\n\n.field public spatchBumps:I\n",
    },
    // v6 is reset to 0.0f afterwards because the stroke-width comparison below reuses it.
    Edit {
        file: "smali_classes3/p/lbg.smali",
        anchor: "    invoke-virtual {p1, v6, v6, v3, v5}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V\n",
        replacement: "    iget v6, p0, Lp/lbg;->spatchBumps:I

    if-lez v6, :spatch_circle

    invoke-static {p1, v3, v5, v6, p0}, Lspatch/Scallop;->draw(Landroid/graphics/Canvas;FLandroid/graphics/Paint;ILandroid/graphics/drawable/Drawable;)V

    goto :spatch_done

    :spatch_circle
    const/4 v6, 0x0

    invoke-virtual {p1, v6, v6, v3, v5}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    :spatch_done
    const/4 v6, 0x0
",
    },
    // p.lot0 (classes9): play icon (shown while paused) gets 5 bumps; v6 is overwritten right after.
    Edit {
        file: "smali_classes9/p/lot0.smali",
        anchor: "    iput-object v4, p0, Lp/lot0;->a:Lp/lbg;\n",
        replacement: "    const/4 v6, 0x5\n\n    iput v6, v4, Lp/lbg;->spatchBumps:I\n\n    iput-object v4, p0, Lp/lot0;->a:Lp/lbg;\n",
    },
    // Pause icon (shown while playing) gets 9 scallops; v2 is overwritten right after.
    Edit {
        file: "smali_classes9/p/lot0.smali",
        anchor: "    iput-object v0, p0, Lp/lot0;->b:Lp/lbg;\n",
        replacement: "    const/16 v2, 0x9\n\n    iput v2, v0, Lp/lbg;->spatchBumps:I\n\n    iput-object v0, p0, Lp/lot0;->b:Lp/lbg;\n",
    },
    // Compact mode (got0: mini-player, live-room bar, video-first player) showed bare 24dp icons from
    // fields c/d; read the scalloped lbg fields b/a instead. Existing field refs only, no new method refs.
    Edit {
        file: "smali_classes9/p/lot0.smali",
        anchor: "    iget-object p2, p0, Lp/lot0;->d:Lp/w2b1;\n",
        replacement: "    iget-object p2, p0, Lp/lot0;->b:Lp/lbg;\n",
    },
    Edit {
        file: "smali_classes9/p/lot0.smali",
        anchor: "    iget-object p2, p0, Lp/lot0;->c:Lp/w2b1;\n",
        replacement: "    iget-object p2, p0, Lp/lot0;->a:Lp/lbg;\n",
    },
    // p.ews (classes9): progress tint default_seekbar_color -> m3_ref_palette_primary80 (#D0BCFF).
    Edit {
        file: "smali_classes9/p/ews.smali",
        anchor: "    const v1, 0x7f06066c\n",
        // dark_base_essential_brightaccent (#1ED760), which the arsc swap maps to the theme accent.
        replacement: "    const v1, 0x7f0605f6\n",
    },
    // Profile side drawer: insert Spatch row above Settings and privacy, handle URI, and display "Spatch".
    Edit {
        file: "smali_classes11/p/hf81.smali",
        anchor: "    iput-object p6, p0, Lp/hf81;->f:Ljava/util/List;\n",
        replacement: "    invoke-static {p6}, Lspatch/Settings;->rows(Ljava/util/List;)Ljava/util/List;\n\n    move-result-object p6\n\n    iput-object p6, p0, Lp/hf81;->f:Ljava/util/List;\n",
    },
    Edit {
        file: "smali_classes11/p/hf81.smali",
        anchor: "    invoke-direct {v0, p0, v1, p1}, Lp/gf81;-><init>(Lp/hf81;Landroid/content/Context;Lp/ag81;)V\n",
        replacement: "    invoke-direct {v0, p0, v1, p1}, Lp/gf81;-><init>(Lp/hf81;Landroid/content/Context;Lp/ag81;)V\n\n    invoke-static {v0}, Lspatch/Settings;->attach(Landroid/view/View;)V\n",
    },
    Edit {
        file: "smali_classes8/p/g7y0.smali",
        anchor: "    const/4 v2, 0x0\n\n    .line 1197\n    invoke-static {v0, v1, v3, v2}, Lp/jon1;->r(Lp/lyn0;Ljava/lang/String;Lp/cqw;Landroid/os/Bundle;)V\n",
        replacement: "    invoke-static {v1}, Lspatch/Settings;->open(Ljava/lang/String;)Z\n\n    move-result v2\n\n    if-nez v2, :spatch_open\n\n    const/4 v2, 0x0\n\n    .line 1197\n    invoke-static {v0, v1, v3, v2}, Lp/jon1;->r(Lp/lyn0;Ljava/lang/String;Lp/cqw;Landroid/os/Bundle;)V\n\n    :spatch_open\n",
    },
    Edit {
        file: "smali_classes11/p/yg81.smali",
        anchor: "    .line 55\n    invoke-static {v1, v0}, Lp/pul1;->o0(Lp/pzj;I)Ljava/lang/String;\n\n    .line 56\n    .line 57\n    .line 58\n    move-result-object v7\n",
        replacement: "    .line 55\n    invoke-static {v1, v0}, Lp/pul1;->o0(Lp/pzj;I)Ljava/lang/String;\n\n    .line 56\n    .line 57\n    .line 58\n    move-result-object v7\n\n    const v3, 0x7f131b75\n\n    if-ne v0, v3, :spatch_not_custom\n\n    const-string v7, \"Spatch\"\n\n    :spatch_not_custom\n",
    },
    // Search tab search bar (p.bqn1.e, "search_bar_root"): Spotify's white 4dp box becomes a
    // Spatch field: highlight surface, pill shape, light icon and subdued hint text. Colours go
    // through y2p1.f (existing reference; Palette maps the role tokens at runtime).
    Edit {
        file: "smali_classes4/p/bqn1.smali",
        anchor: "    sget-wide v9, Lp/i7h;->f:J\n",
        replacement: "    const-wide v9, 0xff2b2640L\n\n    invoke-static {v9, v10}, Lp/y2p1;->f(J)J\n\n    move-result-wide v9\n",
    },
    Edit {
        file: "smali_classes4/p/bqn1.smali",
        anchor: "    const/high16 v11, 0x40800000    # 4.0f\n",
        replacement: "    const/high16 v11, 0x41c00000    # 24.0f\n",
    },
    Edit {
        file: "smali_classes4/p/bqn1.smali",
        anchor: "    iget-wide v9, v9, Lp/gex;->g:J\n",
        replacement: "    const-wide v9, 0xfff3eeffL\n\n    invoke-static {v9, v10}, Lp/y2p1;->f(J)J\n\n    move-result-wide v9\n",
    },
    Edit {
        file: "smali_classes4/p/bqn1.smali",
        anchor: "    iget-wide v7, v4, Lp/jex;->e:J\n",
        replacement: "    const-wide v7, 0xffbcb3dcL\n\n    invoke-static {v7, v8}, Lp/y2p1;->f(J)J\n\n    move-result-wide v7\n",
    },
    // Playlist "mix" play button (p.f1p1.m, "mixing_play_button_simple_green", classes4): its
    // background is clipped to the player's scallop instead of a circle (one new field ref).
    Edit {
        file: "smali_classes4/p/f1p1.smali",
        anchor: "    sget-object v5, Lp/xp31;->a:Lp/vp31;\n",
        replacement: "    sget-object v5, Lspatch/ScallopShape;->PLAY:Lspatch/ScallopShape;\n",
    },
    // Widget: Glance's hand-off of the rendered RemoteViews (p.rx5, classes.dex) asks
    // spatch.SpatchWidget first; it returns Spatch's views only for the Now Playing widget.
    Edit {
        file: "smali/p/rx5.smali",
        anchor: "    invoke-virtual {v1, v4, v0}, Landroid/appwidget/AppWidgetManager;->updateAppWidget(ILandroid/widget/RemoteViews;)V\n",
        replacement: "    invoke-static {v4, v0}, Lspatch/SpatchWidget;->replace(ILandroid/widget/RemoteViews;)Landroid/widget/RemoteViews;\n\n    move-result-object v0\n\n    invoke-virtual {v1, v4, v0}, Landroid/appwidget/AppWidgetManager;->updateAppWidget(ILandroid/widget/RemoteViews;)V\n",
    },
    // Widget: every framework MediaSession Spotify constructs (the outlined constructor sites outside classes4, which has no method-reference room) is
    // followed by spatch.SpatchWidget for title, artist, art and play state.
    Edit {
        file: "smali/p/bxh0.smali",
        anchor: "    invoke-direct {p0, p1, p2}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;)V\n",
        replacement: "    invoke-direct {p0, p1, p2}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;)V\n\n    invoke-static {p0}, Lspatch/SpatchWidget;->attach(Landroid/media/session/MediaSession;)V\n",
    },
    Edit {
        file: "smali_classes3/p/x05.smali",
        anchor: "    invoke-direct {v0, p0, p2, p1}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;)V\n",
        replacement: "    invoke-direct {v0, p0, p2, p1}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;)V\n\n    invoke-static {v0}, Lspatch/SpatchWidget;->attach(Landroid/media/session/MediaSession;)V\n",
    },
    Edit {
        file: "smali_classes3/p/x05.smali",
        anchor: "    invoke-direct {v0, p0, p1, v1}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;)V\n",
        replacement: "    invoke-direct {v0, p0, p1, v1}, Landroid/media/session/MediaSession;-><init>(Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;)V\n\n    invoke-static {v0}, Lspatch/SpatchWidget;->attach(Landroid/media/session/MediaSession;)V\n",
    },
];

/// Original classes moved into `HELPER_DEX` (smali folder, file, original DEX, descriptor) because
/// their own DEX has no room for the references their edits add; behaviour and names are unchanged.
const RELOCATED: [(&str, &str, &str, &str); 4] = [
    (
        "smali_classes2",
        "com/spotify/playbacknative/AudioEffectsListener.smali",
        "classes2.dex",
        "Lcom/spotify/playbacknative/AudioEffectsListener;",
    ),
    ("smali_classes4", "p/y2p1.smali", "classes4.dex", "Lp/y2p1;"),
    // Widget hooks: Glance's RemoteViews hand-off and an outlined MediaSession constructor.
    ("smali", "p/rx5.smali", "classes.dex", "Lp/rx5;"),
    ("smali", "p/bxh0.smali", "classes.dex", "Lp/bxh0;"),
];

/// Helper classes (theme tokens substituted before compiling), shipped together in `HELPER_DEX`.
const HELPERS: [(&str, &str); 19] = [
    (
        "SpatchWidget",
        include_str!("../theme/dex/src/spatch/SpatchWidget.java"),
    ),
    (
        "MiniArt",
        include_str!("../theme/dex/src/spatch/MiniArt.java"),
    ),
    (
        "ScallopFill",
        include_str!("../theme/dex/src/spatch/ScallopFill.java"),
    ),
    (
        "ScallopShape",
        include_str!("../theme/dex/src/spatch/ScallopShape.java"),
    ),
    (
        "Palette",
        include_str!("../theme/dex/src/spatch/Palette.java"),
    ),
    (
        "PaletteResources",
        include_str!("../theme/dex/src/spatch/PaletteResources.java"),
    ),
    (
        "ImmersiveButton",
        include_str!("../theme/dex/src/spatch/ImmersiveButton.java"),
    ),
    (
        "AudioSpectrum",
        include_str!("../theme/dex/src/spatch/AudioSpectrum.java"),
    ),
    (
        "SpatchSettings",
        include_str!("../theme/dex/src/spatch/SpatchSettings.java"),
    ),
    ("Back", include_str!("../theme/dex/src/spatch/Back.java")),
    (
        "Settings",
        include_str!("../theme/dex/src/spatch/Settings.java"),
    ),
    (
        "TapBounce",
        include_str!("../theme/dex/src/spatch/TapBounce.java"),
    ),
    (
        "PlayerArt",
        include_str!("../theme/dex/src/spatch/PlayerArt.java"),
    ),
    (
        "Atmosphere",
        include_str!("../theme/dex/src/spatch/Atmosphere.java"),
    ),
    (
        "Transport",
        include_str!("../theme/dex/src/spatch/Transport.java"),
    ),
    (
        "Scallop",
        include_str!("../theme/dex/src/spatch/Scallop.java"),
    ),
    ("Wave", include_str!("../theme/dex/src/spatch/Wave.java")),
    ("Pill", include_str!("../theme/dex/src/spatch/Pill.java")),
    (
        "Immersive",
        include_str!("../theme/dex/src/spatch/Immersive.java"),
    ),
];
const STUBS: &[(&str, &str)] = &[
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
        include_str!("../theme/dex/stubs/LinearGradient.java"),
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
        include_str!("../theme/dex/stubs/Context.java"),
    ),
    (
        "Resources.java",
        include_str!("../theme/dex/stubs/Resources.java"),
    ),
    (
        "AttributeSet.java",
        include_str!("../theme/dex/stubs/AttributeSet.java"),
    ),
    ("View.java", include_str!("../theme/dex/stubs/View.java")),
    (
        "ViewGroup.java",
        include_str!("../theme/dex/stubs/ViewGroup.java"),
    ),
    (
        "ImageView.java",
        include_str!("../theme/dex/stubs/ImageView.java"),
    ),
    (
        "Shader.java",
        include_str!("../theme/dex/stubs/Shader.java"),
    ),
    (
        "RadialGradient.java",
        include_str!("../theme/dex/stubs/RadialGradient.java"),
    ),
    ("RectF.java", include_str!("../theme/dex/stubs/RectF.java")),
    (
        "Bitmap.java",
        include_str!("../theme/dex/stubs/Bitmap.java"),
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
];

pub fn check_originals(base: &Path) -> Result<()> {
    for (entry, sha) in DEX_TARGETS {
        let got = input::sha256_bytes(&input::read_entry(base, entry)?);
        if got != sha {
            return Err(
                format!("{entry} does not match the supported original (got {got})").into(),
            );
        }
    }
    if input::read_entry(base, HELPER_DEX).is_ok() {
        return Err(format!("{HELPER_DEX} already present; input is already patched").into());
    }
    Ok(())
}

/// Replaces the single occurrence of `anchor`; zero or several occurrences is a hard error.
pub fn apply(text: &str, edit: &Edit, theme: &Theme) -> Result<String> {
    match text.matches(edit.anchor).count() {
        1 => Ok(text.replacen(edit.anchor, &theme.apply(edit.replacement), 1)),
        n => Err(format!(
            "{}: expected 1 anchor, found {n}: {:?}",
            edit.file,
            edit.anchor.trim()
        )
        .into()),
    }
}

/// Returns the reassembled edited DEX (see `DEX_TARGETS`) and the new helper DEX.
pub fn build(
    base: &Path,
    staging: &Path,
    tools: &Tools,
    theme: &Theme,
) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let fw = staging.join("framework");
    let smali = staging.join("smali");
    let built = staging.join("dex.apk");
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("d"),
            OsStr::new("-r"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            smali.as_os_str(),
            base.as_os_str(),
        ],
    )?;
    for e in &EDITS {
        let path = smali.join(e.file);
        fs::write(&path, apply(&fs::read_to_string(&path)?, e, theme)?)?;
    }
    // Their original DEX tables are full. Keep these classes' names and behavior in
    // the helper DEX so the new references they gain do not overflow them.
    for (folder, name, _, _) in RELOCATED {
        let relocated = smali.join("smali_classes16").join(name);
        fs::create_dir_all(relocated.parent().unwrap())?;
        fs::rename(smali.join(folder).join(name), relocated)?;
    }
    // Only the edited DEX need reassembling; the rest are taken from the original APK unchanged.
    for d in fs::read_dir(&smali)? {
        let d = d?;
        let name = d.file_name();
        if name.to_string_lossy().starts_with("smali")
            && name != "smali"
            && name != "smali_classes2"
            && name != "smali_classes3"
            && name != "smali_classes4"
            && name != "smali_classes8"
            && name != "smali_classes9"
            && name != "smali_classes11"
            && name != "smali_classes12"
            && name != "smali_classes16"
        {
            fs::remove_dir_all(d.path())?;
        }
    }
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("b"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            built.as_os_str(),
            smali.as_os_str(),
        ],
    )?;

    let mut out = Vec::new();
    for (entry, _) in DEX_TARGETS {
        let bytes = input::read_entry(&built, entry)?;
        let path = staging.join(entry);
        fs::write(&path, &bytes)?;
        let dump = tools.run(&tools.dexdump, &[OsStr::new("-f"), path.as_os_str()])?;
        check_ref_limits(entry, &dump)?;
        let still_here = RELOCATED.iter().any(|(_, _, dex, class)| {
            *dex == entry
                && dump
                    .lines()
                    .any(|line| line.trim().starts_with("Class descriptor") && line.contains(class))
        });
        if still_here {
            return Err(
                "relocated classes must have exactly one definition in the helper DEX".into(),
            );
        }
        out.push((entry, bytes));
    }
    let relocated_dex = staging.join("audio-listener.dex");
    fs::write(&relocated_dex, input::read_entry(&built, HELPER_DEX)?)?;
    out.push((
        HELPER_DEX,
        compile_helper(staging, tools, theme, &relocated_dex)?,
    ));
    Ok(out)
}

fn helper_source(source: &str, theme: &Theme) -> String {
    if source.contains("@PALETTES@") {
        let rows: Vec<_> = crate::theme::THEMES
            .iter()
            .map(|t| {
                format!(
                    "{{{}}}",
                    t.rgb
                        .iter()
                        .map(|c| format!("0x{c:06x}"))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            })
            .collect();
        return theme
            .apply(source)
            .replace("@THEME@", theme.name)
            .replace("@PALETTES@", &rows.join(","));
    }
    let mut out = String::new();
    let mut p = 0;
    while p < source.len() {
        if source[p..].starts_with("0x") {
            let end = p
                + 2
                + source[p + 2..]
                    .bytes()
                    .take_while(u8::is_ascii_hexdigit)
                    .count();
            if let Ok(value) = u32::from_str_radix(&source[p + 2..end], 16)
                && crate::theme::THEMES[0].rgb.contains(&(value & 0xffffff))
            {
                out.push_str("Palette.color(");
                out.push_str(&source[p..end]);
                out.push(')');
                p = end;
                continue;
            }
        }
        let ch = source[p..].chars().next().unwrap();
        out.push(ch);
        p += ch.len_utf8();
    }
    theme.apply(&out)
}

/// javac against compile-only stubs, then d8; the stubs are passed as a library, never packaged.
fn compile_helper(
    staging: &Path,
    tools: &Tools,
    theme: &Theme,
    listener_dex: &Path,
) -> Result<Vec<u8>> {
    let root = staging.join("helper");
    let (stub_src, stub_cls, cls, dex) = (
        root.join("stubs-src"),
        root.join("stubs"),
        root.join("classes"),
        root.join("dex"),
    );
    for d in [&stub_src, &stub_cls, &cls, &dex] {
        fs::create_dir_all(d)?;
    }
    let mut javac_stubs = vec![
        OsStr::new("--release"),
        OsStr::new("11"),
        OsStr::new("-d"),
        stub_cls.as_os_str(),
    ];
    let stub_paths: Vec<_> = STUBS.iter().map(|(n, _)| stub_src.join(n)).collect();
    for ((_, src), p) in STUBS.iter().zip(&stub_paths) {
        fs::write(p, src)?;
        javac_stubs.push(p.as_os_str());
    }
    let javac = tools.java_home.join("bin/javac");
    tools.run(&javac, &javac_stubs)?;
    let mut javac_helpers = vec![
        OsStr::new("--release"),
        OsStr::new("11"),
        OsStr::new("-cp"),
        stub_cls.as_os_str(),
        OsStr::new("-d"),
        cls.as_os_str(),
    ];
    let helper_paths: Vec<_> = HELPERS
        .iter()
        .map(|(n, _)| root.join(format!("{n}.java")))
        .collect();
    for ((_, src), p) in HELPERS.iter().zip(&helper_paths) {
        fs::write(p, helper_source(src, theme).replace("@THEME@", theme.name))?;
        javac_helpers.push(p.as_os_str());
    }
    tools.run(&javac, &javac_helpers)?;
    let stub_jar = root.join("stubs.jar");
    tools.run(
        &tools.java_home.join("bin/jar"),
        &[
            OsStr::new("cf"),
            stub_jar.as_os_str(),
            OsStr::new("-C"),
            stub_cls.as_os_str(),
            OsStr::new("."),
        ],
    )?;
    let class_paths: Vec<_> = HELPERS
        .iter()
        .map(|(n, _)| cls.join(format!("spatch/{n}.class")))
        .collect();
    let mut d8 = vec![
        OsStr::new("--release"),
        OsStr::new("--min-api"),
        OsStr::new("24"),
        OsStr::new("--lib"),
        stub_jar.as_os_str(),
        OsStr::new("--output"),
        dex.as_os_str(),
    ];
    d8.push(listener_dex.as_os_str());
    d8.extend(class_paths.iter().map(|p| p.as_os_str()));
    tools.run(&tools.d8, &d8)?;
    let out = dex.join("classes.dex");
    let classes = tools.run(&tools.dexdump, &[OsStr::new("-f"), out.as_os_str()])?;
    let all_present = HELPERS
        .iter()
        .all(|(n, _)| classes.contains(&format!("'Lspatch/{n};'")));
    if classes.matches("Class descriptor").count() != HELPERS.len() + RELOCATED.len()
        || !all_present
        || !RELOCATED
            .iter()
            .all(|(_, _, _, class)| classes.contains(&format!("'{class}'")))
    {
        return Err(
            "helper DEX must contain exactly the spatch helpers and the relocated original classes"
                .into(),
        );
    }
    check_ref_limits(HELPER_DEX, &classes)?;
    Ok(fs::read(out)?)
}

/// Fails if a reassembled DEX exceeds the method or field reference limit.
pub fn check_ref_limits(entry: &str, dexdump: &str) -> Result<()> {
    for key in ["method_ids_size", "field_ids_size"] {
        let n: u32 = dexdump
            .lines()
            .find_map(|l| {
                l.trim()
                    .strip_prefix(key)?
                    .trim()
                    .strip_prefix(':')?
                    .trim()
                    .parse()
                    .ok()
            })
            .ok_or_else(|| format!("{entry}: dexdump did not report {key}"))?;
        if n > MAX_REFS {
            return Err(format!("{entry}: {key} {n} exceeds {MAX_REFS}").into());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edits_require_exactly_one_anchor() {
        let lav = crate::theme::by_name("lavender").unwrap();
        let e = EDITS
            .iter()
            .find(|e| e.anchor.contains("0x7f06066c"))
            .unwrap();
        assert_eq!(
            apply("x\n    const v1, 0x7f06066c\ny", e, lav).unwrap(),
            "x\n    const v1, 0x7f0605f6\ny"
        );
        assert!(apply("nothing", e, lav).is_err());
        assert!(
            apply(
                "    const v1, 0x7f06066c\n    const v1, 0x7f06066c\n",
                e,
                lav
            )
            .is_err()
        );
        // Re-applying to patched text fails, so a mixed/already-patched tree is rejected.
        assert!(apply(e.replacement, e, lav).is_err());
    }

    #[test]
    fn artwork_shape_edits_reject_missing_ambiguous_and_repeated_inputs() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        for edit in EDITS.iter().filter(|e| e.file == "smali/p/kvx.smali") {
            assert!(apply("", edit, theme).is_err());
            assert!(apply(&edit.anchor.repeat(2), edit, theme).is_err());
            let patched = apply(edit.anchor, edit, theme).unwrap();
            assert_eq!(patched, edit.replacement);
            assert!(apply(&patched, edit, theme).is_err());
        }
    }

    #[test]
    fn audio_session_hooks_reject_missing_ambiguous_and_repeated_inputs() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        let edits: Vec<_> = EDITS
            .iter()
            .filter(|e| e.file.ends_with("AudioEffectsListener.smali"))
            .collect();
        assert_eq!(edits.len(), 3);
        for edit in edits {
            assert!(apply("", edit, theme).is_err());
            assert!(apply(&edit.anchor.repeat(2), edit, theme).is_err());
            let patched = apply(edit.anchor, edit, theme).unwrap();
            assert_eq!(patched, edit.replacement);
            assert!(apply(&patched, edit, theme).is_err());
        }
    }

    #[test]
    fn runtime_palette_source_keeps_all_palettes_and_maps_role_literals() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        assert_eq!(
            helper_source("int c=0x80d0bcff; int id=0x7f123456;", theme),
            "int c=Palette.color(0x80fe8019); int id=0x7f123456;"
        );
        let source = helper_source(include_str!("../theme/dex/src/spatch/Palette.java"), theme);
        assert!(!source.contains("@PALETTES@"));
        assert!(!source.contains("@THEME@"));
        assert!(source.contains("active=index(\"gruvbox\")"));
        assert!(source.contains("{0x14121c,0x1d1a2a"));
        assert!(source.contains("{0x282828,0x3c3836"));
        assert!(source.contains("BASE={0x282828"));
    }

    #[test]
    fn every_helper_class_called_by_an_edit_is_built() {
        // A patched call into a helper that is not compiled crashes at runtime (NoClassDefFoundError).
        for e in &EDITS {
            for (i, _) in e.replacement.match_indices("Lspatch/") {
                let name = &e.replacement[i + 8..i + e.replacement[i..].find(';').unwrap()];
                assert!(
                    HELPERS.iter().any(|(n, _)| *n == name),
                    "{name} missing from HELPERS"
                );
            }
        }
    }

    #[test]
    fn custom_player_view_is_packaged_and_keeps_split_dimensions() {
        let layout = crate::patch::TARGETS
            .iter()
            .find(|t| t.entry == "res/layout/square_cover_art_content.xml")
            .unwrap()
            .source;
        assert!(layout.contains("<spatch.PlayerArt "));
        assert!(HELPERS.iter().any(|(name, _)| *name == "PlayerArt"));
        for name in ["square_cover_art_size", "square_cover_art_top_margin"] {
            assert!(layout.contains(&format!("@dimen/{name}")));
            assert!(
                crate::patch::SPLIT_REFS
                    .iter()
                    .any(|(ty, n, _)| *ty == "dimen" && *n == name)
            );
        }
    }

    #[test]
    fn ref_limits_parse_dexdump() {
        let ok = "method_ids_size     : 65536\nfield_ids_size      : 59764\n";
        assert!(check_ref_limits("c", ok).is_ok());
        assert!(
            check_ref_limits("c", "method_ids_size     : 65537\nfield_ids_size : 1\n").is_err()
        );
        assert!(check_ref_limits("c", "garbage").is_err());
    }

    #[test]
    fn lbg_branch_keeps_circle_fallback() {
        let r = EDITS
            .iter()
            .find(|e| e.replacement.contains(":spatch_circle"))
            .unwrap()
            .replacement;
        assert!(
            r.contains(":spatch_circle")
                && r.contains("drawCircle")
                && r.trim_end().ends_with("const/4 v6, 0x0")
        );
    }
}
