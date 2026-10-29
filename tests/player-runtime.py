"""Run helper behavior on JVM doubles; does not replace Android/device verification."""
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')) / 'bin'
with tempfile.TemporaryDirectory(prefix='spatch-player-test-') as tmp:
    out = Path(tmp)
    sources = []
    for source in (ROOT / 'theme/dex/stubs').rglob('*.java'):
        text = source.read_text()
        # Turn compile-only graphics/platform methods into inert JVM doubles.
        text = re.sub(r'((?:public|protected) (?:static |final )*([\w.]+) \w+\([^)]*\)) \{ throw new RuntimeException\(\); \}',
                      lambda m: m[1] + ' {' + {'void': '', 'int': 'return 0;', 'float': 'return 0;', 'long': 'return 10000;', 'boolean': 'return false;'}.get(m[2], 'return null;') + '}', text)
        text = text.replace('{ throw new RuntimeException(); }', '{}')
        if source.name == 'View.java':
            text = text.replace('public interface OnClickListener', 'public static View root; public int screenX; public int testHeight=2000; public static long nextDraw; public interface OnClickListener')
            text = text.replace('public static View root;', 'private boolean enabled=true,pressed,hovered; public static View root;')
            text = text.replace('setEnabled(boolean enabled) {}', 'setEnabled(boolean value) {enabled=value;}').replace('isEnabled() { return true; }', 'isEnabled() { return enabled; }')
            text = text.replace('setPressed(boolean pressed) {}', 'setPressed(boolean value) {pressed=value;}').replace('isPressed() { return false; }', 'isPressed() { return pressed; }')
            text = text.replace('setHovered(boolean hovered) {}', 'setHovered(boolean value) {hovered=value;}').replace('isHovered() { return false; }', 'isHovered() { return hovered; }')
            text = text.replace('getRootView() { return null; }', 'getRootView() { if (root != null) return root; View cur = this; while (cur.getParent() instanceof View) { cur = (View) cur.getParent(); } return cur; }')
            text = text.replace('getWidth() { return 0; }', 'getWidth() { return 1000; }').replace('getHeight() { return 0; }', 'getHeight() { return testHeight; }')
            text = text.replace('getContext() { return null; }', 'getContext() { return new android.content.Context(); }')
            text = text.replace('getLocationOnScreen(int[] out) {}', 'getLocationOnScreen(int[] out) {out[0]=screenX;}')
            text = text.replace('public void setSelected(boolean selected) {}', 'private boolean selected; public void setSelected(boolean value) {selected=value;}').replace('isSelected() { return false; }', 'isSelected() { return selected; }')
            text = text.replace('postInvalidateDelayed(long delay) {}', 'postInvalidateDelayed(long delay) { nextDraw = delay; }')
        if source.name == 'MotionEvent.java':
            text = '''package android.view; public final class MotionEvent { public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_HOVER_ENTER=9,ACTION_HOVER_MOVE=7,ACTION_HOVER_EXIT=10; private int action; private float x,y; public int getActionMasked(){return action;} public float getX(){return x;} public float getY(){return y;} public long getEventTime(){return android.os.SystemClock.uptimeMillis();} public static MotionEvent obtain(long d,long t,int a,float x,float y,int m){MotionEvent e=new MotionEvent();e.action=a;e.x=x;e.y=y;return e;} public void recycle(){} }'''
        if source.name == 'Visualizer.java':
            text = text.replace('public class Visualizer {', 'public class Visualizer { public static int session,created,released; public static boolean fail;')
            text = text.replace('public Visualizer(int session) {}', 'public Visualizer(int id) {session=id;created++;}')
            text = text.replace('public int getFft(byte[] fft) { return 0; }', 'public int getFft(byte[] fft) { return fail ? -7 : 0; }')
            text = text.replace('public void release() {}', 'public void release() {released++;}')
        if source.name == 'Context.java':
            text = text.replace('getResources() { return null; }', 'getResources() { return new android.content.res.Resources(); }')
            text = text.replace('getSharedPreferences(String name, int mode) { return null; }', 'getSharedPreferences(String name, int mode) { return new SharedPreferences() { public boolean getBoolean(String k, boolean d) { return d; } public String getString(String k,String d){return d;} public Editor edit() { return null; } }; }')
        if source.name == 'DisplayMetrics.java':
            text=text.replace('float density;', 'float density=1;').replace('int heightPixels;', 'int heightPixels=2000;')
        if source.name == 'SeekBar.java':
            text=text.replace('getProgress() { return 0; }', 'getProgress() { return 50; }').replace('getMax() { return 0; }', 'getMax() { return 100; }')
        if source.name == 'Resources.java':
            text = text.replace('getDisplayMetrics(){return null;}', 'getDisplayMetrics(){return new android.util.DisplayMetrics();}')
        if source.name == 'ViewGroup.java':
            text = text.replace('public int getChildCount() { return 0; }', 'private final java.util.List<View> children = new java.util.ArrayList<View>(); public int getChildCount() { return children.size(); }')
            text = text.replace('public View getChildAt(int i) { return null; }', 'public View getChildAt(int i) { return children.get(i); }')
            text = text.replace('public void addView(View v) {}', 'public void addView(View v) { children.add(v); try { java.lang.reflect.Field pf = View.class.getDeclaredField("parent"); pf.setAccessible(true); pf.set(v, this); } catch (Exception ignored) {} }')
            text = text.replace('public void addView(View v, LayoutParams p) {}', 'public void addView(View v, LayoutParams p) { addView(v); }')
            text = text.replace('public void removeView(View v) {}', 'public void removeView(View v) { children.remove(v); try { java.lang.reflect.Field pf = View.class.getDeclaredField("parent"); pf.setAccessible(true); pf.set(v, null); } catch (Exception ignored) {} }')
        if source.name == 'Rect.java':
            text = text.replace('height() {return 0;}', 'height() {return bottom-top;}')
        if source.name == 'Bitmap.java':
            text = text.replace('return null;', 'return new Bitmap();')
        if source.name == 'ImageView.java':
            text = text.replace('public ImageView(', 'private android.graphics.drawable.Drawable art; public ImageView(')
            text = text.replace('protected void onDraw(android.graphics.Canvas c){}', 'protected void onDraw(android.graphics.Canvas c){if(art!=null)art.draw(c);}')
            text = text.replace('getDrawable(){return null;}', 'getDrawable(){return art;}').replace('setImageDrawable(android.graphics.drawable.Drawable d){}', 'setImageDrawable(android.graphics.drawable.Drawable d){art=d;}')
        if source.name == 'Drawable.java':
            text = text.replace('public interface Callback', 'private final android.graphics.Rect bounds = new android.graphics.Rect(); public interface Callback')
            text = text.replace('setBounds(int l,int t,int r,int b){}', 'setBounds(int l,int t,int r,int b){bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;}')
            text = text.replace('setBounds(android.graphics.Rect r){}', 'setBounds(android.graphics.Rect r){setBounds(r.left,r.top,r.right,r.bottom);}')
            text = text.replace('setState(int[] states){return false;}', 'setState(int[] states){return onStateChange(states);}')
            text = text.replace('getBounds() {return null;}', 'getBounds() {return bounds;}')
        target = out / source.name
        target.write_text(text)
        sources.append(str(target))
    for source in (ROOT / 'theme/dex/src/spatch').glob('*.java'):
        if source.name == 'Palette.java':
            rows = re.findall(r'theme\(\s*"[^"]+",\s*\[([^]]+)\]', (ROOT / 'src/theme.rs').read_text())
            palette = ','.join('{'+','.join(re.findall(r'0x[0-9a-f]+', row))+'}' for row in rows)
            assert len(rows) == 7
            target=out/source.name
            target.write_text(source.read_text().replace('@PALETTES@',palette).replace('@THEME@','lavender'))
            sources.append(str(target))
        else: sources.append(str(source))
    harness = out / 'PlayerRuntimeTest.java'
    harness.write_text(r'''
package spatch;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import java.lang.reflect.*;
public class PlayerRuntimeTest {
    static class Prefs implements android.content.SharedPreferences, android.content.SharedPreferences.Editor {
        java.util.Map<String,String> data=new java.util.HashMap<>();
        java.util.Map<String,Boolean> booleans=new java.util.HashMap<>();
        public String getString(String k,String d){return data.getOrDefault(k,d);}
        public boolean getBoolean(String k,boolean d){return booleans.getOrDefault(k,d);}
        public Editor edit(){return this;}
        public Editor putString(String k,String v){data.put(k,v);return this;}
        public Editor putBoolean(String k,boolean v){booleans.put(k,v);return this;}
        public void apply(){} public boolean commit(){return true;}
    }
    static int clicks;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class TraceCanvas extends Canvas {
        float sx=1, sy=1, tx=0, ty=0; int draws; float drawnScale, drawnX, drawnY; int layerAlpha=-1;
        java.util.ArrayList<float[]> rectangles=new java.util.ArrayList<>();
        public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){rectangles.add(new float[]{r-l,b-t});}
        boolean hasThumb(float height,float min,float max){for(float[] r:rectangles)if(Math.abs(r[1]-height)<.01 && r[0]>min && r[0]<=max)return true;return false;}
        java.util.ArrayList<float[]> saves=new java.util.ArrayList<>();
        public int save(){saves.add(new float[]{sx,sy,tx,ty});return saves.size();}
        public int saveLayerAlpha(float l,float t,float r,float b,int a){layerAlpha=a;return save();}
        public void restoreToCount(int n){float[] s=saves.get(n-1);sx=s[0];sy=s[1];tx=s[2];ty=s[3];while(saves.size()>=n)saves.remove(saves.size()-1);}
        public void translate(float x,float y){tx+=sx*x;ty+=sy*y;}
        public void scale(float x,float y,float px,float py){translate(px,py);sx*=x;sy*=y;translate(-px,-py);}
    }
    static class Art extends Drawable {
        int boundsWrites;
        public void setBounds(int l,int t,int r,int b) {boundsWrites++;super.setBounds(l,t,r,b);}
        public void draw(Canvas c) {if(c instanceof TraceCanvas){TraceCanvas t=(TraceCanvas)c;t.draws++;t.drawnScale=t.sx;t.drawnX=t.tx;t.drawnY=t.ty;}} public void setAlpha(int a) {}
        public void setColorFilter(ColorFilter f) {} public int getOpacity() { return 0; }
    }
    static Object field(Object o, String n) throws Exception { Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    public static void main(String[] args) throws Exception {
        Field active=Palette.class.getDeclaredField("active");active.setAccessible(true);
        int[] accents={0xd0bcff,0xfe8019,0xcba6f7,0xc4a7e7,0x88c0d0,0x7aa2f7,0xbd93f9};
        for(int i=0;i<7;i++){active.setInt(null,i);check(Palette.color(0x80d0bcff)==(0x80000000|accents[i]),"theme mapping must preserve role and alpha");check(Palette.color(0xff123456)==0xff123456,"semantic colour must be preserved");check(Palette.color(0xff1ed760)==(0xff000000|accents[i]),"Spotify green must follow the accent");check(Palette.compose(0xffc5f0c9L)!=0xffc5f0c9L,"badge mint must be themed");}
        active.setInt(null,0);Field pref=Palette.class.getDeclaredField("preferences");pref.setAccessible(true);pref.set(null,new Prefs());
        check(Palette.choose("nord"),"theme must commit before restart");check(Palette.requested().equals("nord") && Palette.activeName().equals("lavender"),"theme selection persists for restart without changing current palette");
        try{Palette.choose("nope");throw new AssertionError("invalid theme accepted");}catch(IllegalArgumentException expected){}
        Prefs spatchPrefs = new Prefs();
        pref.set(null, spatchPrefs);
        Field spatchPrefsField = Settings.class.getDeclaredField("prefs");
        spatchPrefsField.setAccessible(true);
        spatchPrefsField.set(null, spatchPrefs);

        // 1. Drawer row insertion
        java.util.ArrayList rows = new java.util.ArrayList();
        p.ft01 settingsRow = new p.ft01((byte) 1);
        p.ft01 otherRow = new p.ft01((byte) 0);
        rows.add(otherRow);
        rows.add(settingsRow);

        java.util.List modified = Settings.rows(rows);
        check(modified.size() == 3, "drawer must insert Spatch row right before Settings and privacy");
        check(modified.get(1) instanceof Settings, "inserted row must be Settings instance");
        Settings spatchRow = (Settings) modified.get(1);

        // Check row presentation
        p.vtz rowVtz = (p.vtz) spatchRow.a();
        p.fg81 rowFg = (p.fg81) rowVtz.b;
        p.cg81 rowCg = (p.cg81) rowFg.a;
        check(rowCg.c.equals("spotify:spatch:settings"), "drawer URI must be spotify:spatch:settings");
        check(rowCg.b.intValue() == 0x7f131b75, "drawer row title ID must match 0x7f131b75");

        // Drawer without Settings and privacy: left untouched
        java.util.ArrayList noSettingsRows = new java.util.ArrayList();
        noSettingsRows.add(otherRow);
        java.util.List untouched = Settings.rows(noSettingsRows);
        check(untouched.size() == 1 && untouched.get(0) == otherRow, "drawer without settings row must remain untouched");

        // 2. Navigation: open() on root view
        android.view.ViewGroup spatchRoot = new android.widget.FrameLayout(new android.content.Context());
        android.view.View spatchDrawerView = new android.view.View(new android.content.Context());
        spatchRoot.addView(spatchDrawerView);
        Settings.attach(spatchDrawerView);

        check(!Settings.open("unrelated:uri"), "unrelated URI must return false");
        check(Settings.open("spotify:spatch:settings"), "Spatch URI must return true");
        check(spatchRoot.getChildCount() == 2, "SpatchSettings must be added to root view");
        check(spatchRoot.getChildAt(1) instanceof SpatchSettings, "added child must be SpatchSettings");
        SpatchSettings spatchPage = (SpatchSettings) spatchRoot.getChildAt(1);

        // 3. SpatchSettings page controls
        Field featField = SpatchSettings.class.getDeclaredField("featureSwitches");
        featField.setAccessible(true);
        android.widget.Switch[] featSwitches = (android.widget.Switch[]) featField.get(spatchPage);
        check(featSwitches.length == 13, "must contain 13 feature switches");
        for (int i = 0; i < 5; i++) {
            check(featSwitches[i].isChecked() == Settings.get(i), "feature switch " + i + " must reflect Settings.get");
        }

        // Toggle feature switch
        boolean initialWave = Settings.wave();
        featSwitches[0].setChecked(!initialWave);
        Settings.set(0, !initialWave);
        check(Settings.wave() == !initialWave, "toggling wave setting must update Settings");
        check(spatchPrefs.getBoolean("wave", initialWave) == !initialWave, "toggling wave must persist to prefs");
        Settings.set(6, true);
        check(!Wave.animationsEnabled(), "Reduce motion must still every Spatch animation");
        Settings.resetAll();
        check(!Settings.get(6) && Settings.get(0) && Wave.animationsEnabled(), "Reset must restore defaults");

        // 4. Themes: exclusive selection & restart dialog
        Field themeField = SpatchSettings.class.getDeclaredField("themeRows");
        themeField.setAccessible(true);
        View[] thmSwitches = (View[]) themeField.get(spatchPage);
        check(thmSwitches.length == 7, "must contain 7 theme rows");

        // Initial active theme: lavender (index 0)
        check(thmSwitches[0].isSelected(), "initial active theme (lavender) must be checked");
        for (int t = 1; t < 7; t++) {
            check(!thmSwitches[t].isSelected(), "inactive theme " + t + " must be unchecked");
        }

        // Select Gruvbox (index 1) via onThemeSelected
        Method selectTheme = SpatchSettings.class.getDeclaredMethod("onThemeSelected", int.class, String.class);
        selectTheme.setAccessible(true);
        android.app.AlertDialog.Builder.shown = false;
        selectTheme.invoke(spatchPage, 1, "gruvbox");

        check(thmSwitches[1].isSelected(), "Gruvbox switch must be checked after selection");
        check(!thmSwitches[0].isSelected(), "Lavender switch must become unchecked");
        check(Palette.requested().equals("gruvbox"), "selecting Gruvbox must save requested theme");
        check(android.app.AlertDialog.Builder.shown, "changing theme from active lavender to gruvbox must prompt restart dialog");
        check(android.app.AlertDialog.Builder.lastTitle.equals("Restart to apply theme?"), "dialog title must prompt restart");

        // Later behavior: keeps selection
        check(Palette.requested().equals("gruvbox"), "Later must retain saved theme");

        // Select another theme: Nord (index 4)
        selectTheme.invoke(spatchPage, 4, "nord");
        check(thmSwitches[4].isSelected(), "Nord switch must be checked");
        check(!thmSwitches[1].isSelected(), "Gruvbox switch must now be unchecked");
        check(Palette.requested().equals("nord"), "selecting Nord must save requested theme");

        // Selecting already active theme: no dialog
        Palette.choose("lavender");
        Field activeField = Palette.class.getDeclaredField("active");
        activeField.setAccessible(true);
        activeField.setInt(null, 0); // lavender is active
        android.app.AlertDialog.Builder.shown = false;
        selectTheme.invoke(spatchPage, 0, "lavender");
        check(!android.app.AlertDialog.Builder.shown, "selecting already active theme must not show restart dialog");

        // 5. Back navigation: close() removes page from root view
        spatchPage.close(); // starts the slide-out
        spatchPage.run();   // the delayed end of the exit animation
        check(spatchRoot.getChildCount() == 1, "close() must remove SpatchSettings from root view");

        pref.set(null, null);
        spatchPrefsField.set(null, null);
        System.out.println("PASS Spatch settings: drawer insertion, navigation, feature switches, exclusive themes, restart dialog, back navigation");
        System.out.println("PASS seven persistent themes, role mapping, alpha and invalid selection");
        byte[] fft=new byte[1024]; fft[0]=127;fft[1]=127;
        for(float value:AudioSpectrum.bands(fft,48000,42))check(value==0,"silence/DC must not animate bars");
        fft[20]=64;fft[21]=-64;int energetic=0;
        for(float value:AudioSpectrum.bands(fft,48000,42)){check(value>=0 && value<=1,"FFT values must be bounded");if(value>0)energetic++;}
        check(energetic>0 && energetic<42,"tone must affect its frequency bands");
        byte[] pink=new byte[1024];for(int k=1;k<512;k++)pink[k*2]=(byte)Math.round(100/Math.sqrt(k));
        float low=1,high=0;for(float value:AudioSpectrum.bands(pink,48000,24)){low=Math.min(low,value);high=Math.max(high,value);}
        check(high-low<.1f,"equal-energy-per-octave input must not produce a downward tilt");
        System.out.println("PASS weighted FFT bands, pink-noise balance, silence and tones");
        AudioSpectrum.created(91);AudioSpectrum.created(92);AudioSpectrum.destroyed(91);
        android.content.Context allowed=new android.content.Context(){public int checkSelfPermission(String p){return 0;}};
        AudioSpectrum audio=new AudioSpectrum();audio.update(allowed,true);
        check(android.media.audiofx.Visualizer.session==92,"must attach to own live session, never output mix");
        int releases=android.media.audiofx.Visualizer.released;audio.update(allowed,false);
        check(android.media.audiofx.Visualizer.released>releases,"pause must release audio capture");
        audio.update(new android.content.Context(),true);check(audio.status.contains("Tap"),"permission denial needs an explicit prompt");
        AudioSpectrum unavailable=new AudioSpectrum();android.media.audiofx.Visualizer.fail=true;
        unavailable.update(allowed,true);int attempts=android.media.audiofx.Visualizer.created;unavailable.update(allowed,true);
        check(android.media.audiofx.Visualizer.created==attempts,"failed native capture must back off");
        android.media.audiofx.Visualizer.fail=false;AudioSpectrum.destroyed(92);
        System.out.println("PASS own-session capture, permission gate, pause release and error backoff");
        View action=new View(allowed,null){public boolean performClick(){clicks++;return true;}};
        View skipAction=new View(allowed,null){public boolean performClick(){return true;}};
        ImmersiveButton skip=new ImmersiveButton(allowed,3,false);skip.source=skipAction;
        skip.interaction(true,false,true);skip.draw(new Canvas(),new Art(),null,0,0,40,false);
        check(((float[])field(skip,"scale"))[0]==1f,"skip buttons nudge sideways and never scale");
        ImmersiveButton button=new ImmersiveButton(allowed,4,false);button.source=action;
        action.setEnabled(false);check(!button.enabled() && !button.click() && clicks==0,"disabled action must not click");
        button.interaction(true,true,false);View buttonState=(View)field(button,"state");
        check(!buttonState.isPressed() && !buttonState.isHovered(),"disabled action must not highlight");
        button.draw(new Canvas(),new Art(),null,0,0,40,false);
        check((Float)field(button,"alpha")<1,"disabled icons must fade to grey");
        action.setEnabled(true);button.interaction(true,true,false);check(buttonState.isPressed() && buttonState.isHovered(),"enabled action must show press/hover");
        button.interaction(false,false,true);check(((float[])field(button,"scale"))[0]<1,"fast control tap must rebound");
        check(button.click() && clicks==1,"enabled action must dispatch exactly once");
        System.out.println("PASS disabled buttons, hover/press and fast click feedback");
        PlayerArt page = new PlayerArt(new android.content.Context(), null);
        Art art = new Art(); art.setBounds(13,17,301,309); page.setImageDrawable(art);
        check(page.dispatchTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,500,500,0)), "artwork must accept touch sequence");
        page.dispatchTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,500,500,0));
        check(((float[])field(page,"squish"))[0]<1, "fast tap must compress even without a draw between DOWN and UP");
        TraceCanvas fullDecor=new TraceCanvas(),midDecor=new TraceCanvas(),noDecor=new TraceCanvas();
        page.drawHeroDecoration(fullDecor,500,500,880,0,true);
        page.drawHeroDecoration(midDecor,500,500,880,.5f,true);
        page.drawHeroDecoration(noDecor,500,500,880,1,true);
        check(fullDecor.layerAlpha==255 && midDecor.layerAlpha>0 && midDecor.layerAlpha<255 && noDecor.layerAlpha==-1,"ring must fade continuously to the handoff");
        page.drawHeroDecoration(midDecor,500,500,880,.5f,false);
        check(midDecor.layerAlpha>0 && midDecor.layerAlpha<255,"glow must fade continuously");
        System.out.println("PASS ring/glow intermediate fade and exact handoff opacity");
        System.out.println("PASS fast artwork tap");
        Field tap=PlayerArt.class.getDeclaredField("tapAt"); tap.setAccessible(true); tap.setLong(page,9900);
        page.dispatchTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,500,500,0));
        check((Float)field(page,"squishTarget")==1f, "opening immersive must release source artwork press");
        System.out.println("PASS immersive opening releases artwork press");
        Immersive overlay = new Immersive(page.getContext(), page);
        overlay.onDraw(new Canvas());
        ImmersiveButton[] controls=(ImmersiveButton[])field(overlay,"buttons");controls[2].source=action;
        float px=500-(Float)field(overlay,"spacing"),py=(Float)field(overlay,"ctrlY");
        int beforeClicks=clicks;action.setEnabled(false);
        overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,px,py,0));overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,px,py,0));
        check(clicks==beforeClicks,"immersive must reject disabled pointer clicks");
        action.setEnabled(true);
        overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,px,py,0));overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,px,py,0));overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,px,py,0));
        check(clicks==beforeClicks,"cancel must not dispatch an immersive action");
        overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,px,py,0));overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,px,py,0));
        check(clicks==beforeClicks+1,"matched immersive tap must click once");
        overlay.onHoverEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_ENTER,px,py,0));
        check(((View)field(controls[2],"state")).isHovered(),"pointer must reach immersive hover state");
        overlay.onHoverEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_EXIT,px,py,0));
        check(!((View)field(controls[2],"state")).isHovered(),"hover exit must clear feedback");
        System.out.println("PASS immersive hit testing, disabled actions, cancellation and hover exit");
        Field seekField=Immersive.class.getDeclaredField("seekBar");seekField.setAccessible(true);seekField.set(overlay,new android.widget.SeekBar(allowed,null));
        Method drawControls=Immersive.class.getDeclaredMethod("drawControls",Canvas.class,float.class,float.class,long.class);drawControls.setAccessible(true);
        float seekY=(Float)field(overlay,"seekY");
        overlay.onHoverEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_ENTER,500,seekY,0));
        TraceCanvas slider=new TraceCanvas();for(int i=0;i<12;i++)drawControls.invoke(overlay,slider,1000f,2000f,10000L);
        check(slider.hasThumb(18,0,4),"hovered progress must show an 18dp line thumb");
        check(slider.hasThumb(20,3.9f,4.01f),"volume must always show a 20dp line thumb");
        overlay.onHoverEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_EXIT,500,seekY,0));
        for(int i=0;i<24;i++)drawControls.invoke(overlay,new TraceCanvas(),1000f,2000f,10000L);
        slider=new TraceCanvas();drawControls.invoke(overlay,slider,1000f,2000f,10000L);
        check(!slider.hasThumb(18,0,4),"idle progress thumb must fade out");
        float volY=(Float)field(overlay,"volY");overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,500,volY,0));
        slider=new TraceCanvas();drawControls.invoke(overlay,slider,1000f,2000f,10000L);
        check(slider.hasThumb(20,1.9f,3.99f),"volume line must narrow while dragged");
        overlay.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,500,volY,0));
        System.out.println("PASS slider line dimensions, hover exit and drag width");
        check(art.boundsWrites==1, "backdrop capture must not resize shared artwork");
        Rect b=art.getBounds();
        check(b.left==13 && b.top==17 && b.right==301 && b.bottom==309, "immersive mutated shared artwork bounds");
        System.out.println("PASS shared artwork bounds");
        int writes=art.boundsWrites;
        overlay.onDraw(new Canvas());
        check(art.boundsWrites==writes, "immersive render must not change the shared drawable bounds");
        System.out.println("PASS immersive leaves shared drawable geometry untouched");
        page.testHeight=1000;
        float[] sourceSpring=(float[])field(page,"squish");sourceSpring[0]=1;sourceSpring[1]=0;
        TraceCanvas normal=new TraceCanvas();page.onDraw(normal);
        Field opened=Immersive.class.getDeclaredField("openTime");opened.setAccessible(true);opened.setLong(overlay,9750);
        TraceCanvas interrupted=new TraceCanvas();overlay.onDraw(interrupted);
        page.hideForHero(true);
        TraceCanvas hidden=new TraceCanvas();page.onDraw(hidden);
        check(hidden.draws==0, "source artwork must not show behind the flight");
        overlay.dismiss();
        TraceCanvas firstClose=new TraceCanvas();overlay.onDraw(firstClose);
        check(Math.abs(interrupted.drawnScale-firstClose.drawnScale)<0.001 && Math.abs(interrupted.drawnX-firstClose.drawnX)<0.01 && Math.abs(interrupted.drawnY-firstClose.drawnY)<0.01, "Back during opening must continue from displayed artwork/crop");
        System.out.println("PASS interrupted opening stays continuous");
        Field end=Immersive.class.getDeclaredField("closeTime");end.setAccessible(true);end.setLong(overlay,9720);
        TraceCanvas landing=new TraceCanvas();overlay.onDraw(landing);
        check(landing.draws>0, "close animation must render its landing frame before detach");
        check(Math.abs(normal.drawnScale-landing.drawnScale)<0.001 && Math.abs(normal.drawnX-landing.drawnX)<0.01 && Math.abs(normal.drawnY-landing.drawnY)<0.01, "landing crop/scale must match normal ImageView");
        overlay.run();
        TraceCanvas revealed=new TraceCanvas();page.onDraw(revealed);
        check(revealed.draws>0, "source artwork must reappear after handoff");
        System.out.println("PASS return-animation crop and final frame");
        overlay=new Immersive(page.getContext(),page);
        android.os.Build.VERSION.SDK_INT=36;
        View.nextDraw=0;
        overlay.onDraw(new Canvas());
        check(View.nextDraw>0 && View.nextDraw>=100, "reduced motion must still refresh playback");
        System.out.println("PASS reduced-motion playback refresh");
        float[] spring=(float[])field(overlay,"squish"); spring[0]=0.94f; spring[1]=3;
        overlay.onDraw(new Canvas());
        check(spring[0]==1 && spring[1]==0, "reduced motion must snap artwork spring");
        System.out.println("PASS reduced-motion spring");
        PlayerArt next = new PlayerArt(page.getContext(), null);
        next.setImageDrawable(new Art());
        ViewGroup root=new ViewGroup(page.getContext(),null) {
            public int getChildCount() { return 1; } public View getChildAt(int i) { return next; }
        };
        View.root=root; page.screenX=-1000;
        overlay.onDraw(new Canvas());
        check(field(overlay,"playerArt")==next, "immersive must follow centered carousel page");
        System.out.println("PASS carousel page changes");
        Method seek=Immersive.class.getDeclaredMethod("handleSeek", int.class, float.class); seek.setAccessible(true);
        Wave wave=(Wave)field(overlay,"wave");
        seek.invoke(overlay,MotionEvent.ACTION_DOWN,500f);
        check((Boolean)field(wave,"pressed"), "seek wave must show dragging state");
        seek.invoke(overlay,MotionEvent.ACTION_CANCEL,500f);
        check(!(Boolean)field(wave,"pressed"), "cancel must release seek wave");
        System.out.println("PASS seek drag/cancel feedback");
        float[] s={PlayerArt.PRESSED,0}; float peak=0;
        for(int i=0;i<200;i++){PlayerArt.spring(s,1,16); peak=Math.max(peak,s[0]);}
        check(peak>1 && Math.abs(s[0]-1)<0.001, "spring must rebound and settle");
        System.out.println("PASS artwork spring rebound");
    }
}
''')
    subprocess.run([str(JAVA / 'javac'), '-d', str(out / 'classes'), *sources, str(harness)], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', str(out / 'classes'), 'spatch.PlayerRuntimeTest'], check=True)
