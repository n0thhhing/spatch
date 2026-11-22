//! Every guarded smali edit: each anchor must match exactly once or the build stops.

pub struct Edit {
    pub file: &'static str,
    pub anchor: &'static str,
    pub replacement: &'static str,
}

pub const EDITS: [Edit; 22] = [
    Edit { file: "smali/com/spotify/connectivity/httpretrofit/RetrofitMaker.smali", anchor: "    iput-object p5, p0, Lcom/spotify/connectivity/httpretrofit/RetrofitMaker;->mAssertion:Lp/hv31;\n", replacement: "    iput-object p5, p0, Lcom/spotify/connectivity/httpretrofit/RetrofitMaker;->mAssertion:Lp/hv31;\n\n    invoke-static {p0, p5}, Lspatch/CoreBridge;->webgate(Ljava/lang/Object;Ljava/lang/Object;)V\n" },
    // The Rust app reaches Spotify's core through the same Esperanto transports Spotify's screens use:
    // every client's transport passes through CoreBridge.
    Edit { file: "smali/com/spotify/esperanto/esperanto/ClientBase.smali", anchor: "    iput-object p1, p0, Lcom/spotify/esperanto/esperanto/ClientBase;->transport:Lcom/spotify/esperanto/esperanto/Transport;\n", replacement: "    invoke-static {p1}, Lspatch/CoreBridge;->wrap(Ljava/lang/Object;)Lcom/spotify/esperanto/esperanto/Transport;\n\n    move-result-object p1\n\n    iput-object p1, p0, Lcom/spotify/esperanto/esperanto/ClientBase;->transport:Lcom/spotify/esperanto/esperanto/Transport;\n" },
    // The app is the UI: it mounts on the launcher activity and follows later resumes.
    Edit { file: "smali_classes2/com/spotify/music/SpotifyMainActivity.smali", anchor: ".method public final onResume()V\n    .locals 7\n", replacement: ".method public final onResume()V\n    .locals 7\n\n    invoke-static {p0}, Lspatch/SpatchApp;->resumed(Landroid/app/Activity;)V\n" },
    // Spotify's playback session, so the immersive player can follow the music (Audio.java).
    // The decoded PCM, before the volume and with no microphone: AudioDriver hands each block to Audio.feed (its registers v2-v4 are free here).
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioDriver.smali", anchor: "    invoke-static {p1}, Ljava/nio/ByteBuffer;->wrap([B)Ljava/nio/ByteBuffer;\n", replacement: "    iget v2, p0, Lcom/spotify/playbacknative/AudioDriver;->mSampleRate:I\n\n    iget v3, p0, Lcom/spotify/playbacknative/AudioDriver;->mChannelConfig:I\n\n    iget v4, p0, Lcom/spotify/playbacknative/AudioDriver;->mSampleFormat:I\n\n    invoke-static {p1, p2, v2, v3, v4}, Lspatch/Audio;->feed([BIIII)V\n\n    invoke-static {p1}, Ljava/nio/ByteBuffer;->wrap([B)Ljava/nio/ByteBuffer;\n" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: ".method public onPaused(ZI)V\n    .locals 0\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: ".method public onPaused(ZI)V\n    .locals 0\n\n    invoke-static {p1, p2}, Lspatch/Audio;->paused(ZI)V\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result p1\n\n    .line 5\n    if-lez p1, :cond_0", replacement: "    move-result p1\n\n    invoke-static {p1}, Lspatch/Audio;->created(I)V\n\n    .line 5\n    if-lez p1, :cond_0" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result v1\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: "    move-result v1\n\n    invoke-static {v1}, Lspatch/Audio;->destroyed(I)V\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
    // Default-off developer diagnostics; all targets are pinned to Spotify 9.2.0.2053.
    Edit {
        file: "smali_classes3/p/zob1.smali",
        anchor: "    invoke-super {p0, p1}, Landroid/content/ContextWrapper;->attachBaseContext(Landroid/content/Context;)V\n",
        replacement: "    invoke-super {p0, p1}, Landroid/content/ContextWrapper;->attachBaseContext(Landroid/content/Context;)V\n\n    invoke-static {p0}, Lspatch/DevIntegrity;->init(Landroid/content/Context;)V\n",
    },
    Edit {
        file: "smali_classes11/p/xa50.smali",
        anchor: "    :goto_5\n    return-object p0\n",
        replacement: "    :goto_5\n    invoke-static {p0}, Lspatch/DevIntegrity;->signature(Ljava/lang/String;)V\n\n    return-object p0\n",
    },
    Edit {
        file: "smali_classes9/p/mts0.smali",
        anchor: "    invoke-interface {p3, p0, v0}, Lp/dmt0;->a(Lcom/spotify/integrity/integrityimpl/proto/VerifyRequest;Lp/osm;)Ljava/lang/Object;\n",
        replacement: "    invoke-static {p3, p0, v0}, Lspatch/DevIntegrity;->verify(Lp/dmt0;Lcom/spotify/integrity/integrityimpl/proto/VerifyRequest;Lp/osm;)Ljava/lang/Object;\n",
    },
    Edit {
        file: "smali_classes9/p/mts0.smali",
        anchor: "    :catch_0\n    move-exception p0\n\n    .line 99\n    new-instance p1, Lp/crj1;\n",
        replacement: "    :catch_0\n    move-exception p0\n\n    invoke-static {p0}, Lspatch/DevIntegrity;->failure(Ljava/lang/Throwable;)V\n\n    .line 99\n    new-instance p1, Lp/crj1;\n",
    },
    Edit {
        file: "smali_classes9/p/mts0.smali",
        anchor: "    invoke-direct {p0, p1}, Lp/drj1;-><init>(Ljava/lang/String;)V\n    :try_end_2\n    .catch Ljava/lang/Exception; {:try_start_2 .. :try_end_2} :catch_0\n",
        replacement: "    invoke-direct {p0, p1}, Lp/drj1;-><init>(Ljava/lang/String;)V\n    :try_end_2\n    .catch Ljava/lang/Exception; {:try_start_2 .. :try_end_2} :catch_0\n\n    invoke-static {}, Lspatch/DevIntegrity;->accepted()V\n",
    },
    Edit {
        file: "smali_classes2/p/h631.smali",
        anchor: "    invoke-interface {p1, v0, p0}, Lp/xcc;->O([BI)Lp/xcc;\n",
        replacement: "    invoke-static {v0, p0}, Lspatch/DevIntegrity;->body([BI)V\n\n    invoke-interface {p1, v0, p0}, Lp/xcc;->O([BI)Lp/xcc;\n",
    },
    Edit {
        file: "smali/p/ohj0.smali",
        anchor: "    invoke-interface {p0, v0, p1}, Lp/al00;->c(Ljava/lang/String;[B)I\n",
        replacement: "    invoke-static {v0, p1}, Lspatch/DevIntegrity;->event(Ljava/lang/String;[B)V\n\n    invoke-interface {p0, v0, p1}, Lp/al00;->c(Ljava/lang/String;[B)I\n",
    },
    // Observe startup decisions and token results without changing them.
    Edit {
        file: "smali_classes2/p/h3u0.smali",
        anchor: "    iput-object p1, p0, Lp/h3u0;->f:Lp/rqm;\n",
        replacement: "    iput-object p1, p0, Lp/h3u0;->f:Lp/rqm;\n\n    invoke-static {}, Lspatch/DevIntegrity;->pluginConstructed()V\n",
    },
    Edit {
        file: "smali_classes2/p/h3u0.smali",
        anchor: ".method public final c()V\n    .locals 6\n",
        replacement: ".method public final c()V\n    .locals 6\n\n    invoke-static {}, Lspatch/DevIntegrity;->pluginStarted()V\n",
    },
    Edit {
        file: "smali_classes2/p/h3u0.smali",
        anchor: "    move-result v3\n\n    .line 21\n    if-nez v3, :cond_0",
        replacement: "    move-result v3\n\n    invoke-static {v3}, Lspatch/DevIntegrity;->validation(Z)V\n\n    .line 21\n    if-nez v3, :cond_0",
    },
    Edit {
        file: "smali_classes2/p/h3u0.smali",
        anchor: "    move-result v2\n\n    .line 27\n    if-eqz v2, :cond_3",
        replacement: "    move-result v2\n\n    invoke-static {v2}, Lspatch/DevIntegrity;->employeeValidation(Z)V\n\n    .line 27\n    if-eqz v2, :cond_3",
    },
    Edit {
        file: "smali_classes2/p/h3u0.smali",
        anchor: "    move-result v4\n\n    .line 36\n    const/4 v5, 0x6",
        replacement: "    move-result v4\n\n    invoke-static {v4}, Lspatch/DevIntegrity;->scheduled(I)V\n\n    .line 36\n    const/4 v5, 0x6",
    },
    Edit {
        file: "smali_classes9/p/kls0.smali",
        anchor: ".method public D(Lp/ssm;)Ljava/lang/Object;\n    .locals 9\n",
        replacement: ".method public D(Lp/ssm;)Ljava/lang/Object;\n    .locals 9\n\n    invoke-static {}, Lspatch/DevIntegrity;->tokenStarted()V\n",
    },
    Edit {
        file: "smali_classes9/p/kls0.smali",
        anchor: "    check-cast v1, Lp/enf1;\n\n    .line 114\n",
        replacement: "    check-cast v1, Lp/enf1;\n\n    .line 114\n\n    invoke-static {v1}, Lspatch/DevIntegrity;->tokenOutcome(Ljava/lang/Object;)V\n",
    },
    // The extended-metadata service 502s the app's own token but accepts a web-player one; this
    // swaps the bearer on that one webgate path only, falling back to the app's token on any error.
    Edit {
        file: "smali/p/w7m1.smali",
        anchor: ".method public static b(Lp/hy01;Lp/a631;Ljava/lang/String;Lp/fh31;)Lp/fh31;\n    .locals 2\n\n",
        replacement: ".method public static b(Lp/hy01;Lp/a631;Ljava/lang/String;Lp/fh31;)Lp/fh31;\n    .locals 2\n\n    invoke-static {p1, p2}, Lspatch/WebAuth;->auth(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n\n    move-result-object p2\n\n",
    },
];
