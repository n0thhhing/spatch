#!/usr/bin/env python3
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
            text = text.replace('public int getId() { return 0; }', 'public int testId; public int getId() { return testId; }')
            text = text.replace('getRootView() { return null; }', 'getRootView() { if (root != null) return root; View cur = this; while (cur.getParent() instanceof View) { cur = (View) cur.getParent(); } return cur; }')
            text = text.replace('getWidth() { return 0; }', 'getWidth() { return 1000; }').replace('getHeight() { return 0; }', 'getHeight() { return testHeight; }')
            text = text.replace('getContext() { return null; }', 'getContext() { return new android.content.Context(); }')
            text = text.replace('getLocationOnScreen(int[] out) {}', 'getLocationOnScreen(int[] out) {out[0]=screenX;}')
            text = text.replace('public void setSelected(boolean selected) {}', 'private boolean selected; public void setSelected(boolean value) {selected=value;}').replace('isSelected() { return false; }', 'isSelected() { return selected; }')
            text = text.replace('postInvalidateDelayed(long delay) {}', 'postInvalidateDelayed(long delay) { nextDraw = delay; }')
            text = text.replace('public void postInvalidateOnAnimation() {}', 'public void postInvalidateOnAnimation() { nextDraw = 16; }') # the next display frame
            text = text.replace('public void setAlpha(float a) {}', 'public float testAlpha=1; public void setAlpha(float a) {testAlpha=a;}')
            text = text.replace('public void setScaleX(float s) {}', 'public float testScale=1; public void setScaleX(float s) {testScale=s;}')
            text = text.replace('public void setOnClickListener(OnClickListener l) {}', 'private OnClickListener click; public void setOnClickListener(OnClickListener l) {click=l;}')
            text = text.replace('public boolean performClick() { return false; }', 'public boolean performClick() { if (click == null) return false; click.onClick(this); return true; }')
        if source.name == 'TextView.java':
            text = text.replace('public CharSequence getText() { return null; }', 'private CharSequence shown; public CharSequence getText() { return shown; }').replace('public void setText(CharSequence t) {}', 'public void setText(CharSequence t) {shown=t;}')
        if source.name == 'MotionEvent.java':
            text = '''package android.view; public final class MotionEvent { public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_HOVER_ENTER=9,ACTION_HOVER_MOVE=7,ACTION_HOVER_EXIT=10; private int action; private float x,y; public int getActionMasked(){return action;} public float getX(){return x;} public float getY(){return y;} public float getRawX(){return x;} public float getRawY(){return y;} public long getEventTime(){return android.os.SystemClock.uptimeMillis();} public void setAction(int a){} public void offsetLocation(float dx,float dy){} public static MotionEvent obtain(MotionEvent e){return e;} public static MotionEvent obtain(long d,long t,int a,float x,float y,int m){MotionEvent e=new MotionEvent();e.action=a;e.x=x;e.y=y;return e;} public void recycle(){} }'''
        if source.name == 'Visualizer.java':
            text = text.replace('public class Visualizer {', 'public class Visualizer { public static int session,created,released; public static boolean fail;')
            text = text.replace('public Visualizer(int session) {}', 'public Visualizer(int id) {session=id;created++;}')
            text = text.replace('public int getFft(byte[] fft) { return 0; }', 'public int getFft(byte[] fft) { return fail ? -7 : 0; }')
            text = text.replace('public void release() {}', 'public void release() {released++;}')
        if source.name == 'Context.java':
            text = text.replace('getResources() { return null; }', 'getResources() { return new android.content.res.Resources(); }')
            text = text.replace('getSharedPreferences(String name, int mode) { return null; }', 'getSharedPreferences(String name, int mode) { return new SharedPreferences() { public boolean getBoolean(String k, boolean d) { return d; } public String getString(String k,String d){return d;} public java.util.Set<String> getStringSet(String k,java.util.Set<String> d){return d;} public Editor edit() { return null; } }; }')
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
    for source in (ROOT / 'theme/dex/src/spatch').rglob('*.java'):
        if source.name == 'Palette.java':
            rows = re.findall(r'theme\(\s*"[^"]+",\s*\[([^]]+)\]', (ROOT / 'src/theme.rs').read_text())
            palette = ','.join('{'+','.join(re.findall(r'0x[0-9a-f]+', row))+'}' for row in rows)
            assert len(rows) == 8
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
import android.widget.TextView;
import java.lang.reflect.*;
public class PlayerRuntimeTest {
    static class Prefs implements android.content.SharedPreferences, android.content.SharedPreferences.Editor {
        java.util.Map<String,String> data=new java.util.HashMap<>();
        java.util.Map<String,Boolean> booleans=new java.util.HashMap<>();
        public String getString(String k,String d){return data.getOrDefault(k,d);}
        public boolean getBoolean(String k,boolean d){return booleans.getOrDefault(k,d);}
        public Editor edit(){return this;}
        public Editor putString(String k,String v){data.put(k,v);return this;}
        public Editor remove(String k){data.remove(k);booleans.remove(k);return this;}
        public Editor putBoolean(String k,boolean v){booleans.put(k,v);return this;}
        public java.util.Set<String> getStringSet(String k,java.util.Set<String> d){return d;}
        public Editor putStringSet(String k,java.util.Set<String> v){return this;}
        public void apply(){} public boolean commit(){return true;}
    }
    static int clicks;
    static class Recording extends Canvas {
        float degrees, scale=1;
        public void rotate(float d,float x,float y){degrees=d;}
        public void scale(float x,float y,float a,float b){scale=x;}
    }
    static void collect(View view,String text,java.util.List<TextView> out){
        if(view instanceof TextView && text.equals(String.valueOf(((TextView)view).getText()))) out.add((TextView)view);
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collect(((ViewGroup)view).getChildAt(i),text,out);
    }
    static Object runningOf(View view){return Boolean.valueOf(view.testScale!=1);}
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
        M3Switch[] featSwitches = (M3Switch[]) featField.get(spatchPage);
        check(featSwitches.length == Settings.KEYS.length, "must contain every feature switch");
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
        check(thmSwitches.length == Palette.NAMES.length, "one swatch per theme");

