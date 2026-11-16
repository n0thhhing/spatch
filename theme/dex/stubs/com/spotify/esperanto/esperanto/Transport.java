package com.spotify.esperanto.esperanto;
// Compile-time stub only; never packaged (Spotify's Esperanto RPC transport, kept by R8).
public interface Transport {
    io.reactivex.rxjava3.core.Single callSingle(String service, String method, byte[] request);
    io.reactivex.rxjava3.core.Observable callStream(String service, String method, byte[] request);
    byte[] callSync(String service, String method, byte[] request);
}
