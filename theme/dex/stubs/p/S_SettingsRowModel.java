// p.S_SettingsRowModel: Role not confirmed: named only by other stubs, never by helper code.
package p;

// Compile-only Spotify 9.1.98.521 contract.
public final class S_SettingsRowModel implements S_SettingsRowModelBase {
    public final String a;
    public final Integer c;
    public final String d;
    public final S_KotlinFlow j;
    public final S_SettingsRowStateSource k;
    public final S_SettingsReadWrite m;
    public final int mask;

    public S_SettingsRowModel(String id, Integer title, String literal, Integer description, S_SettingsRowTrailing note, Integer icon, S_SettingsRowAction lead, S_KotlinFlow visible, S_SettingsRowStateSource enabled, S_KotlinFlow extra, S_SettingsReadWrite value, java.util.Map log, int mask) {
        this.a = id;
        this.c = title;
        this.d = literal;
        this.j = visible;
        this.k = enabled;
        this.m = value;
        this.mask = mask;
    }
}