        // Initial active theme: lavender (index 0)
        check(thmSwitches[0].isSelected(), "initial active theme (lavender) must be checked");
        for (int t = 1; t < 7; t++) {
            check(!thmSwitches[t].isSelected(), "inactive theme " + t + " must be unchecked");
        }

        // Select Gruvbox (index 1) via onThemeSelected
        Method selectTheme = SpatchSettings.class.getDeclaredMethod("onThemeSelected", int.class, String.class);
        selectTheme.setAccessible(true);
        android.app.AlertDialog.Builder.shown = false;
        Settings.set(6, true); // reduce motion: the dialog path; the colour reveal runs only on a device
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
        Settings.set(6, false);

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

        // Spectrum: varied from the first valid frame, silence stays quiet, a new session starts over.
        Spectrum.reset();
        float[] typical=new float[Spectrum.BANDS];
        for(int i=0;i<typical.length;i++)typical[i]=0.7f+Spectrum.prior(i)+(i%2==0?0.05f:-0.05f);
        Spectrum.step(typical,1,1000,true,false);
        check(Spectrum.seeded, "valid audio must seed the gain at once");
        float bass=0,treble=0,even=0,odd=0; int third=typical.length/3;
        for(int i=0;i<third;i++){bass+=Spectrum.bands[i];treble+=Spectrum.bands[typical.length-1-i];}
        for(int i=0;i<typical.length;i++){if(i%2==0)even+=Spectrum.bands[i];else odd+=Spectrum.bands[i];}
        check(Math.abs(bass-treble)/third<0.25f, "first frame must not be a dome: "+bass/third+" vs "+treble/third);
        check((even-odd)/typical.length*2>0.1f, "first frame must keep band variety");
        Spectrum.reset();
        float[] quiet=new float[Spectrum.BANDS]; java.util.Arrays.fill(quiet,0.01f);
        for(int i=0;i<120;i++)Spectrum.step(quiet,2,1000+i*16,true,false);
        check(!Spectrum.seeded, "silence must not seed the gain");
        for(float v:Spectrum.bands)check(v<0.1f,"silence must stay quiet");
        check(Spectrum.taper(0,false)==1, "taper None must not shape edges");
        System.out.println("PASS spectrum startup");

        // Choices: unknown stored values fall back, reset restores defaults.
        Field prefsField=Settings.class.getDeclaredField("prefs"); prefsField.setAccessible(true); prefsField.set(null,new Prefs());
        for(int i=0;i<Settings.CHOICES.length;i++){
            Settings.setChoice(i,"from-a-newer-build");
            check(Settings.choice(i).equals(Settings.CHOICES[i][0]),"invalid choice must fall back");
            Settings.setChoice(i,Settings.CHOICES[i][Settings.CHOICES[i].length-1]);
        }
        Settings.set(13,true); Settings.hideSection("a",true);
        check(Settings.sectionHidden("a"),"section must hide");
        Settings.resetAll();
        check(Settings.choice(Settings.ART_SPECTRUM).equals("off")&&!Settings.hidePodcasts()&&!Settings.sectionHidden("a"),"reset must clear choices and hides");
        System.out.println("PASS choices and reset");

        // New settings keep today's look for anyone upgrading, and album colours stay readable.
        check(Settings.KEYS.length==Settings.DEFAULTS.length,"every key needs a default");
        for(int i=43;i<Settings.KEYS.length;i++) if(i!=Settings.WIDGET_WALLPAPER&&i!=Settings.HIDE_PREMIUM_TAB&&i!=Settings.HIDE_ACCOUNT_SWITCHER&&i!=Settings.HIDE_PARENTAL_CONTROLS) check(Settings.DEFAULTS[i],"a new feature must start on: "+Settings.KEYS[i]);
        check(!Settings.DEFAULTS[Settings.HIDE_ACCOUNT_SWITCHER]&&!Settings.DEFAULTS[Settings.HIDE_PARENTAL_CONTROLS],"hiding the account switcher and Parental controls is opt-in");
        check(!Settings.DEFAULTS[Settings.HIDE_PREMIUM_TAB],"hiding the Premium tab is opt-in: it removes part of Spotify's own interface");
        p.dsn0 premiumTab=new p.dsn0();premiumTab.b=3;premiumTab.c=new p.sgk1();premiumTab.c.a="spotify:upsell:premium_in_app_destination";
        p.dsn0 createTab=new p.dsn0();createTab.b=4;createTab.c=new p.sgk1();createTab.c.a="spotify:create-menu";
        Settings.set(Settings.HIDE_PREMIUM_TAB,true);
        check(Content.skipTab(premiumTab)&&!Content.skipTab(createTab)&&Content.visualTab(4)==3&&Content.visualTab(2)==2,"the Premium tab goes and later tabs close the gap");
        check(Content.tabCount(java.util.Arrays.asList(createTab,premiumTab,createTab))==2&&Content.hiddenTabs==1,"the bar lays out only the tabs it shows");
        java.lang.reflect.Field navCount=NavIndicator.class.getDeclaredField("count");navCount.setAccessible(true);
        check(navCount.getInt(null)==2,"the selection pill counts the tabs the bar shows");
        Settings.set(Settings.HIDE_PREMIUM_TAB,false);
        check(!Content.skipTab(premiumTab)&&Content.visualTab(4)==4&&Content.tabCount(java.util.Arrays.asList(createTab,premiumTab))==2,"with the switch off every tab stays where it was");
        check(!Settings.DEFAULTS[Settings.WIDGET_WALLPAPER],"the wallpaper widget is opt-in: on by default it would recolour an existing widget");
        check(Settings.choice(Settings.ART_SKIP).equals("slide")&&Settings.choice(Settings.ART_OUTLINE).equals("wavy")&&Settings.choice(Settings.IMM_CHANGE).equals("pop")&&Settings.choice(Settings.GLOW_COLOR).equals("accent"),"defaults must match the existing look");
        check(Tint.vivid(new int[] {0xff202020,0xff400808,0xff101010})==(0xff000000|220<<16|28<<8|28),"the most colourful pixel wins and is lifted");
        check(Tint.vivid(new int[] {0xffffffff})==0xffffffff,"a bright pixel is not darkened");
        System.out.println("PASS new setting defaults and album colour");

