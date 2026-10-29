package android.window;
// Compile-time stub only; never packaged (the device provides the real class).
public interface OnBackInvokedDispatcher {
    int PRIORITY_OVERLAY = 1000000;
    void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback c);
    void unregisterOnBackInvokedCallback(OnBackInvokedCallback c);
}
