package com.spotify.esperanto.esperanto;

public interface CoroutineTransport {
    Object callSingle(String service, String method, byte[] request, p.lnm continuation);
}