        // Content: identity comes from URIs and types; mixed shelves keep music.
        java.util.ArrayList lib=new java.util.ArrayList();
        p.ndy[] types={p.ndy.b,p.ndy.Y,p.ndy.a,p.ndy.g,new p.ndy()};
        for(p.ndy t:types){p.dsm1 d=new p.dsm1(); d.e=t; lib.add(d);}
        check(Content.library(lib)==lib,"filters off must pass the list through");
        Settings.set(13,true);
        check(Content.library(lib).size()==3,"podcasts hidden: shows and new episodes go");
        check(!Content.blocked("spotify:album:x","mixed")&&Content.blocked("spotify:show:x","mixed"),"mixed shelf keeps music");
        check(!Content.blocked("spotify:show:x","popular-audiobooks"),"podcast filter must leave audiobook shelves");
        java.util.ArrayList chips=new java.util.ArrayList(java.util.Arrays.asList(new p.e5c0(),new p.l4c0(),new p.u4c0(),new p.r4c0(),"music"));
        check(Content.libraryChips(chips).size()==3,"podcast chips go");
        Settings.set(14,true);
        check(Content.library(lib).size()==1&&Content.libraryChips(chips).size()==1,"both hidden: only music stays");
        check(Content.blocked("spotify:show:x","popular-audiobooks"),"audiobook filter covers its shelves");
        Settings.resetAll();
        check(Content.slug("spotify:section:0abc|made-for-you").equals("made-for-you"),"slug");
        int surface=0xff2b2640, fallback=0xffd0bcff;
        check(MiniEq.accent(new int[]{0xff808080,0xff202020},surface,fallback)==fallback,"grey art keeps the theme accent");
        int picked=MiniEq.accent(new int[]{0xff808080,0xff800020},surface,fallback);
        check(picked!=fallback&&MiniEq.contrast(picked,surface)>=3,"art accent is lifted until it reads on the card");
        float[] leg=NavIndicator.edges(0,2,0.4f,100,64);
        check(leg[1]-leg[0]>64,"indicator stretches while moving");
        float[] done=NavIndicator.edges(0,2,1,100,64);
        check("following".equals(FilterRow.event("music","music","following")),"open parent returns to itself, not All");
        check("music".equals(FilterRow.event("music","music",null)),"parent without a child keeps its own tap");
        check("podcasts".equals(FilterRow.event("podcasts","music","following")),"other chips keep their own tap");
        check(Expansion.landing(1000,true)==880&&Expansion.landing(1000,false)==1000,"mini player flight lands on the art's resting size");
        check(BannerImport.sample(1600,1600)==1&&BannerImport.sample(6400,1600)==4&&BannerImport.sample(1000,1600)==1,"banner decode size bound");
        java.util.List<String> reviewHome=java.util.Arrays.asList("a","c");
        check(HomeReview.section(0,1,reviewHome)==null,"the chip header is not a section");
        check("a".equals(HomeReview.section(1,1,reviewHome)),"the header shifts positions by one");
        check("c".equals(HomeReview.section(1,0,reviewHome)),"no header, no shift");
        check(HomeReview.section(2,0,reviewHome)==null,"nothing past the last section");
        check(HomeReview.commits(-400,1000)&&HomeReview.commits(400,1000),"a swipe either way hides");
        check(!HomeReview.commits(300,1000),"a short swipe springs back");
        float[] mid=FilterRow.blob(0,60,100,180,0.4f), landed=FilterRow.blob(0,60,100,180,1f), back=FilterRow.blob(100,180,0,60,0.4f);
        check(mid[1]-mid[0]>80,"filter pill stretches between chips");
        check(back[1]-back[0]>80&&back[0]<50,"filter pill leads going backwards too");
        check(Math.abs(landed[0]-100)<.01f&&Math.abs(landed[1]-180)<.01f,"filter pill lands on the chip");
        check(FilterRow.nudge(0.25f)>0&&FilterRow.nudge(0.75f)<0&&Math.abs(FilterRow.nudge(1f))<.0001f,"filter nudge rebounds and rests");
        check(Math.abs(done[0]-218)<.01f&&Math.abs(done[1]-282)<.01f,"indicator settles around the destination");
        check(NavIndicator.dragPosition(250,400,4,false)==2&&Math.round(NavIndicator.dragPosition(210,400,4,false))==2,"a drag reads the tab under the finger");
        check(NavIndicator.dragPosition(-30,400,4,false)==0&&NavIndicator.dragPosition(430,400,4,false)==3,"a drag past the ends clamps to the outer tabs");
        check(NavIndicator.dragPosition(50,400,4,true)==3,"an RTL drag mirrors the tabs");
        p.kk1 off=new p.kk1(),on=new p.kk1(); off.a=p.lk1.a; on.a=p.lk1.b;
        check(SaveBurst.celebrates(true,off,on),"a tapped save celebrates");
        check(!SaveBurst.celebrates(false,off,on)&&!SaveBurst.celebrates(true,on,off)&&!SaveBurst.celebrates(true,off,off)&&!SaveBurst.celebrates(true,null,on),"initial, unsave and failed states stay quiet");
        check(Surfaces.radius(0)==0&&Surfaces.radius(4)==8&&Surfaces.radius(500)==500,"corner scale");
        check(Surfaces.tile(0xffdc148cL<<32)!=0xffdc148cL<<32,"server tile is toned");
        check(Content.title(null,"spotify:section:0|musicfeed-for-fans-of[2]").equals("For fans of"),"feed ids read as titles");
        check(Content.title(null,"spotify:section:0|more-like-artist-listens-favorites[1]").equals("More like your artists"),"indexed feeds merge");
        System.out.println("PASS content filters");

