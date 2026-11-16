package dev.makepad.android;

import android.view.MotionEvent;
import android.view.Surface;

public final class MakepadNative {
    public static native void activityOnCreate(Object activity);

    public static native void onAndroidParams(String cachePath, float density, boolean isEmulator, String androidVersion, String buildNumber, String kernelVersion);

    public static native void initChoreographer(float refreshRate, int sdkVersion);

    public static native void activityOnStart();

    public static native void activityOnResume();

    public static native void activityOnPause();

    public static native void activityOnStop();

    public static native void activityOnDestroy();

    public static native void activityOnWindowFocusChanged(boolean hasFocus);

    public static native void surfaceOnSurfaceCreated(Surface surface);

    public static native void surfaceOnSurfaceChanged(Surface surface, int width, int height);

    public static native void surfaceOnSurfaceDestroyed();

    public static native void surfaceOnTouch(MotionEvent event);

    public static native void onBackPressed();
}
