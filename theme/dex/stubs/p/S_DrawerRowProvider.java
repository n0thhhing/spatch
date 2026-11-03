// p.S_DrawerRowProvider: Drawer row provider: kind 0 Recents, 1 Settings and privacy, 2 Company Information.
package p;
// Compile-time stub only; never packaged (the device provides the real class).
public final class S_DrawerRowProvider implements S_DrawerRowSource { public final byte a; public S_DrawerRowProvider(byte b) { a = b; } // no initializer: javac would inline a constant
    public S_KotlinFlow a() { return new S_KotlinFlowOf(new S_DrawerRowWrapper(null, 0, "", null, null, null, 0), (byte) 10); } }