        // Navigation colours: ends are exact, the middle is per channel, other colour spaces switch.
        long white=0xffffffffL<<32, black=0xff000000L<<32;
        check(Pill.mix(black,white,0)==black&&Pill.mix(black,white,1)==white,"mix ends");
        check(Pill.mix(black,white,0.5f)==(0xff808080L<<32),"mix middle");
        check(Pill.mix(black,white|1,0.5f)==(white|1),"non-sRGB colours switch");
        System.out.println("PASS navigation colours");

        // Fonts: every bundled file parses, and malformed files are rejected with a message.
        for (java.io.File file : new java.io.File(System.getProperty("fonts")).listFiles()) {
            if (!file.getName().endsWith(".ttf")) continue;
            byte[] font = java.nio.file.Files.readAllBytes(file.toPath());
            FontFile info = FontFile.parse(font);
            check(info.family != null, file.getName() + " has a family name");
            check(info.variable == !file.getName().startsWith("atkinson"), file.getName() + " axes");
            for (int cut : new int[] {0, 5, 11, 12, 40, 200, font.length / 2}) {
                try {
                    FontFile.parse(java.util.Arrays.copyOf(font, cut));
                    check(false, "truncated at " + cut + " was accepted");
                } catch (IllegalArgumentException e) {
                    check(e.getMessage() != null, "truncation has a message");
                }
            }
            byte[] broken = font.clone();
            broken[12 + 8] = 0x7f; // first table's offset, far past the end
            boolean rejected = false;
            try { FontFile.parse(broken); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "out of range table is rejected");
        }
        FontFile monaspace = FontFile.parse(java.nio.file.Files.readAllBytes(new java.io.File(System.getProperty("fonts"), "monaspace-neon.ttf").toPath()));
        check(monaspace.minWeight == 200 && monaspace.maxWeight == 800, "wght axis range");
        for (String magic : new String[] {"wOFF", "wOF2", "ttcf", "PK34", "<svg"}) {
            byte[] other = java.util.Arrays.copyOf(magic.getBytes("ISO-8859-1"), 64);
            boolean rejected = false;
            try { FontFile.parse(other); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, magic + " is rejected");
        }
        boolean big = false;
        try { FontFile.parse(new byte[FontFile.MAX_BYTES + 1]); } catch (IllegalArgumentException e) { big = e.getMessage().contains("25 MB"); }
        check(big, "oversized files are rejected");
        check(FontFile.cleanName("  ../../evil\n ").equals("../../evil"), "names lose controls and stay display text");
        check(FontFile.cleanName(" \t ") == null, "blank names are refused");
        check(FontFile.cleanName("x".repeat(99)).length() == 48, "names are capped");
        check(Fonts.weight(0x7f09000d) == 400 && Fonts.weight(0x7f090006) == 700 && Fonts.weight(0x7f090012) == 700 && Fonts.weight(0x7f090015) == 800, "Spotify roles keep their weights");
        check(Fonts.weight(0x7f090001) == 0 && Fonts.weight(0x7f09001c) == 0, "the icon font is never replaced");
        check(Fonts.resource(0x7f09000d) == null, "Default leaves Spotify's fonts alone");
        check(Fonts.variable(800) == 0x7f090002, "Default keeps the variable font");
        System.out.println("PASS fonts");

        // Side menu: hidden providers leave, Settings and privacy stays and the Spatch row sits above it.
        java.util.ArrayList menu = new java.util.ArrayList();
        p.ft01 recents = new p.ft01((byte) 0), settings = new p.ft01((byte) 1);
        p.nbd0 chats = new p.nbd0((byte) 0), updates = new p.nbd0((byte) 1);
        p.vse1 stats = new p.vse1();
        menu.add(recents); menu.add(stats); menu.add(chats); menu.add(updates); menu.add(settings);
        check(Settings.rows(menu).size() == 6, "nothing is hidden by default");
        Settings.set(21, true); Settings.set(22, true); Settings.set(15, true);
        java.util.List shown = Settings.rows(menu);
        check(!shown.contains(recents) && !shown.contains(stats) && !shown.contains(chats), "hidden rows leave");
        check(shown.contains(updates) && shown.contains(settings) && shown.size() == 3, "other rows stay");
        Settings.resetAll();
        check(Settings.rows(menu).size() == 6, "reset restores the rows");
        System.out.println("PASS side menu");

