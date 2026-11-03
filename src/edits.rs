//! Every guarded smali edit: each anchor must match exactly once or the build stops.

pub struct Edit {
    pub file: &'static str,
    pub anchor: &'static str,
    pub replacement: &'static str,
}

pub const EDITS: [Edit; 91] = [
    // Lyric lines (p.wkf0.a): other lines take Spotify's own animated 0.83 scale, which it keeps for
    // p2. classes9 is at its method limit, so these read Settings.on and Tint.lyricGlow as fields.
    Edit {
        file: "smali_classes9/p/wkf0.smali",
        anchor: "    if-eqz p2, :cond_23\n",
        replacement: "    sget-object v10, Lspatch/Settings;->on:[Z\n\n    const/16 v11, 0x5d\n\n    aget-boolean v10, v10, v11\n\n    if-nez v10, :spatch_lyrics_focus\n\n    if-eqz p2, :cond_23\n\n    :spatch_lyrics_focus\n",
    },
    // The active line's text style (p.okf0, field b): clearing mask bit 0x2000 lets the copy take the shadow.
    Edit {
        file: "smali_classes9/p/okf0.smali",
        anchor: "    invoke-static/range {v15 .. v41}, Lp/uce1;->a(",
        replacement: "    iget-boolean v8, v0, Lp/okf0;->b:Z\n\n    if-eqz v8, :spatch_no_glow\n\n    sget-object v31, Lspatch/Tint;->lyricGlow:Landroidx/compose/ui/graphics/Shadow;\n\n    if-eqz v31, :spatch_no_glow\n\n    const v41, 0xffdffc\n\n    :spatch_no_glow\n    invoke-static/range {v15 .. v41}, Lp/uce1;->a(",
    },
    // The queue sheet's list reports itself on layout; its window is a dialog no activity hook sees.
    Edit {
        file: "smali_classes3/com/spotify/nowplayingqueue/bottomsheet/view/layoutmanager/StickyHeaderLinearLayoutManager.smali",
        anchor: ".method public final t0(Lp/f511;Lp/k511;)V\n    .locals 2\n",
        replacement: ".method public final t0(Lp/f511;Lp/k511;)V\n    .locals 2\n\n    iget-object v0, p0, Landroidx/recyclerview/widget/a;->b:Landroidx/recyclerview/widget/RecyclerView;\n\n    invoke-static {v0}, Lspatch/RowCards;->queue(Landroid/view/ViewGroup;)V\n",
    },
    // Every real save state reaches SaveBurst before the view takes it (tap flag e, previous state d).
    Edit {
        file: "smali_classes7/com/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView.smali",
        anchor: ".method public final a(Lp/kk1;)V\n    .locals 3\n",
        replacement: ".method public final a(Lp/kk1;)V\n    .locals 3\n\n    iget-boolean v0, p0, Lcom/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView;->e:Z\n\n    iget-object v1, p0, Lcom/spotify/encoreconsumermobile/elements/addtobutton/AddToButtonView;->d:Lp/kk1;\n\n    invoke-static {p0, v0, v1, p1}, Lspatch/SaveBurst;->changed(Landroid/view/View;ZLp/kk1;Lp/kk1;)V\n",
    },
    Edit {
        file: "smali/p/yw9.smali",
        anchor: ".method public constructor <init>(JLandroidx/compose/ui/graphics/Brush;FLp/bv61;I)V\n    .locals 1\n",
        replacement: ".method public constructor <init>(JLandroidx/compose/ui/graphics/Brush;FLp/bv61;I)V\n    .locals 1\n\n    invoke-static {p1, p2}, Lspatch/Surfaces;->bg(J)J\n\n    move-result-wide p1\n\n    invoke-static {p3}, Lspatch/Surfaces;->headerBrush(Landroidx/compose/ui/graphics/Brush;)Landroidx/compose/ui/graphics/Brush;\n\n    move-result-object p3\n",
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

    // Encore's colours are built once from Palette: a live theme change builds them again.
    Edit { file: "smali/p/ggx.smali", anchor: ".field public static final a:Lp/sex;\n", replacement: ".field public static a:Lp/sex;\n" },
    Edit { file: "smali/p/ggx.smali", anchor: ".method static constructor <clinit>()V\n    .locals 62\n", replacement: ".method static constructor <clinit>()V\n    .locals 0\n\n    invoke-static {}, Lp/ggx;->rebuild()V\n\n    return-void\n.end method\n\n.method public static rebuild()V\n    .locals 62\n" },
    // Encore's tinted surfaces (Home shortcut tiles and the like) are white at 10/14/21% over the
    // page; they become the theme accent through Palette, about 1.4x as strong so they stay as bright.
    Edit { file: "smali/p/ggx.smali", anchor: "    const v0, 0x1affffff\n", replacement: "    const v0, 0x24d0bcff\n\n    invoke-static {v0}, Lspatch/Palette;->color(I)I\n\n    move-result v0\n" },
    Edit { file: "smali/p/ggx.smali", anchor: "    const v0, 0x24ffffff\n", replacement: "    const v0, 0x32d0bcff\n\n    invoke-static {v0}, Lspatch/Palette;->color(I)I\n\n    move-result v0\n" },
    Edit { file: "smali/p/ggx.smali", anchor: "    const v0, 0x36ffffff\n", replacement: "    const v0, 0x4cd0bcff\n\n    invoke-static {v0}, Lspatch/Palette;->color(I)I\n\n    move-result v0\n" },
    Edit { file: "smali/p/kex.smali", anchor: ".field public static final a:Lp/gsc;\n", replacement: ".field public static a:Lp/gsc;\n" },
    Edit { file: "smali/p/kex.smali", anchor: ".method static constructor <clinit>()V\n    .locals 6\n", replacement: ".method static constructor <clinit>()V\n    .locals 0\n\n    invoke-static {}, Lp/kex;->rebuild()V\n\n    return-void\n.end method\n\n.method public static rebuild()V\n    .locals 6\n" },
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
    // The play button around it (p.f1p1.j): its click also reports the press for the burst.
    Edit {
        file: "smali_classes4/p/f1p1.smali",
        anchor: ".method public static final j(Lkotlin/jvm/functions/Function0;IZLp/rhm0;FZZLjava/lang/String;Lp/pzj;I)V\n    .locals 28\n",
        replacement: ".method public static final j(Lkotlin/jvm/functions/Function0;IZLp/rhm0;FZZLjava/lang/String;Lp/pzj;I)V\n    .locals 28\n\n    invoke-static/range {p0 .. p1}, Lspatch/Flourish;->pressed(Lkotlin/jvm/functions/Function0;I)Lkotlin/jvm/functions/Function0;\n\n    move-result-object p0\n",
    },
    // Press release of everything Encore makes pressable (p.if41): the spec comes from spatch.Surfaces.
    Edit {
        file: "smali/p/if41.smali",
        anchor: "    .line 1\n    const/high16 v0, 0x3f800000    # 1.0f\n\n    .line 2\n    .line 3\n    invoke-static {v0}, Lp/ywd0;->w(F)Ljava/lang/Float;\n\n    .line 4\n    .line 5\n    .line 6\n    move-result-object v2\n\n    .line 7\n    const/4 v5, 0x0\n\n    .line 8\n    const/16 v7, 0xe\n\n    .line 9\n    .line 10\n    iget-object v1, p0, Lp/if41;->P0:Lp/d95;\n\n    .line 11\n    .line 12\n    const/4 v3, 0x0\n",
        replacement: "    .line 1\n    const/high16 v0, 0x3f800000    # 1.0f\n\n    .line 2\n    .line 3\n    invoke-static {v0}, Lp/ywd0;->w(F)Ljava/lang/Float;\n\n    .line 4\n    .line 5\n    .line 6\n    move-result-object v2\n\n    .line 7\n    const/4 v5, 0x0\n\n    .line 8\n    const/16 v7, 0xc\n\n    .line 9\n    .line 10\n    iget-object v1, p0, Lp/if41;->P0:Lp/d95;\n\n    .line 11\n    .line 12\n    invoke-static {}, Lspatch/Surfaces;->pressSpring()Lp/ud5;\n\n    move-result-object v3\n",
    },
    // Widget: Glance's hand-off of the rendered RemoteViews (p.rx5, classes.dex) asks
    // spatch.SpatchWidget first; it returns Spatch's views only for the Now Playing widget.
    Edit {
        file: "smali/p/rx5.smali",
        anchor: "    invoke-virtual {v1, v4, v0}, Landroid/appwidget/AppWidgetManager;->updateAppWidget(ILandroid/widget/RemoteViews;)V\n",
        replacement: "    invoke-static {v4, v0}, Lspatch/SpatchWidget;->replace(ILandroid/widget/RemoteViews;)Landroid/widget/RemoteViews;\n\n    move-result-object v0\n\n    invoke-virtual {v1, v4, v0}, Landroid/appwidget/AppWidgetManager;->updateAppWidget(ILandroid/widget/RemoteViews;)V\n",
    },
    // Glance translates the whole widget on the main thread for every state change; once Spatch's own
    // views are showing the result would be thrown away, so the translation doesn't start.
    Edit {
        file: "smali/p/rx5.smali",
        anchor: ".method public final d(Landroid/content/Context;Lp/d3x;Lp/dnm;)Ljava/lang/Object;\n    .locals 20\n",
        replacement: ".method public final d(Landroid/content/Context;Lp/d3x;Lp/dnm;)Ljava/lang/Object;\n    .locals 20\n\n    move-object/from16 v0, p0\n\n    iget-object v0, v0, Lp/rx5;->e:Lp/yw5;\n\n    iget v0, v0, Lp/yw5;->a:I\n\n    invoke-static {v0}, Lspatch/SpatchWidget;->skips(I)Z\n\n    move-result v0\n\n    if-eqz v0, :spatch_translate\n\n    sget-object v0, Lp/bhh1;->a:Lp/bhh1;\n\n    return-object v0\n\n    :spatch_translate\n",
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
