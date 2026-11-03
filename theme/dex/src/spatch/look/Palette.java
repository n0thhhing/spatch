package spatch;

import android.content.Context;
import android.content.SharedPreferences;

/** Theme chosen in Spatch settings; applied at launch, or live by swapping everything that reads it. */
public final class Palette {
    static final String[] NAMES={"lavender","gruvbox","catppuccin-mocha","rose-pine","nord","tokyo-night","dracula","gruvbox-material-dark","wallpaper"};
    /** NAMES index of the theme read from the wallpaper's Material You palette at launch; COLOURS has no row for it. */
    static final int WALLPAPER=8;
    private static final int[] BASE={0x14121c,0x1d1a2a,0x2b2640,0xf3eeff,0xc6b8ff,0xbcb3dc,0xd0bcff,0xe0d2ff,0xb7a3ee};
    private static final int[][] COLOURS={@PALETTES@};
    private static int[] wallpaper=COLOURS[0]; // Lavender until the system palette is read (and before Android 12)
    private static SharedPreferences preferences;
    private static int active=index("@THEME@");
    static boolean resourceError;
    private static Context base;
    private static boolean reopen,swapped;
    private static final java.util.ArrayList<android.app.Activity> OPEN=new java.util.ArrayList<>();
    private Palette() {}
    private static int index(String name){for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals(name))return i;return indexDefault();}
    private static int indexDefault(){for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals("@THEME@"))return i;return 0;}
    public static void load(Context c){
        if(preferences!=null || c==null)return;
        try{
            preferences=c.getSharedPreferences("spatch",0);
            base=c;
            wallpaper=wallpaper(c);
            active=index(preferences.getString("theme","@THEME@"));
            if(active!=indexDefault() && (android.os.Build.VERSION.SDK_INT<30 || !PaletteResources.install(c,NAMES[active]))){active=indexDefault();resourceError=true;}
        }catch(RuntimeException e){active=indexDefault();resourceError=true;}
    }
    /** A role of any theme, for showing themes side by side; roles are BASE's order. */
    static int tone(int theme,int role){return 0xff000000|row(theme)[role];}
    private static int[] row(int theme){return theme==WALLPAPER?wallpaper:COLOURS[theme];}

    static int[] wallpaperRow(){return wallpaper;}

    /** The nine roles from the system palette (dark tones, as every Spatch theme is dark). */
    static int[] wallpaper(Context c){
        if(android.os.Build.VERSION.SDK_INT<31)return COLOURS[0];
        try{
            android.content.res.Resources r=c.getResources();
            String[] names={"neutral1_900","accent2_800","neutral1_50","accent2_200","neutral2_200","accent1_200","accent1_100","accent1_300"};
            int[] sys=new int[names.length];
            for(int i=0;i<names.length;i++){
                int id=r.getIdentifier("system_"+names[i],"color","android");
                if(id==0)return COLOURS[0];
                sys[i]=r.getColor(id)&0xffffff;
            }
            int lifted=Pill.blend(0xff000000|sys[0],0xff000000|sys[1],0.45f)&0xffffff;
            return new int[]{sys[0],lifted,sys[1],sys[2],sys[3],sys[4],sys[5],sys[6],sys[7]};
        }catch(RuntimeException e){return COLOURS[0];}
    }

    /**
     * The Lavender overlay's compiled resources with its colours swapped for the wallpaper's: every
     * Res_value holding an ARGB8/RGB8 literal (size 8, type 0x1c/0x1d) is matched against Lavender's roles.
     */
    static byte[] recolor(byte[] b){
        byte[] out=b.clone();
        for(int p=0;p+8<=b.length;p++){
            if(b[p]!=8||b[p+1]!=0||b[p+2]!=0||(b[p+3]!=0x1c&&b[p+3]!=0x1d))continue;
            int v=(b[p+4]&255)|(b[p+5]&255)<<8|(b[p+6]&255)<<16|(b[p+7]&255)<<24;
            for(int i=0;i<COLOURS[0].length;i++)if(COLOURS[0][i]==(v&0xffffff)){
                int n=(v&0xff000000)|wallpaper[i];
                out[p+4]=(byte)n;out[p+5]=(byte)(n>>8);out[p+6]=(byte)(n>>16);out[p+7]=(byte)(n>>24);
                break;
            }
        }
        return out;
    }
    static String activeName(){return NAMES[active];}
    static String requested(){return preferences==null?NAMES[active]:NAMES[index(preferences.getString("theme",NAMES[active]))];}
    static boolean choose(String name){
        for(String valid:NAMES)if(valid.equals(name)){return preferences!=null && preferences.edit().putString("theme",name).commit();}
        throw new IllegalArgumentException("Unknown theme");
    }
    /** Every colour source swapped in place, then each screen rebuilt from them; the process and playback carry on. False leaves the running theme alone. */
    static boolean apply(Context host,String name){
        android.app.Activity shown=activity(host);
        int next=index(name);
        if(base==null || shown==null || android.os.Build.VERSION.SDK_INT<30 || !PaletteResources.swap(base,name,next==indexDefault()))return false;
        active=next;
        swapped=true;
        p.S_EncoreColorTokens.rebuild();
        p.S_EncoreTheme.rebuild();
        reopen=true;
        RowCards.watch(shown.getApplication()); // too early at startup: there's no application to ask yet
        if(!OPEN.contains(shown))OPEN.add(shown);
        for(android.app.Activity a:new java.util.ArrayList<>(OPEN)){PaletteResources.sync(a.getResources());a.recreate();}
        return true;
    }
    private static android.app.Activity activity(Context c){
        while(c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity))c=((android.content.ContextWrapper)c).getBaseContext();
        return c instanceof android.app.Activity?(android.app.Activity)c:null;
    }
    static void created(android.app.Activity a){
        OPEN.add(a);
        if(swapped)PaletteResources.sync(a.getResources());
    }
    static void destroyed(android.app.Activity a){OPEN.remove(a);}
    /** The settings page went with its screen, so it comes back on the first one to return. */
    static void resumed(android.app.Activity a){
        if(!reopen)return;
        reopen=false;
        Settings.show(a.getWindow().getDecorView(),true);
    }
    static void restart(Context c){
        android.content.Intent launch=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if(launch==null || launch.getComponent()==null)throw new IllegalStateException("Launcher unavailable");
        c.startActivity(android.content.Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }
    /** Spotify brand greens in Encore's static Compose palette (p.S_SearchFieldColors): accent, then pale mints (badges). */
    private static final int[] GREENS={0x1ed760,0x1db954,0x19e68c},MINTS={0x91eeda,0xc5f0c9};
    public static int color(int value){
        int rgb=value&0xffffff;
        for(int i=0;i<BASE.length;i++)if(rgb==BASE[i])return (value&0xff000000)|row(active)[i];
        for(int g:GREENS)if(rgb==g)return (value&0xff000000)|row(active)[6];
        for(int g:MINTS)if(rgb==g)return (value&0xff000000)|row(active)[7];
        return value;
    }
    /** One of the running theme's own colours (alpha ignored), so policy that reshades server colours leaves it be. */
    static boolean ours(int argb){for(int c:row(active))if(c==(argb&0xffffff))return true;return false;}
    public static long compose(long argb){return (argb & ~0xffffffffL)==0 ? color((int)argb)&0xffffffffL : argb;}
}