        // Tables stay in step, and every setting can be reached and reset from some page.
        check(new java.util.HashSet<String>(java.util.Arrays.asList(Settings.KEYS)).size()==Settings.KEYS.length,"stored keys must be unique");
        check(new java.util.HashSet<String>(java.util.Arrays.asList(Settings.CHOICE_KEYS)).size()==Settings.CHOICES.length && Settings.CHOICE_KEYS.length==Settings.CHOICES.length,"choice keys must be unique and complete");
        for(String name:new String[] {"FEATURE_TITLES","FEATURE_SUBTITLES","FEATURE_ICONS"}) {
            Field table=SpatchSettings.class.getDeclaredField(name); table.setAccessible(true);
            check(((String[])table.get(null)).length==Settings.KEYS.length,name+" must have one entry per setting");
        }
        Settings.resetAll();
        SpatchSettings full=new SpatchSettings(new android.content.Context());
        View[] rowsByFeature=(View[])field(full,"featureRows");
        java.util.Map masters=(java.util.Map)field(full,"masterPages");
        for(int i=0;i<rowsByFeature.length;i++) check(masters.containsKey(Integer.valueOf(i)) || rowsByFeature[i].getParent()!=null,"no page shows setting "+Settings.KEYS[i]);
        View[] choices=(View[])field(full,"choiceRows");
        for(int i=0;i<choices.length;i++) check(choices[i]!=null,"no page shows choice "+Settings.CHOICE_KEYS[i]);
        System.out.println("PASS every setting and choice is on a page");
        for(int i=0;i<Settings.KEYS.length;i++) Settings.set(i,!Settings.DEFAULTS[i]);
        for(int i=0;i<Settings.CHOICES.length;i++) Settings.setChoice(i,Settings.CHOICES[i][Settings.CHOICES[i].length-1]);
        java.util.ArrayList<TextView> resets=new java.util.ArrayList<TextView>();
        collect(full,"Reset this page",resets);
        check(resets.size()>10,"pages must offer scoped resets");
        for(TextView reset:resets) check(reset.performClick(),"reset must have a handler");
        for(int i=0;i<Settings.KEYS.length;i++) check(Settings.get(i)==Settings.DEFAULTS[i],"no page resets "+Settings.KEYS[i]);
        for(int i=0;i<Settings.CHOICES.length;i++) check(Settings.choice(i).equals(Settings.CHOICES[i][0]),"no page resets "+Settings.CHOICE_KEYS[i]);
        System.out.println("PASS every setting has a scoped reset");

        android.os.Build.VERSION.SDK_INT=0; // an earlier test left system animations off
        // Fine-tuning switches depend on their main switch only where the feature needs it.
        Settings.set(43,false); check(!Settings.navDrag(),"no pill, no drag"); Settings.set(43,true); check(Settings.navDrag(),"drag returns with the pill");
        check(Settings.buttons()==Wave.animationsEnabled(),"button motion follows system animations");
        Settings.set(6,true); check(!Settings.springPress(),"reduce motion stops springs"); Settings.set(6,false); check(Settings.springPress(),"springs return");
        float[] flat=new float[Spectrum.BANDS]; flat[0]=.3f; flat[Spectrum.BANDS-1]=.9f; flat[12]=.5f;
        check(PlayerArt.audioLevel(0,flat)==.3f && Math.abs(PlayerArt.audioLevel(180,flat)-.9f)<.0001f,"bass at the top, treble at the bottom");
        for(int d=0;d<=180;d++) check(PlayerArt.audioLevel(d,flat)==PlayerArt.audioLevel((360-d)%360,flat) || d==0,"ring must be mirrored at "+d);
        float loudest=1.045f+.012f+1.12f*PlayerArt.AUDIO_REACH+.012f*PlayerArt.BOLD/2;
        check(loudest<1/.88f,"loudest bold ring must stay inside the view, reached "+loudest);
        System.out.println("PASS fine-tuning dependencies and audio ring");

