package spatch;

import android.content.Context;
import android.content.SharedPreferences;

/** Theme chosen in Spatch settings; apply on the next app launch so Compose and XML agree. */
public final class Palette {
    static final String[] NAMES={"lavender","gruvbox","catppuccin-mocha","rose-pine","nord","tokyo-night","dracula"};
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
    public static int color(int value){
        for(int i=0;i<BASE.length;i++)if((value&0xffffff)==BASE[i])return (value&0xff000000)|COLOURS[active][i];
        return value;
    }
    public static long compose(long argb){return (argb & ~0xffffffffL)==0 ? color((int)argb)&0xffffffffL : argb;}
}
