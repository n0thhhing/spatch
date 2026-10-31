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
pub const DEX_TARGETS: [(&str, &str); 10] = [
    (
        "classes5.dex",
        "a26cf01d4128da9d07d952b8608c5c764af46566b359ffc95e9aa19a66fa6cb9",
    ),
    (
        "classes7.dex",
        "70a11db74157abea0b986fdde6329d11967b29279e9c9743da205f7ac36312b0",
    ),
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

pub const EDITS: [Edit; 78] = [
    // Every real save state reaches SaveBurst before the view takes it (tap flag e, previous state d).
    Edit {
        file: "smali_classes7/com/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView.smali",
        anchor: ".method public final a(Lp/kk1;)V\n    .locals 3\n",
        replacement: ".method public final a(Lp/kk1;)V\n    .locals 3\n\n    iget-boolean v0, p0, Lcom/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView;->e:Z\n\n    iget-object v1, p0, Lcom/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView;->d:Lp/kk1;\n\n    invoke-static {p0, v0, v1, p1}, Lspatch/SaveBurst;->changed(Landroid/view/View;ZLp/kk1;Lp/kk1;)V\n",
    },
    Edit {
        file: "smali/p/yw9.smali",
        anchor: ".method public constructor <init>(JLandroidx/compose/ui/graphics/Brush;FLp/bv61;I)V\n    .locals 1\n",
        replacement: ".method public constructor <init>(JLandroidx/compose/ui/graphics/Brush;FLp/bv61;I)V\n    .locals 1\n\n    invoke-static {p1, p2}, Lspatch/Surfaces;->bg(J)J\n\n    move-result-wide p1\n",
    },
    Edit {
        file: "smali/p/xp31.smali",
        anchor: ".method public static final b(F)Lp/vp31;\n    .locals 1\n",
        replacement: ".method public static final b(F)Lp/vp31;\n    .locals 1\n\n    invoke-static {p0}, Lspatch/Surfaces;->radius(F)F\n\n    move-result p0\n",
    },
    // Search category tile: theme-toned surface, gentler artwork tilt.
    Edit {
        file: "smali_classes5/p/o6e.smali",
        anchor: "    iget-wide v4, v0, Lp/o6e;->c:J\n",
        replacement: "    iget-wide v4, v0, Lp/o6e;->c:J\n\n    invoke-static {v4, v5}, Lspatch/Surfaces;->tile(J)J\n\n    move-result-wide v4\n",
    },
    Edit {
        file: "smali_classes5/p/o6e.smali",
        anchor: "    const/high16 v7, 0x41c80000    # 25.0f\n",
        replacement: "    const/high16 v7, 0x41600000    # 14.0f\n",
    },
    Edit {
        file: "smali_classes5/p/o6e.smali",
        anchor: "    const/high16 v7, -0x3e380000    # -25.0f\n",
        replacement: "    const/high16 v7, -0x3ea00000    # -14.0f\n",
    },

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
    // base press: the only black here that paints a surface (v13 stays for text on accents)
    Edit {
        file: "smali/p/ggx.smali",
        anchor: "    const-wide v13, 0xff000000L\n\n    .line 22\n    .line 23\n    .line 24\n    .line 25\n    .line 26\n    invoke-static {v13, v14}, Lp/y2p1;->f(J)J\n\n    .line 27\n    .line 28\n    .line 29\n    move-result-wide v11\n",
        replacement: "    const-wide v13, 0xff000000L\n\n    .line 22\n    .line 23\n    .line 24\n    .line 25\n    .line 26\n    invoke-static {}, Lspatch/Surfaces;->pressed()J\n\n    .line 27\n    .line 28\n    .line 29\n    move-result-wide v11\n",
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
    // spatch.Pill.a(modifier, tab), which adds the Material 3 indicator and then sizes the icon. Icon
    // and label colours go through Pill too (psn0 is relocated:
    // classes2 has no method refs to spare).
    Edit {
        file: "smali_classes2/p/psn0.smali",
        anchor: "    const/high16 p3, 0x41c00000    # 24.0f\n\n    .line 80\n    .line 81\n    invoke-static {p4, p3}, Lp/n191;->r(Lp/rhm0;F)Lp/rhm0;\n",
        replacement: "    .line 80\n    .line 81\n    invoke-static {p4, p0}, Lspatch/Pill;->a(Lp/rhm0;Lp/psn0;)Lp/rhm0;\n",
    },
    Edit {
        file: "smali_classes2/p/psn0.smali",
        anchor: "    move-wide v3, v0\n\n    .line 134\n",
        replacement: "    move-wide v3, v0\n\n    invoke-static {p0, v5, v6, v3, v4}, Lspatch/Pill;->tint(Lp/psn0;JJ)J\n\n    move-result-wide v3\n\n    move-wide v5, v3\n\n    .line 134\n",
    },
    Edit {
        file: "smali_classes2/p/psn0.smali",
        anchor: "    :goto_8\n    new-instance v11, Lp/jyd1;\n",
        replacement: "    :goto_8\n    invoke-static {v4, v8, v9}, Lspatch/Pill;->labelTint(Lp/psn0;J)J\n\n    move-result-wide v8\n\n    new-instance v11, Lp/jyd1;\n",
    },
    // p.jub.V0 (classes5): the Create tab's close disc takes the slot's top padding directly, so it
    // gets Pill's icon offset too; p2 is put back because the method reuses it for k1u.d.
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    invoke-static/range {p0 .. p5}, Lp/cxn1;->G(Lp/rhm0;FFFFI)Lp/rhm0;\n\n    .line 537\n    .line 538\n    .line 539\n    move-result-object v0\n\n    .line 540\n    move-object v3, v1\n",
        replacement: "    invoke-static/range {p2 .. p2}, Lspatch/Pill;->closeDiscTop(F)F\n\n    move-result p2\n\n    invoke-static/range {p0 .. p5}, Lp/cxn1;->G(Lp/rhm0;FFFFI)Lp/rhm0;\n\n    .line 537\n    .line 538\n    .line 539\n    move-result-object v0\n\n    move/from16 p2, v3\n\n    .line 540\n    move-object v3, v1\n",
    },
    // Create opening (p.jub.V0, classes5): Spotify's white disc, shrink and dark tint go; the disc
    // becomes a tonal pill and Pill turns the + on its own centre (see Pill.closeAngle).
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    iget v11, v12, Lp/grg;->a:F\n\n    .line 545\n    .line 546\n    invoke-static {v0, v11}, Lp/n191;->r(Lp/rhm0;F)Lp/rhm0;\n",
        replacement: "    iget v11, v12, Lp/grg;->c:F\n\n    .line 545\n    .line 546\n    invoke-static {v0, v11}, Lspatch/Pill;->closePill(Lp/rhm0;F)Lp/rhm0;\n",
    },
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    iget-wide v13, v11, Lp/kxx;->a:J\n\n    .line 566\n",
        replacement: "    invoke-static {}, Lspatch/Pill;->closeColor()J\n\n    move-result-wide v13\n\n    .line 566\n",
    },
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    iget v7, v12, Lp/grg;->e:F\n\n    .line 607\n",
        replacement: "    iget v7, v12, Lp/grg;->e:F\n\n    invoke-static {v7}, Lspatch/Pill;->closeAngle(F)F\n\n    move-result v7\n\n    .line 607\n",
    },
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    iget v7, v12, Lp/grg;->b:F\n\n    .line 613\n",
        replacement: "    const/high16 v7, 0x3f800000    # 1.0f\n\n    .line 613\n",
    },
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    iget v7, v12, Lp/grg;->d:F\n\n    .line 627\n",
        replacement: "    const/4 v7, 0x0\n\n    .line 627\n",
    },
    Edit {
        file: "smali_classes5/p/jub.smali",
        anchor: "    const v0, 0x21122c8\n",
        replacement: "    const v0, 0x21122c8\n\n    invoke-static {}, Lspatch/Pill;->closeRest()V\n",
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
        replacement: "    invoke-static/range {p1 .. p1}, Lspatch/ScallopShape;->live(I)Lp/bv61;\n\n    move-result-object v5\n",
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
    // Home: every model update passes through xmc0's constructor; spatch.Content filters its list.
    Edit {
        file: "smali_classes2/p/xmc0.smali",
        anchor: "    iput-object p2, p0, Lp/xmc0;->b:Ljava/util/List;\n",
        replacement: "    invoke-static {p2}, Lspatch/Content;->home(Ljava/util/List;)Ljava/util/List;\n\n    move-result-object p2\n\n    iput-object p2, p0, Lp/xmc0;->b:Ljava/util/List;\n",
    },
    // Home's list: spatch.Content keeps it so Review on Home can resubmit the model.
    Edit {
        file: "smali_classes2/p/t0d0.smali",
        anchor: ".method public final b(Lp/xmc0;)V\n    .locals 6\n",
        replacement: ".method public final b(Lp/xmc0;)V\n    .locals 6\n\n    invoke-static {p0, p1}, Lspatch/Content;->submit(Lp/t0d0;Lp/xmc0;)V\n",
    },
    // Own profile top bar (bn31.i): the same banner setting picks its colour.
    Edit {
        file: "smali_classes12/p/bn31.smali",
        anchor: "    iget v0, v14, Lp/l6x0;->k:I\n\n    .line 272\n    .line 273\n    invoke-static {v0}, Lp/y2p1;->d(I)J\n\n    .line 274\n    .line 275\n    .line 276\n    move-result-wide v0\n",
        replacement: "    iget v0, v14, Lp/l6x0;->k:I\n\n    .line 272\n    .line 273\n    invoke-static {v0}, Lp/y2p1;->d(I)J\n\n    .line 274\n    .line 275\n    .line 276\n    move-result-wide v0\n\n    invoke-static {v0, v1, v14}, Lspatch/ProfileBanner;->bar(JLp/l6x0;)J\n\n    move-result-wide v0\n",
    },
    // Own profile header (bn31.h): spatch.ProfileBanner may replace the brush over Spotify's colour.
    Edit {
        file: "smali_classes12/p/bn31.smali",
        anchor: "    invoke-static {v2, v5, v6, v12, v14}, Lp/mtg;->m(Lp/rhm0;Landroidx/compose/ui/graphics/Brush;Lp/bv61;FI)Lp/rhm0;\n",
        replacement: "    invoke-static {v5, v0}, Lspatch/ProfileBanner;->brush(Landroidx/compose/ui/graphics/Brush;Lp/l6x0;)Landroidx/compose/ui/graphics/Brush;\n\n    move-result-object v5\n\n    invoke-static {v2, v5, v6, v12, v14}, Lp/mtg;->m(Lp/rhm0;Landroidx/compose/ui/graphics/Brush;Lp/bv61;FI)Lp/rhm0;\n",
    },
    // Your Library rows (r0a1.d): spatch.Surfaces lays each on a card.
    Edit {
        file: "smali_classes12/p/r0a1.smali",
        anchor: "    :goto_b\n    invoke-interface {v2, v4}, Lp/rhm0;->then(Lp/rhm0;)Lp/rhm0;\n\n    .line 365\n    .line 366\n    .line 367\n    move-result-object v2\n",
        replacement: "    :goto_b\n    invoke-interface {v2, v4}, Lp/rhm0;->then(Lp/rhm0;)Lp/rhm0;\n\n    .line 365\n    .line 366\n    .line 367\n    move-result-object v2\n\n    invoke-static {v2}, Lspatch/Surfaces;->libraryRow(Lp/rhm0;)Lp/rhm0;\n\n    move-result-object v2\n",
    },
    // Toggle chips (n0k0.c, Library filters among them): the selected chip turns into a pill.
    Edit {
        file: "smali_classes8/p/n0k0.smali",
        anchor: ".method public static final c(ZLp/wt30;Lp/bex;Lp/wdx;Lp/bv61;Lp/x9x;ZLp/lcn0;Lp/r980;Lp/rhm0;Lp/z1i;Lp/pzj;I)V\n    .locals 29\n",
        replacement: ".method public static final c(ZLp/wt30;Lp/bex;Lp/wdx;Lp/bv61;Lp/x9x;ZLp/lcn0;Lp/r980;Lp/rhm0;Lp/z1i;Lp/pzj;I)V\n    .locals 29\n\n    move/from16 v0, p0\n\n    move-object/from16 v1, p4\n\n    invoke-static {v1, v0}, Lspatch/Surfaces;->chipShape(Lp/bv61;Z)Lp/bv61;\n\n    move-result-object v1\n\n    move-object/from16 p4, v1\n",
    },
    // Playlist track rows (eeb1, via kro1.d): spatch.Surfaces lays each on a card.
    Edit {
        file: "smali_classes8/p/eeb1.smali",
        anchor: "    move v10, v4\n",
        replacement: "    move v10, v4\n\n    invoke-static {v9}, Lspatch/Surfaces;->trackRow(Lp/rhm0;)Lp/rhm0;\n\n    move-result-object v9\n\n",
    },
    // Home chips (p.sdy, classes7): the fetched feed list goes through spatch.Content.feeds.
    Edit {
        file: "smali_classes7/p/sdy.smali",
        anchor: "Lp/aj00;->a(Ljava/util/List;)Ljava/util/ArrayList;",
        replacement: "Lspatch/Content;->feeds(Ljava/util/List;)Ljava/util/ArrayList;",
    },
    // Search tiles (p.s8e, classes5): filtered before the row layout so the rest reflow.
    Edit {
        file: "smali_classes5/p/s8e.smali",
        anchor: "    iput-object p3, p0, Lp/s8e;->c:Ljava/util/ArrayList;\n",
        replacement: "    invoke-static {p3}, Lspatch/Content;->tiles(Ljava/util/ArrayList;)Ljava/util/ArrayList;\n\n    move-result-object p3\n\n    iput-object p3, p0, Lp/s8e;->c:Ljava/util/ArrayList;\n",
    },
    // Your Library (classes12 items, classes4 filter chips): hidden types never reach the list.
    Edit {
        file: "smali_classes12/p/zzo1.smali",
        anchor: "    .line 710\n    .line 711\n    :cond_16\n    return-object v0\n",
        replacement: "    .line 710\n    .line 711\n    :cond_16\n    invoke-static {v0}, Lspatch/Content;->library(Ljava/util/ArrayList;)Ljava/util/ArrayList;\n\n    move-result-object v0\n\n    return-object v0\n",
    },
    Edit {
        file: "smali_classes4/p/oxo1.smali",
        anchor: "    invoke-static {v2, v1}, Lp/gpo1;->l(Ljava/lang/String;Ljava/util/List;)Lp/i5c0;\n\n    .line 49\n    .line 50\n    .line 51\n    move-result-object v1\n\n    .line 52\n    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z\n\n    .line 53\n    .line 54\n    .line 55\n    goto :goto_0\n\n    .line 56\n    :cond_0\n    return-object v0\n",
        replacement: "    invoke-static {v2, v1}, Lp/gpo1;->l(Ljava/lang/String;Ljava/util/List;)Lp/i5c0;\n\n    .line 49\n    .line 50\n    .line 51\n    move-result-object v1\n\n    .line 52\n    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z\n\n    .line 53\n    .line 54\n    .line 55\n    goto :goto_0\n\n    .line 56\n    :cond_0\n    invoke-static {v0}, Lspatch/Content;->libraryChips(Ljava/util/ArrayList;)Ljava/util/ArrayList;\n\n    move-result-object v0\n\n    return-object v0\n",
    },
    // Floating icon-only bar: k1u.c always takes its compact branch (no label; TalkBack still reads
    // the tab's description), which Spotify otherwise uses below 520dp of height.
    Edit {
        file: "smali_classes12/p/k1u.smali",
        anchor: "    if-ge v6, v7, :cond_9\n\n    .line 114\n",
        replacement: "    .line 114\n",
    },
    // Tab press feedback (k1u.c, classes12): Spotify's default indication shrinks the whole tab to 0.9
    // and an overlay fills the square tab cell. Compose draws none; NavIndicator draws a pill state layer.
    Edit {
        file: "smali_classes12/p/k1u.smali",
        anchor: "    const/16 v19, 0x7b8\n\n    .line 235\n",
        replacement: "    const/16 v19, 0x6b8\n\n    .line 235\n",
    },
    Edit {
        file: "smali_classes12/p/k1u.smali",
        anchor: "    .line 242\n    const/4 v12, 0x0\n\n    .line 243\n    const/4 v13, 0x0\n\n    .line 244\n    const/16 v17, 0x0\n",
        replacement: "    .line 242\n    const/4 v12, 0x0\n\n    .line 243\n    const/4 v13, 0x0\n\n    .line 244\n    const/16 v17, 0x0\n",
    },

    // Typography: the three Spotify Mix loaders ask spatch.Fonts first (docs/targets.md).
    Edit {
        file: "smali/p/jw21.smali",
        anchor: ".method public static c(Landroid/content/Context;ILandroid/util/TypedValue;ILp/e82;ZZ)Landroid/graphics/Typeface;\n    .locals 11\n",
        replacement: ".method public static c(Landroid/content/Context;ILandroid/util/TypedValue;ILp/e82;ZZ)Landroid/graphics/Typeface;\n    .locals 11\n\n    invoke-static {p1}, Lspatch/Fonts;->resource(I)Landroid/graphics/Typeface;\n\n    move-result-object v0\n\n    if-eqz v0, :spatch_stock\n\n    if-eqz p4, :spatch_done\n\n    invoke-virtual {p4, v0}, Lp/e82;->o(Landroid/graphics/Typeface;)V\n\n    :spatch_done\n    return-object v0\n\n    :spatch_stock\n",
    },
    Edit {
        file: "smali_classes3/p/n74.smali",
        anchor: ".method public static K(Landroid/content/Context;II)Landroid/graphics/Typeface;\n    .locals 3\n",
        replacement: ".method public static K(Landroid/content/Context;II)Landroid/graphics/Typeface;\n    .locals 3\n\n    invoke-static {p1}, Lspatch/Fonts;->resource(I)Landroid/graphics/Typeface;\n\n    move-result-object v0\n\n    if-eqz v0, :spatch_stock\n\n    return-object v0\n\n    :spatch_stock\n",
    },
    Edit {
        file: "smali_classes3/p/h4b1.smali",
        anchor: ".method public static final a(Landroid/content/Context;)Landroid/graphics/Typeface;\n    .locals 3\n",
        replacement: ".method public static final a(Landroid/content/Context;)Landroid/graphics/Typeface;\n    .locals 3\n\n    const v0, 0x7f090015\n\n    invoke-static {v0}, Lspatch/Fonts;->resource(I)Landroid/graphics/Typeface;\n\n    move-result-object v0\n\n    if-eqz v0, :spatch_stock\n\n    return-object v0\n\n    :spatch_stock\n",
    },
    Edit {
        file: "smali_classes3/p/b0h1.smali",
        anchor: ".method public static a(Landroid/graphics/Typeface;Lp/v930;Landroid/content/Context;)Landroid/graphics/Typeface;\n    .locals 11\n",
        replacement: ".method public static a(Landroid/graphics/Typeface;Lp/v930;Landroid/content/Context;)Landroid/graphics/Typeface;\n    .locals 11\n\n    invoke-static {p0}, Lspatch/Fonts;->owned(Landroid/graphics/Typeface;)Z\n\n    move-result v0\n\n    if-eqz v0, :spatch_stock\n\n    return-object p0\n\n    :spatch_stock\n",
    },
    Edit {
        file: "smali_classes2/p/tzb.smali",
        anchor: "    new-instance p0, Lp/rv21;\n\n    .line 12\n    .line 13\n    const v0, 0x7f090002\n",
        replacement: "    new-instance p0, Lp/rv21;\n\n    .line 12\n    .line 13\n    iget v0, p1, Landroidx/compose/ui/text/font/FontWeight;->a:I\n\n    invoke-static {v0}, Lspatch/Fonts;->variable(I)I\n\n    move-result v0\n",
    },
    // Home filter row: my50.b hands the same model to the native chips (spatch.FilterRow).
    Edit {
        file: "smali_classes2/p/my50.smali",
        anchor: "    invoke-virtual {p2, v1}, Lp/hqr0;->setValue(Ljava/lang/Object;)V\n",
        replacement: "    invoke-virtual {p2, v1}, Lp/hqr0;->setValue(Ljava/lang/Object;)V\n\n    iget-object p2, v1, Lp/nn60;->a:Lp/b9;\n\n    iget-object v1, v1, Lp/nn60;->b:Lp/mn60;\n\n    iget-object v2, p0, Lp/my50;->Z:Landroid/view/View;\n\n    iget-object v3, p0, Lp/my50;->a:Lp/wt30;\n\n    invoke-static {v2, p2, v1, v3}, Lspatch/FilterRow;->bind(Landroid/view/View;Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V\n",
    },
    // Every page change takes the animation Spotify otherwise uses for only a few of them.
    Edit {
        file: "smali_classes2/p/tvn0.smali",
        anchor: "    move-object p4, v1\n\n    .line 18\n    :cond_2\n",
        replacement: "    move-object p4, v1\n\n    .line 18\n    :cond_2\n    invoke-static {}, Lspatch/Settings;->pageMotionEnabled()Z\n\n    move-result p3\n",
    },
    // The default press overlay (tsr) is black, which shows on any surface lighter than black.
    Edit {
        file: "smali_classes8/p/tsr.smali",
        anchor: "    sget-wide v2, Lp/i7h;->b:J\n",
        replacement: "    invoke-static {}, Lspatch/Surfaces;->pressTint()J\n\n    move-result-wide v2\n",
    },
    Edit {
        file: "smali_classes8/p/tsr.smali",
        anchor: "    sget-wide v1, Lp/i7h;->b:J\n",
        replacement: "    invoke-static {}, Lspatch/Surfaces;->pressTint()J\n\n    move-result-wide v1\n",
    },
    Edit {
        file: "smali_classes8/p/tsr.smali",
        anchor: "    const v4, 0x3e99999a    # 0.3f\n",
        replacement: "    const v4, 0x3e23d70a    # 0.16f\n",
    },
    // Side drawer link rows: no outer padding, so the held highlight fills the rounded row.
    Edit {
        file: "smali_classes11/p/x571.smali",
        anchor: "    invoke-static {v0, v13, v1, v3, v2}, Lp/evx;->b(Lp/evx;ILp/dvx;Lp/b3v;I)Lp/evx;\n\n    .line 168\n    .line 169\n    .line 170\n    move-result-object v15\n",
        replacement: "    invoke-static {v0, v13, v1, v3, v2}, Lp/evx;->b(Lp/evx;ILp/dvx;Lp/b3v;I)Lp/evx;\n\n    .line 168\n    .line 169\n    .line 170\n    move-result-object v15\n\n    invoke-static {v15}, Lspatch/Surfaces;->drawerItem(Lp/evx;)Lp/evx;\n\n    move-result-object v15\n",
    },
    // Side drawer link rows (x571, classes11): tonal rounded surfaces.
    Edit {
        file: "smali_classes11/p/x571.smali",
        anchor: "    invoke-static {v0, v1}, Lp/n191;->f(Lp/rhm0;F)Lp/rhm0;\n\n    .line 110\n    .line 111\n    .line 112\n    move-result-object v14\n\n    .line 113\n    check-cast v8, Lp/l340;\n",
        replacement: "    invoke-static {v0, v1}, Lp/n191;->f(Lp/rhm0;F)Lp/rhm0;\n\n    .line 110\n    .line 111\n    .line 112\n    move-result-object v14\n\n    invoke-static {v14}, Lspatch/Surfaces;->drawerRow(Lp/rhm0;)Lp/rhm0;\n\n    move-result-object v14\n\n    .line 113\n    check-cast v8, Lp/l340;\n",
    },
];