        // Skip animation on a carousel page, half a page from centre.
        View.root=null;
        PlayerArt carousel=new PlayerArt(new android.content.Context(),null);
        carousel.setImageDrawable(new Art());
        carousel.screenX=500;
        Recording canvas=new Recording();
        Settings.setChoice(Settings.ART_OUTLINE,"none"); // the ring has its own rotation
        Settings.setChoice(Settings.ART_SKIP,"slide"); View.nextDraw=0; carousel.onDraw(canvas);
        check(canvas.degrees==0 && View.nextDraw==250,"Slide must leave Spotify's slide alone and idle neighbours: "+canvas.degrees+" "+View.nextDraw+" "+carousel.isCurrentPage());
        Settings.setChoice(Settings.ART_SKIP,"spin"); View.nextDraw=0; carousel.onDraw(canvas);
        check(Math.abs(canvas.degrees-100)<.01f && View.nextDraw==16,"Spin must roll with the offset, every frame, got "+canvas.degrees);
        carousel.screenX=-500; carousel.onDraw(canvas); check(Math.abs(canvas.degrees+100)<.01f,"the other side must roll the other way");
        carousel.screenX=500; Settings.setChoice(Settings.ART_SKIP,"shrink"); canvas.degrees=0; carousel.onDraw(canvas);
        check(Math.abs(canvas.scale-.825f)<.001f && canvas.degrees==0,"Shrink must scale with the offset and not turn, got "+canvas.scale+" "+canvas.degrees);
        Settings.setChoice(Settings.ART_SKIP,"fade"); canvas.degrees=0; carousel.onDraw(canvas);
        check(Math.abs(carousel.testAlpha-.3f)<.001f && canvas.degrees==0,"Fade must fade without turning, got "+carousel.testAlpha);
        check(Math.abs(PlayerArt.settle(1,0,21)-.95f)<.001f,"a skip that lands at once must still ease in, got "+PlayerArt.settle(1,0,21));
        check(Math.abs(PlayerArt.settle(1,0,5000)-(1-32/PlayerArt.ARRIVE_MILLIS))<.001f,"a slow neighbour's gap between frames must not finish the arrival");
        check(PlayerArt.settle(.5f,.49f,16)==.49f,"a finger drag must be followed exactly");
        check(PlayerArt.settle(.2f,.6f,16)==.6f,"leaving must not lag");
        carousel.screenX=0; carousel.onDraw(canvas);
        check(carousel.testAlpha>.29f&&carousel.testAlpha<.4f,"landing on centre must not jump to full opacity, got "+carousel.testAlpha);
        // Spotify can jump straight to the new page: a song change alone must start the arrival.
        java.lang.reflect.Constructor<TextSlide> slideNew=TextSlide.class.getDeclaredConstructor(android.widget.TextView.class); slideNew.setAccessible(true);
        TextSlide slide=slideNew.newInstance(new android.widget.TextView(new android.content.Context()));
        java.util.function.Function<String,android.text.Editable> typed=t->(android.text.Editable)java.lang.reflect.Proxy.newProxyInstance(android.text.Editable.class.getClassLoader(),new Class<?>[]{android.text.Editable.class},(o,m,a)->t);
        java.lang.reflect.Field songAt=PlayerArt.class.getDeclaredField("songAt"); songAt.setAccessible(true);
        slide.afterTextChanged(typed.apply("First")); check(songAt.getLong(null)<0,"the first title seen must not start an arrival");
        try { slide.afterTextChanged(typed.apply("Next song")); } catch(RuntimeException stubbedAnimator) {}
        check(songAt.getLong(null)>0,"a new title must start the arrival");
        PlayerArt leaving=new PlayerArt(new android.content.Context(),null); leaving.setImageDrawable(new Art()); leaving.screenX=0;
        Settings.setChoice(Settings.ART_SKIP,"fade"); songAt.setLong(null,-100000); leaving.onDraw(canvas); Thread.sleep(2);
        carousel.screenX=500; carousel.onDraw(canvas); Thread.sleep(2);
        try { slide.afterTextChanged(typed.apply("Third song")); } catch(RuntimeException stubbedAnimator) {}
        leaving.onDraw(canvas);
        check(leaving.testAlpha==1,"the page already centred is the old song and must not snap away, got "+leaving.testAlpha);
        carousel.screenX=0; songAt.setLong(null,songAt.getLong(null)-1); // the test clock can stand still: this page landed after the title changed
        Settings.setChoice(Settings.ART_SKIP,"shrink"); carousel.onDraw(canvas);
        check(canvas.scale<.67f,"Shrink must start small on a song change with no slide, got "+canvas.scale);
        Settings.setChoice(Settings.ART_SKIP,"fade"); carousel.onDraw(canvas);
        check(carousel.testAlpha<.08f,"Fade must start clear on a song change with no slide, got "+carousel.testAlpha);
        check(PlayerArt.arriving((long)PlayerArt.ARRIVE_MILLIS)==0,"the arrival must end");
        songAt.setLong(null,-100000);
        Settings.setChoice(Settings.ART_SKIP,"slide"); carousel.onDraw(canvas);
        check(carousel.testAlpha==1,"leaving Fade must restore full opacity");
        Settings.setChoice(Settings.ART_SKIP,"spin"); Settings.set(6,true); canvas.degrees=0; carousel.screenX=500; carousel.onDraw(canvas);
        check(canvas.degrees==0,"reduce motion must stop Spin"); Settings.set(6,false); Settings.setChoice(Settings.ART_SKIP,"slide");
        Settings.setChoice(Settings.ART_OUTLINE,"wavy");
        check(ArtMotion.angleStep(170,-170)==20&&ArtMotion.angleStep(-170,170)==-20,"a turn across the seam is a small step");
        check(ArtMotion.scrubbed(1000,-3600,60000)==0&&ArtMotion.scrubbed(59000,3600,60000)==60000&&ArtMotion.scrubbed(0,360,60000)==30000,"one turn is 30 seconds, kept inside the song");
        check(HoldSeek.landing(5000,-10000,60000)==0&&HoldSeek.landing(58000,10000,60000)==59000&&HoldSeek.landing(5000,10000,0)==15000,"hold seek stops before the ends");
        check(SeekBubble.time(61000).equals("1:01")&&SeekBubble.time(600000).equals("10:00")&&SeekBubble.time(-5).equals("0:00"),"time label");
        check(SpatchSettings.matches(Settings.ART_SCRATCH,"scratch")&&SpatchSettings.matches(Settings.HIDE_BARS,"slide away")&&!SpatchSettings.matches(Settings.ART_TILT,"zzz"),"settings search reads titles and subtitles");
        for(int setting:SpatchSettings.PRESET) check(Settings.DEFAULTS[setting]||setting==Settings.LIBRARY_HOLD||setting==Settings.PLAYLIST_HOLD||setting==Settings.LIBRARY_GLOW||setting==Settings.PLAYLIST_GLOW||setting==Settings.LIBRARY_CASCADE||setting==Settings.PLAYLIST_CASCADE||setting==Settings.BURST,"a preset covers only playful extras, got "+Settings.KEYS[setting]);
        check(!SpatchWidget.skips(5),"an unknown widget stays Glance's");
        String saved=Settings.export(); Settings.set(Settings.ART_TILT,false); Settings.setChoice(Settings.ART_SHAPE,"soft");
        check(Settings.importText(saved)==Settings.KEYS.length+Settings.CHOICES.length,"export must round trip");
        check(Settings.get(Settings.ART_TILT)&&Settings.choice(Settings.ART_SHAPE).equals("circle"),"import must restore");
        check(Settings.importText("nonsense\nart_shape=hexagon\nart_tilt=maybe\n=true")==0 && Settings.choice(Settings.ART_SHAPE).equals("circle"),"bad text must change nothing");
        check(RowCards.fits(56,1000,1000)&&!RowCards.fits(39,1000,1000)&&!RowCards.fits(216,1000,1000)&&!RowCards.fits(56,400,1000),"cards fit list rows, not headings, shelves or tiles");
        check(Surfaces.chroma(Surfaces.soften(0xffe91e8c))<Surfaces.chroma(0xffe91e8c)&&(Surfaces.soften(0x80e91e8c)>>>24)==0x80,"softening quiets a colour and keeps its opacity");
        check(!MiniRing.moved(5000,5005,60)&&MiniRing.moved(5000,5020,60)&&MiniRing.moved(5000,4999,60)&&MiniRing.moved(0,10000,60),"the mini ring redraws once its arc visibly moves");
        check(SpatchWidget.isAd("spotify:ad:abc123")&&!SpatchWidget.isAd("spotify:track:abc")&&!SpatchWidget.isAd(null),"ads are told apart from songs by their item id");
        check(Surfaces.black(0x66121212)&&Surfaces.black(0xb3181818)&&!Surfaces.black(0xff141414),"Spotify's page blacks are recognised at any opacity");
        check(Surfaces.grey(0xff535353)&&!Surfaces.grey(0xff101010)&&!Surfaces.grey(0x80535353)&&!Surfaces.grey(0xffe91e8c)&&Surfaces.chroma(Surfaces.tintGrey(0xff535353))>0,"flat header greys take a theme tint; scrims and colours do not");
        java.lang.reflect.Field wallpaperRow=Palette.class.getDeclaredField("wallpaper");wallpaperRow.setAccessible(true);
        Object keptRow=wallpaperRow.get(null);
        wallpaperRow.set(null,new int[]{1,2,3,4,5,6,0x123456,8,9});
        int lavenderAccent=Palette.tone(0,6)&0xffffff;
        byte[] recolored=Palette.recolor(new byte[]{0,8,0,0,0x1c,(byte)lavenderAccent,(byte)(lavenderAccent>>8),(byte)(lavenderAccent>>16),(byte)0x80,9});
        check(recolored[5]==0x56&&recolored[6]==0x34&&recolored[7]==0x12&&recolored[8]==(byte)0x80&&recolored[0]==0&&recolored[9]==9,"wallpaper theme recolours Lavender's values and keeps their opacity");
        check(Palette.tone(Palette.WALLPAPER,6)==0xff123456,"wallpaper theme reads its own row");
        wallpaperRow.set(null,keptRow);
        System.out.println("PASS skip animations");

