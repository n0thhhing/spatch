package spatch;

import android.content.Context;
import android.content.SharedPreferences;

/** Theme chosen in Spatch settings; apply on the next app launch so Compose and XML agree. */
public final class Palette {
    static final String[] NAMES={"lavender","gruvbox","catppuccin-mocha","rose-pine","nord","tokyo-night","dracula","gruvbox-material-dark"};
    private static final int[] BASE={0x14121c,0x1d1a2a,0x2b2640,0xf3eeff,0xc6b8ff,0xbcb3dc,0xd0bcff,0xe0d2ff,0xb7a3ee};
    private static final int[][] COLOURS={@PALETTES@};
    private static SharedPreferences preferences;
    private static int active=index("@THEME@");
    static boolean resourceError;
    private Palette() {}
    private static int index(String name){for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals(name))return i;return indexDefault();}
    private static int indexDefault(){for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals("@THEME@"))return i;return 0;}
    public static void load(Context c){
        if(preferences!=null || c==null)return;
        try{
            preferences=c.getSharedPreferences("spatch",0);
            active=index(preferences.getString("theme","@THEME@"));
            if(active!=indexDefault() && (android.os.Build.VERSION.SDK_INT<30 || !PaletteResources.install(c,NAMES[active]))){active=indexDefault();resourceError=true;}
        }catch(RuntimeException e){active=indexDefault();resourceError=true;}
    }
    /** A role of any theme, for showing themes side by side; roles are BASE's order. */
    static int tone(int theme,int role){return 0xff000000|COLOURS[theme][role];}
    static String activeName(){return NAMES[active];}
    static String requested(){return preferences==null?NAMES[active]:NAMES[index(preferences.getString("theme",NAMES[active]))];}
    static boolean choose(String name){
        for(String valid:NAMES)if(valid.equals(name)){return preferences!=null && preferences.edit().putString("theme",name).commit();}
        throw new IllegalArgumentException("Unknown theme");
    }
    static void restart(Context c){
        android.content.Intent launch=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if(launch==null || launch.getComponent()==null)throw new IllegalStateException("Launcher unavailable");
        c.startActivity(android.content.Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }
    /** Spotify brand greens in Encore's static Compose palette (p.gex): accent, then pale mints (badges). */
    private static final int[] GREENS={0x1ed760,0x1db954,0x19e68c},MINTS={0x91eeda,0xc5f0c9};
    public static int color(int value){
        int rgb=value&0xffffff;
        for(int i=0;i<BASE.length;i++)if(rgb==BASE[i])return (value&0xff000000)|COLOURS[active][i];
        for(int g:GREENS)if(rgb==g)return (value&0xff000000)|COLOURS[active][6];
        for(int g:MINTS)if(rgb==g)return (value&0xff000000)|COLOURS[active][7];
        return value;
    }
    public static long compose(long argb){return (argb & ~0xffffffffL)==0 ? color((int)argb)&0xffffffffL : argb;}
}