/// Original classes moved into `HELPER_DEX` (smali folder, file, original DEX, descriptor) because
/// their own DEX has no room for the references their edits add; behaviour and names are unchanged.
const RELOCATED: [(&str, &str, &str, &str); 11] = [
    // Typography and the Home filter row: these DEX tables are full too.
    ("smali", "p/jw21.smali", "classes.dex", "Lp/jw21;"),
    ("smali_classes2", "p/tzb.smali", "classes2.dex", "Lp/tzb;"),
    ("smali_classes2", "p/my50.smali", "classes2.dex", "Lp/my50;"),
    // Compose surface and shape policy (spatch.Surfaces): both DEX tables are full.
    ("smali", "p/yw9.smali", "classes.dex", "Lp/yw9;"),
    ("smali", "p/xp31.smali", "classes.dex", "Lp/xp31;"),
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
    ("smali_classes2", "p/xmc0.smali", "classes2.dex", "Lp/xmc0;"),
    ("smali_classes2", "p/psn0.smali", "classes2.dex", "Lp/psn0;"),
];

/// Helper classes (theme tokens substituted before compiling), shipped together in `HELPER_DEX`.
const HELPERS: [(&str, &str); 49] = [
    ("Fonts", include_str!("../theme/dex/src/spatch/Fonts.java")),
    (
        "FontFile",
        include_str!("../theme/dex/src/spatch/FontFile.java"),
    ),
    (
        "FontImport",
        include_str!("../theme/dex/src/spatch/FontImport.java"),
    ),
    (
        "Typography",
        include_str!("../theme/dex/src/spatch/Typography.java"),
    ),
    (
        "HomeReview",
        include_str!("../theme/dex/src/spatch/HomeReview.java"),
    ),
    (
        "ProfileBanner",
        include_str!("../theme/dex/src/spatch/ProfileBanner.java"),
    ),
    (
        "BannerImport",
        include_str!("../theme/dex/src/spatch/BannerImport.java"),
    ),
    (
        "FilterRow",
        include_str!("../theme/dex/src/spatch/FilterRow.java"),
    ),
    (
        "Expansion",
        include_str!("../theme/dex/src/spatch/Expansion.java"),
    ),
    (
        "ClickHaptic",
        include_str!("../theme/dex/src/spatch/ClickHaptic.java"),
    ),
    (
        "SaveBurst",
        include_str!("../theme/dex/src/spatch/SaveBurst.java"),
    ),
    (
        "Surfaces",
        include_str!("../theme/dex/src/spatch/Surfaces.java"),
    ),
    (
        "MiniRing",
        include_str!("../theme/dex/src/spatch/MiniRing.java"),
    ),
    (
        "MiniSurface",
        include_str!("../theme/dex/src/spatch/MiniSurface.java"),
    ),
    (
        "Haptics",
        include_str!("../theme/dex/src/spatch/Haptics.java"),
    ),
    (
        "MiniBar",
        include_str!("../theme/dex/src/spatch/MiniBar.java"),
    ),
    (
        "MiniEq",
        include_str!("../theme/dex/src/spatch/MiniEq.java"),
    ),
    (
        "MiniSkip",
        include_str!("../theme/dex/src/spatch/MiniSkip.java"),
    ),
    (
        "NavIndicator",
        include_str!("../theme/dex/src/spatch/NavIndicator.java"),
    ),
    (
        "M3Switch",
        include_str!("../theme/dex/src/spatch/M3Switch.java"),
    ),
    (
        "SettingsHero",
        include_str!("../theme/dex/src/spatch/SettingsHero.java"),
    ),
    (
        "ThemeSwatch",
        include_str!("../theme/dex/src/spatch/ThemeSwatch.java"),
    ),
    (
        "Content",
        include_str!("../theme/dex/src/spatch/Content.java"),
    ),
    (
        "Spectrum",
        include_str!("../theme/dex/src/spatch/Spectrum.java"),
    ),
    (
        "SpectrumPreview",
        include_str!("../theme/dex/src/spatch/SpectrumPreview.java"),
    ),
    (
        "TextSlide",
        include_str!("../theme/dex/src/spatch/TextSlide.java"),
    ),
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
    ("Tint", include_str!("../theme/dex/src/spatch/Tint.java")),
    (
        "Confetti",
        include_str!("../theme/dex/src/spatch/Confetti.java"),
    ),
    (
        "Flourish",
        include_str!("../theme/dex/src/spatch/Flourish.java"),
    ),
    (
        "Springy",
        include_str!("../theme/dex/src/spatch/Springy.java"),
    ),
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
/// Added by `--debug` only, and found by name at runtime, so other builds contain none of this code.
const DEBUG_HELPERS: [(&str, &str); 3] = [
    (
        "DevOptions",
        include_str!("../theme/dex/src/spatch/DevOptions.java"),
    ),
    (
        "DevTests",
        include_str!("../theme/dex/src/spatch/DevTests.java"),
    ),
    (
        "DevOverlay",
        include_str!("../theme/dex/src/spatch/DevOverlay.java"),
    ),
];

fn helpers(debug: bool) -> Vec<(&'static str, &'static str)> {
    let extra: &[(&str, &str)] = if debug { &DEBUG_HELPERS } else { &[] };
    HELPERS.iter().chain(extra).copied().collect()
}

/// Every obfuscated class the helpers compile against, for the on-device binding test.
fn obfuscated_classes() -> String {
    let names: Vec<_> = STUBS
        .iter()
        .filter(|(_, source)| source.contains("package p;"))
        .map(|(name, _)| format!("p.{}", name.trim_end_matches(".java")))
        .collect();
    names.join(",")
}

const STUBS: &[(&str, &str)] = &[
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
    ("fwq.java", include_str!("../theme/dex/stubs/p/fwq.java")),
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
    debug: bool,
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
            && name != "smali_classes5"
            && name != "smali_classes7"
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
        compile_helper(staging, tools, theme, &relocated_dex, debug)?,
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
    debug: bool,
) -> Result<Vec<u8>> {
    let root = staging.join("helper");
    let helpers = helpers(debug);
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
    let helper_paths: Vec<_> = helpers
        .iter()
        .map(|(n, _)| root.join(format!("{n}.java")))
        .collect();
    for ((_, src), p) in helpers.iter().zip(&helper_paths) {
        let source = helper_source(src, theme)
            .replace("@THEME@", theme.name)
            .replace("@OBFUSCATED@", &obfuscated_classes());
        fs::write(p, source)?;
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
    // Each helper plus its nested classes (Name$Inner.class).
    let class_paths: Vec<_> = fs::read_dir(cls.join("spatch"))?
        .map(|e| Ok(e?.path()))
        .collect::<Result<_>>()?;
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
    let all_present = helpers
        .iter()
        .all(|(n, _)| classes.contains(&format!("'Lspatch/{n};'")));
    let nested = class_paths
        .iter()
        .filter(|p| p.to_string_lossy().contains('$'))
        .count();
    if classes.matches("Class descriptor").count() != helpers.len() + nested + RELOCATED.len()
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
    fn only_debug_builds_contain_developer_code() {
        let names = |debug| -> Vec<&str> { helpers(debug).iter().map(|(n, _)| *n).collect() };
        assert!(!names(false).iter().any(|n| n.starts_with("Dev")));
        for (name, _) in DEBUG_HELPERS {
            assert!(names(true).contains(&name));
        }
        // Normal helpers may only reach it by name: a compile-time reference would fail without --debug.
        for (name, source) in HELPERS {
            for class in ["DevOptions.", "DevTests.", "DevOverlay."] {
                assert!(!source.contains(class), "{name} refers to {class}");
            }
        }
        assert!(obfuscated_classes().contains("p.x7v"));
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