        // Decoration colours.
        Settings.setChoice(Settings.GLOW_COLOR,"accent"); check(Tint.color(Settings.GLOW_COLOR)==Palette.color(0xffd0bcff),"Theme must be the theme's accent");
        for(int i=2;i<Tint.NAMES.length;i++){Settings.setChoice(Settings.GLOW_COLOR,Tint.NAMES[i]);check(Tint.color(Settings.GLOW_COLOR)==Tint.SWATCHES[i],"swatch "+Tint.NAMES[i]);}
        check(Tint.vivid(new int[] {0xff000000})==0xffdcdcdc,"a black cover must keep a visible glow");
        check(Tint.vivid(new int[] {0xff808080})==0xffdcdcdc,"grey stays grey and readable");
        Field fromTint=Tint.class.getDeclaredField("from"),toTint=Tint.class.getDeclaredField("to"),atTint=Tint.class.getDeclaredField("changedAt");
        fromTint.setAccessible(true);toTint.setAccessible(true);atTint.setAccessible(true);
        fromTint.setInt(null,0xff000000);toTint.setInt(null,0xffffffff);
        Settings.setChoice(Settings.GLOW_COLOR,"album");
        atTint.setLong(null,android.os.SystemClock.uptimeMillis()-450); int midTone=Tint.color(Settings.GLOW_COLOR)&255;
        atTint.setLong(null,android.os.SystemClock.uptimeMillis()-5000); int endTone=Tint.color(Settings.GLOW_COLOR)&255;
        check(midTone>60 && midTone<200 && endTone==255,"album colour must fade between songs, mid "+midTone+" end "+endTone);
        Settings.setChoice(Settings.GLOW_COLOR,"accent");
        System.out.println("PASS decoration colours");

