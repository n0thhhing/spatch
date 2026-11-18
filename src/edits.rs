//! Every guarded smali edit: each anchor must match exactly once or the build stops.

pub struct Edit {
    pub file: &'static str,
    pub anchor: &'static str,
    pub replacement: &'static str,
}

pub const EDITS: [Edit; 6] = [
    Edit { file: "smali/com/spotify/connectivity/httpretrofit/RetrofitMaker.smali", anchor: "    iput-object p5, p0, Lcom/spotify/connectivity/httpretrofit/RetrofitMaker;->mAssertion:Lp/jc31;\n", replacement: "    iput-object p5, p0, Lcom/spotify/connectivity/httpretrofit/RetrofitMaker;->mAssertion:Lp/jc31;\n\n    invoke-static {p0, p5}, Lspatch/CoreBridge;->webgate(Ljava/lang/Object;Ljava/lang/Object;)V\n" },
    // The Rust app reaches Spotify's core through the same Esperanto transports Spotify's screens use:
    // every client's transport passes through CoreBridge.
    Edit { file: "smali/com/spotify/esperanto/esperanto/ClientBase.smali", anchor: "    iput-object p1, p0, Lcom/spotify/esperanto/esperanto/ClientBase;->transport:Lcom/spotify/esperanto/esperanto/Transport;\n", replacement: "    invoke-static {p1}, Lspatch/CoreBridge;->wrap(Ljava/lang/Object;)Lcom/spotify/esperanto/esperanto/Transport;\n\n    move-result-object p1\n\n    iput-object p1, p0, Lcom/spotify/esperanto/esperanto/ClientBase;->transport:Lcom/spotify/esperanto/esperanto/Transport;\n" },
    // The app is the UI: it mounts on the launcher activity and follows later resumes.
    Edit { file: "smali_classes2/com/spotify/music/SpotifyMainActivity.smali", anchor: ".method public final onResume()V\n    .locals 7\n", replacement: ".method public final onResume()V\n    .locals 7\n\n    invoke-static {p0}, Lspatch/SpatchApp;->resumed(Landroid/app/Activity;)V\n" },
    // Spotify's playback session, so the immersive player can follow the music (Audio.java).
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: ".method public onPaused(ZI)V\n    .locals 0\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: ".method public onPaused(ZI)V\n    .locals 0\n\n    invoke-static {p1, p2}, Lspatch/Audio;->paused(ZI)V\n\n    .line 1\n    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result p1\n\n    .line 5\n    if-lez p1, :cond_0", replacement: "    move-result p1\n\n    invoke-static {p1}, Lspatch/Audio;->created(I)V\n\n    .line 5\n    if-lez p1, :cond_0" },
    Edit { file: "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali", anchor: "    move-result v1\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", replacement: "    move-result v1\n\n    invoke-static {v1}, Lspatch/Audio;->destroyed(I)V\n\n    .line 7\n    invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" },
];
