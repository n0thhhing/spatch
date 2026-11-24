package spatch;

/**
 * Focused JVM checks for CoreBridge creation routing and SpatchApp native-feature mode state.
 *
 *   JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
 *   javac --release 11 -cp "$JAR" -d /tmp/ct \
 *     java/dex/stubs/com/spotify/esperanto/esperanto/Transport.java \
 *     java/dex/stubs/io/reactivex/rxjava3/core/*.java \
 *     java/dex/stubs/io/reactivex/rxjava3/disposables/*.java \
 *     java/dex/stubs/io/reactivex/rxjava3/functions/*.java \
 *     java/dex/src/spatch/*.java \
 *     java/dex/test/spatch/CreationRouteTest.java
 *   java -cp "/tmp/ct:$JAR" spatch.CreationRouteTest
 */
public final class CreationRouteTest {
    public static void main(String[] args) {
        check("spotify:blend:invitation".equals(CoreBridge.creationRoute("blend")), "blend route");
        check("spotify:jam".equals(CoreBridge.creationRoute("jam")), "jam route");
        check("spotify:create-prompted-playlist".equals(CoreBridge.creationRoute("prompted")), "prompted route");
        check("spotify:create-menu".equals(CoreBridge.creationRoute("collaborative")), "collaborative fallback route");
        check("spotify:create-menu".equals(CoreBridge.creationRoute("mixed")), "mixed fallback route");
        check("spotify:blend:invitation".equals(CoreBridge.creationRoute("  BLEND ")), "case/trim normalization");

        check(CoreBridge.creationRoute("playlist") == null, "plain playlist stays local form");
        check(CoreBridge.creationRoute("") == null, "empty kind rejected");
        check(CoreBridge.creationRoute(null) == null, "null kind rejected");
        check(CoreBridge.creationRoute("podcast") == null, "unknown kind rejected");

        String unsupported = CoreBridge.openCreation(null, "playlist");
        check(unsupported.contains("Unsupported"), "unsupported error: " + unsupported);
        check(!SpatchApp.isNativeFeatureMode(), "unsupported must not enter native mode");

        String noActivity = CoreBridge.openCreation(null, "blend");
        check(!noActivity.isEmpty(), "null activity returns error: " + noActivity);
        check(!SpatchApp.isNativeFeatureMode(), "null activity must not enter native mode");

        SpatchApp.enterNativeFeature(null);
        check(SpatchApp.isNativeFeatureMode(), "enterNativeFeature sets mode");
        SpatchApp.exitNativeFeature(null);
        check(!SpatchApp.isNativeFeatureMode(), "exitNativeFeature clears mode");

        // Fresh-install login/auth activities must never be covered by SpatchApp
        String[] authActivities = {
            "com.spotify.login.loginflowimpl.LoginActivity",
            "com.spotify.login.loginflowimpl.DelegatingLoginActivity",
            "com.spotify.login.loginflowimpl.QuickLoginActivity",
            "com.spotify.login.adaptiveauthentication.challenge.web.NoAnimLauncherActivity",
            "com.spotify.login.phonenumbersignup.callingcode.CallingCodePickerActivity",
            "com.spotify.appauthorization.sso.AuthorizationActivity",
            "com.spotify.appauthorization.externallogin.LoginRedirectActivity",
            "com.spotify.accountswitching.accountpickerimpl.AccountPickerActivity",
            "com.spotify.accountswitching.switcheruiimpl.AccountSwitcherActivity",
            "com.spotify.ageverification.ageassurancewebview.AgeAssuranceWebViewActivity",
            "com.spotify.genalphaauthentication.parentalconsentwebview.ParentalConsentWebActivity",
            "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
            "com.facebook.CustomTabMainActivity",
            "androidx.browser.customtabs.CustomTabsActivity"
        };
        for (String name : authActivities) {
            check(SpatchApp.isAuthClassName(name), "must treat as auth activity: " + name);
        }
        check(!SpatchApp.isAuthClassName("com.spotify.music.SpotifyMainActivity"), "main activity is not auth");
        check(!SpatchApp.isAuthClassName("android.app.Activity"), "generic activity is not auth");
        check(!CoreBridge.openLogin(null).isEmpty(), "openLogin(null) returns actionable message");
        check(!CoreBridge.openSpotifyLogin(null).isEmpty(), "openSpotifyLogin(null) returns actionable message");
        check(!CoreBridge.openNativeSpotify(null).isEmpty(), "openNativeSpotify(null) returns actionable message");
        check(CoreBridge.LOGIN_TASK_FLAGS == 268468224, "login task flags match Spotify iwe0.b (NEW_TASK|CLEAR_TASK)");

        System.out.println("CreationRouteTest: all checks passed");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("Failed: " + label);
    }
}
