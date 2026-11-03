// p.S_DrawerRowContent: Drawer row content: icon, title resource, URI, badge, log spec (e), trailing slot. Settings reads e to reuse Spotify's logging.
package p;
// Compile-time stub only; never packaged (the device provides the real class).
public final class S_DrawerRowContent implements S_DrawerRowContentBase {
    public final S_DrawerRowIconBase a;
    public final Integer b;
    public final String c;
    public final S_DrawerRowBadge d;
    public final S_DrawerRowLogSpec e;
    public final S_DrawerRowTrailing f;

    public S_DrawerRowContent(S_DrawerRowIconBase a, Integer b, String c, S_DrawerRowBadge d, S_DrawerRowLogSpec e, S_DrawerRowTrailing f) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
    }
}
