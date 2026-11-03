// p.S_DrawerRowWrapper: Drawer row wrapper with defaults; its a field is the row content (S_DrawerRowContent).
package p;
// Compile-time stub only; never packaged (the device provides the real class).
public final class S_DrawerRowWrapper {
    public final S_DrawerRowContentBase a;

    public S_DrawerRowWrapper(S_DrawerRowIconBase icon, Integer title, String uri, S_DrawerRowBadge badge, S_DrawerRowLogSpec log, S_DrawerRowTrailing trailing, int defaults) {
        this.a = new S_DrawerRowContent(icon, title, uri, badge, log, trailing);
    }
}