        // Spring: bounces past rest, settles exactly, and leaves the view alone when off.
        View springy=new View(new android.content.Context());
        Springy spring2=new Springy(springy);
        spring2.onTouch(springy,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0,0,0));
        spring2.onTouch(springy,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,0,0,0));
        Field drawnAt=Springy.class.getDeclaredField("drawn"),runningNow=Springy.class.getDeclaredField("running");drawnAt.setAccessible(true);runningNow.setAccessible(true);
        float highest=0;int frames=0;
        do{drawnAt.setLong(spring2,android.os.SystemClock.uptimeMillis()-16);spring2.run();highest=Math.max(highest,springy.testScale);}while(runningNow.getBoolean(spring2)&&++frames<400);
        check(frames<400 && springy.testScale==1,"spring must settle at exactly 1");
        check(highest>1 && highest<1.03f,"spring must overshoot a little, reached "+highest);
        View still=new View(new android.content.Context());
        Settings.set(50,false);new Springy(still).onTouch(still,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0,0,0));
        check(!((Boolean)runningOf(still)),"off must not start a spring");Settings.set(50,true);
        System.out.println("PASS springy press");

        // Developer options: every test reports, and a broken test can't hide the others.
        java.util.List<DevTests.Result> outcome=DevTests.run(new android.content.Context(),null);
        check(outcome.size()>=17,"self tests must all report, got "+outcome.size());
        check(DevTests.summary(outcome).contains(" passed, "),"summary");
        for(DevTests.Result r:outcome) if(r.name.equals("Settings tables match")||r.name.equals("Stored choices are valid")||r.name.equals("Spring settles")||r.name.equals("Album colour stays visible")||r.name.equals("Audio ring fits its view")) check(!r.failed(),r.toString());
        for(DevTests.Result r:outcome) if(r.name.equals("Navigation bar is attached")) check(!r.failed() && r.toString().startsWith("SKIP"),"no window must skip, not fail");
        Settings.resetAll();
        check(DevOptions.changedSettings().equals("All defaults\n"),"defaults must read as such");
        Settings.set(6,true); Settings.setChoice(Settings.ART_SKIP,"spin");
        String changed=DevOptions.changedSettings();
        check(changed.contains("reduce_motion = true")&&changed.contains("art_skip = spin")&&!changed.contains("wave ="),"report lists only changes: "+changed);
        Settings.resetAll();
        check(DevOptions.trace(Thread.currentThread(),new IllegalStateException("boom")).contains("boom"),"crash record keeps the message");
        collect(full,"Run all tests",resets=new java.util.ArrayList<TextView>());
        check(!resets.isEmpty(),"Developer options must be on the page in a debug helper set");
        System.out.println("PASS developer options");

        // Burst: options change what is thrown, pieces leave and vanish, and each burst cleans up.
        check(Confetti.count("few")<Confetti.count("normal") && Confetti.count("normal")<Confetti.count("lots"),"amount");
        check(Confetti.scale("small")<1 && Confetti.scale("big")>1,"size");
        for(String shape:Settings.CHOICES[Settings.BURST_SHAPE]) check(Confetti.shapes(shape).length>0,"no shapes for "+shape);
        check(Confetti.shapes("hearts").length==1 && Confetti.shapes("mixed").length==6,"shape sets");
        check(Confetti.place(0,1,0,100,50)[0]==0 && Confetti.place(1,1,0,100,50)[0]==100,"pieces travel their reach");
        check(Confetti.place(1,0,0,100,50)[1]==50,"pieces fall");
        check(Confetti.place(.5f,1,0,100,0)[0]>50,"pieces start fast");
        check(Confetti.pieceScale(0)==0 && Confetti.pieceScale(.15f)==1 && Confetti.pieceScale(1)==.5f,"pieces pop in");
        check(Confetti.pieceAlpha(.5f)==1 && Confetti.pieceAlpha(1)<.001f,"pieces are gone at the end");
        Field liveBursts=Confetti.class.getDeclaredField("live"); liveBursts.setAccessible(true);
        View host=new View(new android.content.Context());
        int before=liveBursts.getInt(null);
        Settings.set(Settings.BURST,false); Confetti.wanted(host,0,0,true,Settings.BURST_PLAYER);
        check(liveBursts.getInt(null)==before,"main switch off must not burst"); Settings.set(Settings.BURST,true);
        Settings.set(Settings.BURST_MINI,false); Confetti.wanted(host,0,0,true,Settings.BURST_MINI);
        check(liveBursts.getInt(null)==before,"a place's switch off must not burst"); Settings.set(Settings.BURST_MINI,true);
        Settings.setChoice(Settings.BURST_WHEN,"play"); Confetti.wanted(host,0,0,false,Settings.BURST_PLAYER);
        check(liveBursts.getInt(null)==before,"Play only must skip pause"); Settings.setChoice(Settings.BURST_WHEN,"both");
        Settings.set(6,true); Confetti.at(host,0,0); check(liveBursts.getInt(null)==before,"reduce motion must stop bursts"); Settings.set(6,false);
        View generated=new View(new android.content.Context()); generated.testId=0x1a;
        new ClickHaptic().sendAccessibilityEvent(generated,1); // a runtime id is not a play button and must not throw
        generated.testId=0x7f0b0c19; boolean burstReached=false;
        try { new ClickHaptic().sendAccessibilityEvent(generated,1); } catch(NullPointerException noResources) { burstReached=true; }
        check(burstReached,"the mini player button must reach the burst");
        int[] clicks={0}; kotlin.jvm.functions.Function0 wrapped=Flourish.pressed(()->{clicks[0]++;return "done";},1);
        check("done".equals(wrapped.invoke())&&clicks[0]==1,"a play button's click must still run, once");
        java.nio.file.Path spatchSrc=java.nio.file.Path.of(System.getProperty("fonts")).getParent().resolve("dex/src/spatch/player/ScallopShape.java");
        check(!new String(java.nio.file.Files.readAllBytes(spatchSrc)).contains("Flourish."),"a play state change alone must never burst: paused, tapping a song or skipping starts playback too");
        System.out.println("PASS play button burst");

        // Library and playlists: rows arrive in turn and end exactly in place; the ring meets long press.
        check(Flourish.rise(0,0)[0]==0 && Flourish.rise(0,0)[1]==1,"a row starts hidden and low");
        check(Flourish.rise(5000,9)[0]==1 && Flourish.rise(5000,9)[1]==0,"every row ends in place");
        check(Flourish.rise(100,0)[0]>Flourish.rise(100,3)[0],"later rows wait their turn");
        check(Flourish.holdProgress(0)==0 && Flourish.holdProgress(400)==1 && Flourish.holdProgress(5000)==1,"ring fills by long press");
        Field armedNow=Flourish.class.getDeclaredField("armed"), tapNow=Flourish.class.getDeclaredField("tapAt"); armedNow.setAccessible(true); tapNow.setAccessible(true);
        java.lang.reflect.Method sawRows=Flourish.class.getDeclaredMethod("saw",int.class); sawRows.setAccessible(true);
        Flourish.tab(2); check(armedNow.getBoolean(null),"opening Library rises"); armedNow.setBoolean(null,false);
        tapNow.setLong(null,android.os.SystemClock.uptimeMillis()-60000);
        sawRows.invoke(null,Flourish.PLAYLIST); sawRows.invoke(null,Flourish.LIBRARY);
        check(!armedNow.getBoolean(null),"rows composing without a tap must not hide the page");
        tapNow.setLong(null,android.os.SystemClock.uptimeMillis());
        sawRows.invoke(null,Flourish.PLAYLIST); check(armedNow.getBoolean(null),"a tap that opens a playlist rises");
        Flourish.tab(0); check(!armedNow.getBoolean(null),"leaving for another tab must not leave a rise waiting");
        System.out.println("PASS row cascade and hold ring");
    }
}
''')
    subprocess.run([str(JAVA / 'javac'), '-d', str(out / 'classes'), *sources, str(harness)], check=True)
    subprocess.run([str(JAVA / 'java'), '-Dfonts=' + str(ROOT / 'theme/fonts'), '-cp', str(out / 'classes'), 'spatch.PlayerRuntimeTest'], check=True)
