#!/usr/bin/env python3
"""Run helper behavior on JVM doubles; does not replace Android/device verification."""
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')) / 'bin'
RESOURCE = re.compile(r'(?<!\w)R_(?:anim|array|attr|bool|color|dimen|drawable|font|id|integer|layout|string|style)_\w+')
stand_in = {}


def fill(text):
    """The patcher swaps R_<type>_<name> for the build's resource ID; here each name gets a stand-in, the same one everywhere."""
    return RESOURCE.sub(lambda m: f'0x{stand_in.setdefault(m.group(), 0x7f700000 + len(stand_in)):08x}', text)


with tempfile.TemporaryDirectory(prefix='spatch-player-test-') as tmp:
    out = Path(tmp)
    sources = []
    for source in (ROOT / 'theme/dex/stubs').rglob('*.java'):
        text = source.read_text()
        # Turn compile-only graphics/platform methods into inert JVM doubles.
        text = re.sub(r'((?:public|protected) (?:static |final )*([\w.]+) \w+\([^)]*\)) \{ throw new RuntimeException\(\); \}',
                      lambda m: m[1] + ' {' + {'void': '', 'int': 'return 0;', 'float': 'return 0;', 'long': 'return 10000;', 'boolean': 'return false;'}.get(m[2], 'return null;') + '}', text)
        text = text.replace('{ throw new RuntimeException(); }', '{}')
        if source.name == 'ViewConfiguration.java':
            text=text.replace('return null;', 'return new ViewConfiguration();')
        if source.name == 'View.java':
            text = text.replace('public interface OnClickListener', 'public static View root; public int screenX; public int testHeight=2000; public int testWidth=1000; public android.content.Context testContext; public Runnable testPosted; public static long nextDraw; public interface OnClickListener')
            text = text.replace('public static View root;', 'private boolean enabled=true,pressed,hovered; public static View root;')
            text = text.replace('setEnabled(boolean enabled) {}', 'setEnabled(boolean value) {enabled=value;}').replace('isEnabled() { return true; }', 'isEnabled() { return enabled; }')
            text = text.replace('setPressed(boolean pressed) {}', 'setPressed(boolean value) {pressed=value;}').replace('isPressed() { return false; }', 'isPressed() { return pressed; }')
            text = text.replace('setHovered(boolean hovered) {}', 'setHovered(boolean value) {hovered=value;}').replace('isHovered() { return false; }', 'isHovered() { return hovered; }')
            text = text.replace('public int getId() { return 0; }', 'public int testId; public int getId() { return testId; }')
            text = text.replace('getRootView() { return null; }', 'getRootView() { if (root != null) return root; View cur = this; while (cur.getParent() instanceof View) { cur = (View) cur.getParent(); } return cur; }')
            text = text.replace('getWidth() { return 0; }', 'getWidth() { return testWidth; }').replace('getHeight() { return 0; }', 'getHeight() { return testHeight; }')
            text = text.replace('getContext() { return null; }', 'getContext() { return testContext == null ? new android.content.Context() : testContext; }')
            text = text.replace('getLocationOnScreen(int[] out) {}', 'getLocationOnScreen(int[] out) {out[0]=screenX;}')
            text = text.replace('public void setSelected(boolean selected) {}', 'private boolean selected; public void setSelected(boolean value) {selected=value;}').replace('isSelected() { return false; }', 'isSelected() { return selected; }')
            text = text.replace('postInvalidateDelayed(long delay) {}', 'postInvalidateDelayed(long delay) { nextDraw = delay; }')
            text = text.replace('public void postInvalidateOnAnimation() {}', 'public void postInvalidateOnAnimation() { nextDraw = 16; }') # the next display frame
            text = text.replace('public void setAlpha(float a) {}', 'public float testAlpha=1; public void setAlpha(float a) {testAlpha=a;}')
            text = text.replace('public void setScaleX(float s) {}', 'public float testScale=1; public void setScaleX(float s) {testScale=s;}')
            text = text.replace('public void setOnClickListener(OnClickListener l) {}', 'private OnClickListener click; public void setOnClickListener(OnClickListener l) {click=l;}')
            text = text.replace('public boolean performClick() { return false; }', 'public boolean performClick() { if (click == null) return false; click.onClick(this); return true; }')
            text = text.replace('public boolean post(Runnable r) { return false; }', 'public boolean post(Runnable r) { testPosted=r; return true; }')
        if source.name == 'TextView.java':
            text = text.replace('public CharSequence getText() { return null; }', 'private CharSequence shown; public CharSequence getText() { return shown; }').replace('public void setText(CharSequence t) {}', 'public void setText(CharSequence t) {shown=t;}')
        if source.name == 'Paint.java':
            text = text.replace('public class Paint {', 'public class Paint { private int testColor=0xff000000; private Style testStyle=Style.FILL;')
            text = text.replace('getAlpha() {return 0;}', 'getAlpha() {return testColor>>>24;}').replace('setAlpha(int a) {}', 'setAlpha(int a) {testColor=(testColor&0xffffff)|(a<<24);}')
            text = text.replace('getColor() {return 0;}', 'getColor() {return testColor;}').replace('setColor(int c) {}', 'setColor(int c) {testColor=c;}')
            text = text.replace('getStyle() { return Style.FILL; }', 'getStyle() { return testStyle; }').replace('setStyle(Style s) {}', 'setStyle(Style s) {testStyle=s;}')
        if source.name == 'SystemClock.java':
            text = text.replace('public final class SystemClock {', 'public final class SystemClock { public static long now=10000;')
            text = text.replace('uptimeMillis() {return 10000;}', 'uptimeMillis() {return now;}')
        if source.name == 'MotionEvent.java':
            text = '''package android.view; public final class MotionEvent { public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_HOVER_ENTER=9,ACTION_HOVER_MOVE=7,ACTION_HOVER_EXIT=10; private int action; private float x,y; public int getActionMasked(){return action;} public float getX(){return x;} public float getY(){return y;} public float getRawX(){return x;} public float getRawY(){return y;} public long getEventTime(){return android.os.SystemClock.uptimeMillis();} public void setAction(int a){action=a;} public void offsetLocation(float dx,float dy){} public static MotionEvent obtain(MotionEvent e){return obtain(0,0,e.action,e.x,e.y,0);} public static MotionEvent obtain(long d,long t,int a,float x,float y,int m){MotionEvent e=new MotionEvent();e.action=a;e.x=x;e.y=y;return e;} public void recycle(){} }'''
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
            text=text.replace('public class SeekBar extends android.view.View {', 'public class SeekBar extends android.view.View { private int progress=50,max=100; public OnSeekBarChangeListener testListener;')
            text=text.replace('getProgress() { return 0; }', 'getProgress() { return progress; }').replace('getMax() { return 0; }', 'getMax() { return max; }')
            text=text.replace('setMax(int max) {}', 'setMax(int value) {max=value;}')
            text=text.replace('setProgress(int p) {}', 'setProgress(int p) {int next=Math.max(0,Math.min(max,p));if(next==progress)return;progress=next;if(testListener!=null)testListener.onProgressChanged(this,progress,false);}')
            text=text.replace('setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {}', 'setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {testListener=listener;}')
        if source.name == 'Resources.java':
            text = text.replace('getDisplayMetrics(){return null;}', 'getDisplayMetrics(){return new android.util.DisplayMetrics();}')
            text = text.replace('getConfiguration(){return null;}', 'getConfiguration(){return new android.content.res.Configuration();}')
        if source.name == 'ViewGroup.java':
            text = text.replace('public void removeAllViews() {}', 'public void removeAllViews() {while(getChildCount()>0)removeView(getChildAt(0));}')
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
            text = text.replace('public interface Callback', 'public long nextFrame; public interface Callback')
            text = text.replace('scheduleSelf(Runnable what, long when) {}', 'scheduleSelf(Runnable what, long when) {nextFrame=when;}')
            text = text.replace('public interface Callback', 'private final android.graphics.Rect bounds = new android.graphics.Rect(); public interface Callback')
            text = text.replace('setBounds(int l,int t,int r,int b){}', 'setBounds(int l,int t,int r,int b){bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;}')
            text = text.replace('setBounds(android.graphics.Rect r){}', 'setBounds(android.graphics.Rect r){setBounds(r.left,r.top,r.right,r.bottom);}')
            text = text.replace('setState(int[] states){return false;}', 'setState(int[] states){return onStateChange(states);}')
            text = text.replace('getBounds() {return null;}', 'getBounds() {return bounds;}')
        target = out / source.name
        target.write_text(fill(text))
        sources.append(str(target))
    for source in (ROOT / 'theme/dex/src/spatch').rglob('*.java'):
        if source.name == 'Palette.java':
            rows = re.findall(r'theme\(\s*"[^"]+",\s*\[([^]]+)\]', (ROOT / 'src/theme.rs').read_text())
            palette = ','.join('{'+','.join(re.findall(r'0x[0-9a-f]+', row))+'}' for row in rows)
            assert len(rows) == 18
            light_source=(ROOT / 'src/theme.rs').read_text().split('pub const LIGHT:')[1].split('const fn theme')[0]
            light_rows=re.findall(r'\[\s*(0x[0-9a-f]+[^]]*)\]',light_source)
            light_palette=','.join('{'+','.join(re.findall(r'0x[0-9a-f]+',row))+'}' for row in light_rows)
            assert len(light_rows)==18
            text = source.read_text().replace('@PALETTES@',palette).replace('@LIGHT_PALETTES@',light_palette).replace('@THEME@','lavender')
        else:
            text = source.read_text()
        target = out / 'src' / source.relative_to(ROOT / 'theme/dex/src')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(fill(text))
        sources.append(str(target))
    harness = out / 'PlayerRuntimeTest.java'
    harness.write_text(fill(r'''
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
        java.util.Map<String,java.util.Set<String>> sets=new java.util.HashMap<>();
        public String getString(String k,String d){return data.getOrDefault(k,d);}
        public boolean getBoolean(String k,boolean d){return booleans.getOrDefault(k,d);}
        public Editor edit(){return this;}
        public Editor putString(String k,String v){data.put(k,v);return this;}
        public Editor remove(String k){data.remove(k);booleans.remove(k);sets.remove(k);return this;}
        public Editor putBoolean(String k,boolean v){booleans.put(k,v);return this;}
        public java.util.Set<String> getStringSet(String k,java.util.Set<String> d){return sets.getOrDefault(k,d);}
        public Editor putStringSet(String k,java.util.Set<String> v){sets.put(k,new java.util.HashSet<String>(v));return this;}
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
    static class TracePath extends Path {
        final java.util.ArrayList<float[]> points=new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> starts=new java.util.ArrayList<>();
        int closes;
        public void reset(){points.clear();starts.clear();closes=0;}
        public void moveTo(float x,float y){starts.add(points.size());points.add(new float[]{x,y});}
        public void lineTo(float x,float y){points.add(new float[]{x,y});}
        public void quadTo(float a,float b,float x,float y){points.add(new float[]{x,y});}
        public void close(){closes++;}
        float area(int start,int end){float sum=0;for(int i=start;i<end;i++){float[] a=points.get(i),b=points.get(i+1==end?start:i+1);sum+=a[0]*b[1]-b[0]*a[1];}return sum/2;}
    }
    static class SplashRoot extends View {
        final java.util.ArrayList<Drawable> layers=new java.util.ArrayList<>();
        final android.view.ViewOverlay overlay=new android.view.ViewOverlay(){public void add(Drawable d){layers.add(d);}public void remove(Drawable d){layers.remove(d);}};
        Runnable pending; boolean focus=true;
        SplashRoot(){super(new android.content.Context());}
        public android.content.res.Resources getResources(){return new android.content.res.Resources();}
        public View getRootView(){return this;}
        public android.view.ViewOverlay getOverlay(){return overlay;}
        public boolean hasWindowFocus(){return focus;}
        public boolean postDelayed(Runnable r,long ms){pending=r;return true;}
        public boolean removeCallbacks(Runnable r){if(pending==r)pending=null;return true;}
    }
    static class LyricSlot extends ViewGroup {
        ViewGroup.LayoutParams params=new ViewGroup.LayoutParams(-1,-2);
        int visibility=View.VISIBLE,accessibility=2;boolean clipped;
        LyricSlot(int height){super(new android.content.Context(),null);params.height=height;}
        public ViewGroup.LayoutParams getLayoutParams(){return params;}
        public void setLayoutParams(ViewGroup.LayoutParams value){params=value;}
        public int getVisibility(){return visibility;}
        public void setVisibility(int value){visibility=value;}
        public int getImportantForAccessibility(){return accessibility;}
        public void setImportantForAccessibility(int value){accessibility=value;}
        public boolean getClipChildren(){return clipped;}
        public void setClipChildren(boolean value){clipped=value;}
        public boolean post(Runnable value){value.run();return true;}
    }
    static class OrientationActivity extends android.app.Activity implements p.S_OrientationHost {
        int requested=1, requests;
        boolean changing;
        public int a(){return requested;}
        public boolean isChangingConfigurations(){return changing;}
        public void r(int value){
            if(value!=-100) Immersive.orientation=value;
            requested=Immersive.landscapeRequested?13:Immersive.orientation;
            requests++;
        }
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
        p.S_DrawerRowProvider settingsRow = new p.S_DrawerRowProvider((byte) 1);
        p.S_DrawerRowProvider otherRow = new p.S_DrawerRowProvider((byte) 0);
        rows.add(otherRow);
        rows.add(settingsRow);

        java.util.List modified = Settings.rows(rows);
        check(modified.size() == 3, "drawer must insert Spatch row right before Settings and privacy");
        check(modified.get(1) instanceof Settings, "inserted row must be Settings instance");
        Settings spatchRow = (Settings) modified.get(1);

        // Check row presentation
        p.S_KotlinFlowOf rowVtz = (p.S_KotlinFlowOf) spatchRow.a();
        p.S_DrawerRowWrapper rowFg = (p.S_DrawerRowWrapper) rowVtz.b;
        p.S_DrawerRowContent rowCg = (p.S_DrawerRowContent) rowFg.a;
        check(rowCg.c.equals("spotify:spatch:settings"), "drawer URI must be spotify:spatch:settings");
        check(rowCg.b.intValue() == R_string_mixing_edit_page_customize_button, "drawer row title ID must match R_string_mixing_edit_page_customize_button");

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
        byte[] dense=new byte[1024],denser=new byte[1024];
        java.util.Arrays.fill(dense,(byte)64);java.util.Arrays.fill(denser,(byte)100);
        float[] loudFrame=AudioSpectrum.bands(dense,48000,24),louderFrame=AudioSpectrum.bands(denser,48000,24);
        boolean loudDynamics=false;
        for(int i=0;i<loudFrame.length;i++) if(loudFrame[i]>1 && louderFrame[i]>loudFrame[i]+.02f) loudDynamics=true;
        check(loudDynamics,"loud multi-tone audio must retain energy changes above the old clipping point");
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
        Atmosphere heroBackdrop=(Atmosphere)field(overlay,"atmosphere");
        check(Math.abs((Float)field(heroBackdrop,"artX")-((Float)field(overlay,"drawnCx")+(Float)field(overlay,"artDrag")))<.001 && Math.abs((Float)field(heroBackdrop,"artY")-(Float)field(overlay,"drawnCy"))<.001 && Math.abs((Float)field(heroBackdrop,"artRadius")-(Float)field(overlay,"drawnSize")/2)<.001,"immersive shockwaves must use the displayed artwork geometry");
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
        float midLeft = Spectrum.at(typical, 0.479f, true);
        float midRight = Spectrum.at(typical, 0.521f, true);
        check(Math.abs(midLeft - midRight) > 0.01f, "middle visualizer bars must be distinct: " + midLeft + " vs " + midRight);
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
        for(int i=43;i<Settings.KEYS.length;i++) if(i!=Settings.WIDGET_WALLPAPER&&i!=Settings.HIDE_PREMIUM_TAB&&i!=Settings.HIDE_ACCOUNT_SWITCHER&&i!=Settings.HIDE_PARENTAL_CONTROLS&&i!=Settings.HIDE_PLAYER_LYRICS&&i!=Settings.IMM_LYRICS) check(Settings.DEFAULTS[i],"a new feature must start on: "+Settings.KEYS[i]);
        check(!Settings.DEFAULTS[Settings.IMM_LYRICS],"immersive lyrics are opt-in");
        check(!Settings.DEFAULTS[Settings.HIDE_PLAYER_LYRICS],"inline lyrics must remain visible until the user opts out");
        check(!Settings.DEFAULTS[Settings.HIDE_ACCOUNT_SWITCHER]&&!Settings.DEFAULTS[Settings.HIDE_PARENTAL_CONTROLS],"hiding the account switcher and Parental controls is opt-in");
        check(!Settings.DEFAULTS[Settings.HIDE_PREMIUM_TAB],"hiding the Premium tab is opt-in: it removes part of Spotify's own interface");
        p.S_NavTab premiumTab=new p.S_NavTab();premiumTab.b=3;premiumTab.c=new p.S_PageId();premiumTab.c.a="spotify:upsell:premium_in_app_destination";
        p.S_NavTab createTab=new p.S_NavTab();createTab.b=4;createTab.c=new p.S_PageId();createTab.c.a="spotify:create-menu";
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
        p.S_LibraryContentType[] types={p.S_LibraryContentType.b,p.S_LibraryContentType.Y,p.S_LibraryContentType.a,p.S_LibraryContentType.g,new p.S_LibraryContentType()};
        for(p.S_LibraryContentType t:types){p.S_LibraryItem d=new p.S_LibraryItem(); d.e=t; lib.add(d);}
        check(Content.library(lib)==lib,"filters off must pass the list through");
        Settings.set(13,true);
        check(Content.library(lib).size()==3,"podcasts hidden: shows and new episodes go");
        check(!Content.blocked("spotify:album:x","mixed")&&Content.blocked("spotify:show:x","mixed"),"mixed shelf keeps music");
        check(!Content.blocked("spotify:show:x","popular-audiobooks"),"podcast filter must leave audiobook shelves");
        java.util.ArrayList chips=new java.util.ArrayList(java.util.Arrays.asList(new p.S_LibraryPodcastChip(),new p.S_LibraryAudiobookChip(),new p.S_LibraryPodcastChipB(),new p.S_LibraryAudiobookChipB(),"music"));
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
        NavIndicator cancelledMenu=new NavIndicator(new android.content.Context(),null);
        Field menuNav=NavIndicator.class.getDeclaredField("nav");menuNav.setAccessible(true);menuNav.set(cancelledMenu,new View(new android.content.Context()));
        Field menuHeld=NavIndicator.class.getDeclaredField("homeHeld");menuHeld.setAccessible(true);menuHeld.setBoolean(cancelledMenu,true);
        Field hoveredOption=NavIndicator.class.getDeclaredField("menuHover");hoveredOption.setAccessible(true);hoveredOption.setInt(cancelledMenu,2);
        check(cancelledMenu.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,20,20,0)) && !menuHeld.getBoolean(cancelledMenu) && hoveredOption.getInt(cancelledMenu)==-1,"a cancelled held gesture clears even a highlighted action without opening settings");
        java.lang.reflect.Method menuPillMove=NavIndicator.class.getDeclaredMethod("moveMenuPill",int.class);menuPillMove.setAccessible(true);
        Settings.set(6,true);menuPillMove.invoke(cancelledMenu,2);
        check((Float)field(cancelledMenu,"menuSlideFrom")==2f && (Float)field(cancelledMenu,"menuPillAlpha")==1f,"reduced motion snaps the hover pill to the selected menu row");
        menuPillMove.invoke(cancelledMenu,-1);
        check((Float)field(cancelledMenu,"menuPillAlpha")==0f,"leaving the menu clears the hover pill under reduced motion");Settings.set(6,false);
        check(NavIndicator.menuOption(20,20,260,52)==0 && NavIndicator.menuOption(20,70,260,52)==1 && NavIndicator.menuOption(20,130,260,52)==2,"dragging through the Home menu selects each row");
        check(NavIndicator.menuOption(-1,20,260,52)==-1 && NavIndicator.menuOption(260,20,260,52)==-1 && NavIndicator.menuOption(20,-1,260,52)==-1 && NavIndicator.menuOption(20,156,260,52)==-1,"releasing outside the Home menu cancels selection");
        java.lang.reflect.Method openHomeMenu=NavIndicator.class.getDeclaredMethod("homeMenu");openHomeMenu.setAccessible(true);
        android.widget.FrameLayout mockRoot=new android.widget.FrameLayout(new android.content.Context());
        mockRoot.testWidth=1080; mockRoot.testHeight=2400;
        android.view.View.root=mockRoot;
        openHomeMenu.invoke(cancelledMenu);
        float openedWidth=(Float)field(cancelledMenu,"menuWidth");
        check(openedWidth < cancelledMenu.getWidth() && openedWidth <= 224f, "home menu width is compact and detached rather than spanning the navigation bar");
        android.view.View.root=null;
        p.S_SaveButtonState off=new p.S_SaveButtonState(),on=new p.S_SaveButtonState(); off.a=p.S_SaveButtonStage.a; on.a=p.S_SaveButtonStage.b;
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
        check(Fonts.weight(R_font_spotify_mix_ui_regular) == 400 && Fonts.weight(R_font_spotify_mix_ui_bold) == 700 && Fonts.weight(R_font_spotify_mix_ui_title_bold) == 700 && Fonts.weight(R_font_spotify_mix_ui_title_extrabold) == 800, "Spotify roles keep their weights");
        check(Fonts.weight(R_font_spoticon) == 0 && Fonts.weight(0x7f09001c) == 0, "the icon font is never replaced");
        check(Fonts.resource(R_font_spotify_mix_ui_regular) == null, "Default leaves Spotify's fonts alone");
        check(Fonts.variable(800) == R_font_spotify_mix, "Default keeps the variable font");
        System.out.println("PASS fonts");

        // Side menu: hidden providers leave, Settings and privacy stays and the Spatch row sits above it.
        java.util.ArrayList menu = new java.util.ArrayList();
        p.S_DrawerRowProvider recents = new p.S_DrawerRowProvider((byte) 0), settings = new p.S_DrawerRowProvider((byte) 1);
        p.S_MessagesRowProvider chats = new p.S_MessagesRowProvider((byte) 0), updates = new p.S_MessagesRowProvider((byte) 1);
        p.S_ListeningStatsRowProvider stats = new p.S_ListeningStatsRowProvider();
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
        android.widget.SeekBar[] strengthControls=(android.widget.SeekBar[])field(full,"strengthSliders");
        for(int i=0;i<strengthControls.length;i++) {
            check(strengthControls[i]!=null,"no slider shows "+Settings.STRENGTH_KEYS[i]);
            strengthControls[i].testListener.onProgressChanged(strengthControls[i],Settings.STRENGTH_MAX[i]-Settings.STRENGTH_MIN[i],true);
            check(Settings.strength(i)==Settings.STRENGTH_MAX[i],"dragging the slider must update "+Settings.STRENGTH_KEYS[i]);
        }
        System.out.println("PASS every setting and choice is on a page");
        for(int i=0;i<Settings.KEYS.length;i++) Settings.set(i,!Settings.DEFAULTS[i]);
        for(int i=0;i<Settings.CHOICES.length;i++) Settings.setChoice(i,Settings.CHOICES[i][Settings.CHOICES[i].length-1]);
        java.util.ArrayList<TextView> resets=new java.util.ArrayList<TextView>();
        collect(full,"Reset this page",resets);
        check(resets.size()>10,"pages must offer scoped resets");
        for(TextView reset:resets) check(reset.performClick(),"reset must have a handler");
        for(int i=0;i<Settings.KEYS.length;i++) check(Settings.get(i)==Settings.DEFAULTS[i],"no page resets "+Settings.KEYS[i]);
        for(int i=0;i<Settings.CHOICES.length;i++) check(Settings.choice(i).equals(Settings.CHOICES[i][0]),"no page resets "+Settings.CHOICE_KEYS[i]);
        for(int i=0;i<strengthControls.length;i++) check(Settings.strength(i)==Settings.STRENGTH_DEFAULTS[i] && strengthControls[i].getProgress()==Settings.STRENGTH_DEFAULTS[i]-Settings.STRENGTH_MIN[i],"page reset must restore slider "+Settings.STRENGTH_KEYS[i]);
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
        check(Settings.importText(saved)==Settings.KEYS.length+Settings.CHOICES.length+Settings.STRENGTH_KEYS.length,"export must round trip");
        check(Settings.get(Settings.ART_TILT)&&Settings.choice(Settings.ART_SHAPE).equals("circle"),"import must restore");
        check(Settings.importText("nonsense\nart_shape=hexagon\nart_tilt=maybe\n=true")==0 && Settings.choice(Settings.ART_SHAPE).equals("circle"),"bad text must change nothing");
        check(Settings.importText("tilt_strength=21\nripple_size=oops\nhide_distance=0\nripple_size=9999999999999")==0,"invalid strengths must not be imported");
        for(int i=0;i<Settings.STRENGTH_KEYS.length;i++) {
            Settings.setStrength(i,-1);check(Settings.strength(i)==Settings.STRENGTH_MIN[i],"strength minimum");
            Settings.setStrength(i,999);check(Settings.strength(i)==Settings.STRENGTH_MAX[i],"strength maximum");
        }
        Object oldPrefs=spatchPrefsField.get(null);
        Prefs presetPrefs=new Prefs();spatchPrefsField.set(null,presetPrefs);
        Settings.resetAll();
        check(!Settings.savePreset("  ")&&!Settings.savePreset("bad\nname")&&!Settings.savePreset("x".repeat(41)),"invalid preset names must not be saved");
        Settings.set(6,true);Settings.set(Settings.ART_TILT,false);Settings.setChoice(Settings.ART_SHAPE,"soft");Settings.setStrength(Settings.TILT_STRENGTH,18);
        check(Settings.savePreset("  Evening  ")&&Settings.presets()[0].equals("Evening"),"preset names must be trimmed and stored");
        Settings.resetAll();
        check(Settings.applyPreset("Evening")&&!Settings.get(Settings.ART_TILT)&&Settings.choice(Settings.ART_SHAPE).equals("soft")&&Settings.strength(Settings.TILT_STRENGTH)==18,"presets survive reset and restore switches, choices and strengths");
        Settings.setStrength(Settings.TILT_STRENGTH,7);Settings.savePreset("Evening");
        check(Settings.presets().length==1,"updating a preset must not duplicate its name");
        Settings.savePreset("names");
        check(Settings.presets().length==2 && presetPrefs.data.get("preset_value:names")!=null,"a preset name must not collide with the stored name list");
        // Reload from the same preferences, as a new app process would.
        spatchPrefsField.set(null,null);
        Settings.load(new android.content.Context(){public android.content.SharedPreferences getSharedPreferences(String n,int mode){return presetPrefs;}});
        check(Settings.strength(Settings.TILT_STRENGTH)==7 && Settings.presets().length==2,"strengths and presets must persist across reloads");
        Settings.resetAll();
        SpatchSettings presetPage=new SpatchSettings(new android.content.Context());
        android.widget.LinearLayout presetRows=new android.widget.LinearLayout(new android.content.Context());
        Method renderPresets=SpatchSettings.class.getDeclaredMethod("renderPresets",android.widget.LinearLayout.class);renderPresets.setAccessible(true);renderPresets.invoke(presetPage,presetRows);
        check(presetRows.getChildCount()==3 && presetRows.getChildAt(1).performClick(),"saved presets must be reachable from the UI");
        android.app.AlertDialog.Builder.lastPositive.onClick(null,-1);
        android.widget.SeekBar[] restoredControls=(android.widget.SeekBar[])field(presetPage,"strengthSliders");
        check(Settings.strength(Settings.TILT_STRENGTH)==7 && restoredControls[0].getProgress()==7,"applying a preset must refresh its sliders");
        presetRows.getChildAt(1).performClick();android.app.AlertDialog.Builder.lastNegative.onClick(null,-2);
        check(Settings.presets().length==1 && !Settings.applyPreset("Evening") && presetRows.getChildCount()==2,"deleting a preset must remove its snapshot and row");
        Settings.deletePreset("names");Settings.deletePreset("missing");
        check(Settings.presets().length==0 && !Settings.applyPreset("missing"),"missing presets leave settings alone");
        Settings.resetAll();spatchPrefsField.set(null,oldPrefs);
        System.out.println("PASS named presets, persistence and strength sliders");
        check(RowCards.fits(56,1000,1000)&&!RowCards.fits(39,1000,1000)&&!RowCards.fits(216,1000,1000)&&!RowCards.fits(56,400,1000),"cards fit list rows, not headings, shelves or tiles");
        check(Surfaces.chroma(Surfaces.soften(0xffe91e8c))<Surfaces.chroma(0xffe91e8c)&&(Surfaces.soften(0x80e91e8c)>>>24)==0x80,"softening quiets a colour and keeps its opacity");
        check(!MiniRing.moved(5000,5005,60)&&MiniRing.moved(5000,5020,60)&&MiniRing.moved(5000,4999,60)&&MiniRing.moved(0,10000,60),"the mini ring redraws once its arc visibly moves");
        float[] silentBands=new float[Spectrum.bands.length], loudBands=new float[Spectrum.bands.length]; java.util.Arrays.fill(loudBands,1f);
        check(Wave.swell(silentBands,.5f,1f,"mix","normal")==Wave.swell(silentBands,.5f,1f)&&Wave.swell(loudBands,.5f,1f,"mix","normal")==Wave.swell(loudBands,.5f,1f),"the default music settings keep the old wave");
        check(Wave.swell(silentBands,.5f,1f,"beat","subtle")>Wave.swell(silentBands,.5f,1f,"beat","normal")&&Wave.swell(silentBands,.5f,1f,"beat","normal")>Wave.swell(silentBands,.5f,1f,"beat","strong"),"subtle swings least and strong most");
        check(Wave.swell(loudBands,.5f,1f,"melody","strong")<=1f&&Wave.beat(loudBands,1f)<=1f&&Wave.beat(silentBands,1f)==0f&&Wave.push("strong")>Wave.push("subtle"),"music never pushes the wave past its bounds");
        float[] peakBands=new float[Spectrum.BANDS];java.util.Arrays.fill(peakBands,Spectrum.CEILING);
        for(String follows:new String[]{"mix","beat","melody"}) for(String strength:new String[]{"normal","subtle","strong"}) {
            float loudWave=Wave.swell(loudBands,.5f,1f,follows,strength),peakWave=Wave.swell(peakBands,.5f,1f,follows,strength);
            check(peakWave>loudWave+.02f && peakWave<=1f,"loud wave peaks must keep moving: "+follows+" "+strength);
        }
        check(Wave.beat(peakBands,1f)>Wave.beat(loudBands,1f),"beat response must use the spectrum's full range");
        check(Wave.thickness(6,10,.9f,"strong")<Wave.thickness(6,10,1f,"strong") && Wave.thickness(6,10,1f,"strong")<=8,"held wave thickness must not flatten at loud peaks");
        System.out.println("PASS loud wave dynamics and bounded thickness");
        // Contours: the normal is unit length, points outward, and is perpendicular to the actual boundary.
        float[] contour=new float[4],priorPoint=new float[4],nextPoint=new float[4];
        for(String shape:new String[]{"circle","squircle","scallop","soft"}) for(int angle=0;angle<360;angle++) {
            double radians=Math.toRadians(angle);
            ArtContour.point(shape,radians,.26f,contour);ArtContour.point(shape,radians-.0001,.26f,priorPoint);ArtContour.point(shape,radians+.0001,.26f,nextPoint);
            check(Math.abs(Math.hypot(contour[2],contour[3])-1)<.0001,"unit contour normal "+shape);
            check(contour[0]*contour[2]+contour[1]*contour[3]>0,"outward contour normal "+shape);
            float tx=nextPoint[0]-priorPoint[0],ty=nextPoint[1]-priorPoint[1];
            check(Math.abs((tx*contour[2]+ty*contour[3])/Math.max(.00001,Math.hypot(tx,ty)))<.02,"normal must follow the tangent "+shape+" "+angle);
            check(Math.abs(contour[0])<=1.001 && Math.abs(contour[1])<=1.001,"art stays in its square "+shape);
        }
        TracePath radialPath=new TracePath();Paint audioPaint=new Paint(3);audioPaint.setAlpha(210);
        Spectrum.drawArtwork(new Canvas(),audioPaint,radialPath,loudBands,"bars","scallop",.26f,0,0,100,5,10,1);
        for(int i=0;i<72;i++) {
            ArtContour.point("scallop",2*Math.PI*i/72-Math.PI/2,.26f,contour);
            float[] rayStart=radialPath.points.get(i*2),rayEnd=radialPath.points.get(i*2+1);float dx=rayEnd[0]-rayStart[0],dy=rayEnd[1]-rayStart[1];
            check(Math.abs(dx*contour[3]-dy*contour[2])<.001 && dx*contour[2]+dy*contour[3]>0,"bars extrude along the local normal");
        }
        Spectrum.drawArtwork(new Canvas(),audioPaint,radialPath,loudBands,"ribbon","soft",.26f,0,0,100,5,10,1);
        check(radialPath.closes==2 && radialPath.starts.size()==2,"ribbon must have a closed inner and outer edge");
        int innerStart=radialPath.starts.get(1);float outerArea=radialPath.area(0,innerStart),innerArea=radialPath.area(innerStart,radialPath.points.size());
        check(outerArea*innerArea<0 && Math.abs(outerArea)>Math.abs(innerArea),"the ribbon must leave a hole for the artwork");
        System.out.println("PASS shape-conforming rays and ribbon winding");

        TracePath scopePath=new TracePath();
        for(boolean loop:new boolean[]{false,true}) for(float energy:new float[]{0,.2f,.7f,1}) for(float phase:new float[]{0,1,5}) {
            AudioScope.trace(scopePath,0,0,200,20,energy,phase,loop);
            for(float[] point:scopePath.points)check(point[0]>=-.001 && point[0]<=200.001 && point[1]>=0 && point[1]<=20,"scope stays within its track");
            if(!loop){float[] first=scopePath.points.get(0),last=scopePath.points.get(scopePath.points.size()-1);check(first[0]==0 && first[1]==10 && last[0]==200 && last[1]==10,"scope lands exactly at both track ends");}
            if(energy==0)for(float[] point:scopePath.points)check(point[1]==10,"silent scope collapses to a line");
            check(scopePath.closes==(loop&&energy>0?1:0),"only the live visualizer loop closes");
        }
        final int[] scopePasses={0};Canvas scopeCanvas=new Canvas(){public void drawPath(Path p,Paint paint){scopePasses[0]++;}};
        audioPaint.setColor(0xff102030);audioPaint.setAlpha(173);audioPaint.setStyle(Paint.Style.STROKE);
        AudioScope.draw(scopeCanvas,audioPaint,scopePath,0,0,200,20,.7f,1,false,1);
        check(scopePasses[0]==2 && audioPaint.getAlpha()==173 && audioPaint.getColor()==0xad102030 && audioPaint.getStyle()==Paint.Style.STROKE,"scope bloom and core must preserve the caller's paint");
        System.out.println("PASS scope endpoints, silence, bounds and phosphor passes");

        Spectrum.reset();Spectrum.trackBass(.1f,1000,true);check(Spectrum.kickAt==0,"starting audio is not a kick");
        Spectrum.trackBass(.9f,1016,true);long firstKick=Spectrum.kickAt;check(firstKick==1016 && Spectrum.kickEnergy>0,"a rising bass transient triggers a kick");
        for(long t=1032;t<1600;t+=16)Spectrum.trackBass(.9f,t,true);
        check(Spectrum.kickAt==firstKick,"sustained loud bass must not keep firing rings");
        for(long t=1600;t<1920;t+=16)Spectrum.trackBass(.05f,t,true);
        Spectrum.trackBass(.9f,1920,true);check(Spectrum.kickAt==1920,"another separated kick can trigger");
        Spectrum.trackBass(.05f,1936,true);Spectrum.trackBass(1,1952,true);check(Spectrum.kickAt==1920,"cooldown blocks rapid retriggers");
        Spectrum.trackBass(.9f,3000,true);check(Spectrum.kickAt==0,"resuming after a gap must not replay a kick");
        Spectrum.trackBass(0,3016,false);check(Spectrum.kickAt==0,"reduced motion or silence clears the kick");
        Atmosphere shockBackdrop=new Atmosphere();shockBackdrop.artwork(130,170,88);Method drawShocks=Atmosphere.class.getDeclaredMethod("drawShockwaves",Canvas.class,float.class,float.class);drawShocks.setAccessible(true);
        Settings.set(Settings.BASS_SHOCKWAVE,true);Spectrum.kickAt=android.os.SystemClock.uptimeMillis()-20;Spectrum.kickEnergy=1;
        check((Boolean)drawShocks.invoke(shockBackdrop,new Canvas(),400f,1000f),"a fresh kick creates an expanding ring");
        drawShocks.invoke(shockBackdrop,new Canvas(),400f,1000f);
        long[] ringTimes=(long[])field(shockBackdrop,"shockAt");check((ringTimes[0]>0?1:0)+(ringTimes[1]>0?1:0)==1,"one shared kick is consumed once per backdrop");
        Settings.set(6,true);check(!(Boolean)drawShocks.invoke(shockBackdrop,new Canvas(),400f,1000f),"reduced motion clears shockwaves");Settings.set(6,false);
        check(Atmosphere.shockProgress(0)==0 && Atmosphere.shockProgress(420)==1 && Atmosphere.shockProgress(900)==1,"rings expire after 420 ms");
        Spectrum.reset();System.out.println("PASS transient kick detection, cooldown and shockwave expiry");

        final int[] artworkOrigin={150,250},surfaceOrigin={20,70};
        View movingArtwork=new View(new android.content.Context()){
            public int getWidth(){return 200;}public int getHeight(){return 200;}
            public void getLocationOnScreen(int[] out){out[0]=artworkOrigin[0];out[1]=artworkOrigin[1];}
        };
        View backdropSurface=new View(new android.content.Context()){
            public void getLocationOnScreen(int[] out){out[0]=surfaceOrigin[0];out[1]=surfaceOrigin[1];}
        };
        final java.util.ArrayList<float[]> shockCircles=new java.util.ArrayList<>();
        Canvas shockCanvas=new Canvas(){public void drawCircle(float x,float y,float r,Paint paint){shockCircles.add(new float[]{x,y,r,paint.getAlpha()});}};
        Settings.set(Settings.BASS_SHOCKWAVE,true);Settings.setStrength(Settings.SHOCK_STRENGTH,100);
        Spectrum.alpha=1;Spectrum.kickAt=android.os.SystemClock.uptimeMillis()-10;Spectrum.kickEnergy=1;
        Atmosphere aligned=new Atmosphere();aligned.follow(movingArtwork,backdropSurface,88);
        drawShocks.invoke(aligned,shockCanvas,400f,1000f);
        float[] firstRing=shockCircles.get(0);check(firstRing[0]==230 && firstRing[1]==280 && firstRing[2]>=88,"rings originate at the measured artwork centre and edge");
        artworkOrigin[1]=180;aligned.follow(movingArtwork,backdropSurface,88);shockCircles.clear();drawShocks.invoke(aligned,shockCanvas,400f,1700f);
        check(shockCircles.get(0)[1]==210,"lyrics-induced layout changes must move the ring centre, independent of backdrop height");
        surfaceOrigin[1]+=35;artworkOrigin[1]+=35;aligned.follow(movingArtwork,backdropSurface,88);shockCircles.clear();drawShocks.invoke(aligned,shockCanvas,400f,1700f);
        check(shockCircles.get(0)[1]==210,"window insets must cancel between artwork and backdrop");
        Settings.setStrength(Settings.SHOCK_STRENGTH,50);shockCircles.clear();drawShocks.invoke(aligned,shockCanvas,400f,1700f);
        check(shockCircles.get(0)[3]<firstRing[3],"shockwave strength controls ring opacity");Settings.setStrength(Settings.SHOCK_STRENGTH,100);
        System.out.println("PASS artwork-centred shockwaves across lyrics layout changes");

        final LyricSlot[] lyricSlot={new LyricSlot(96)};LyricSlot fullLyrics=new LyricSlot(240);
        View lyricRoot=new View(new android.content.Context()){public View findViewById(int id){return id==R_id_lyrics_element?lyricSlot[0]:null;}public boolean post(Runnable task){task.run();return true;}};
        Settings.set(Settings.HIDE_PLAYER_LYRICS,false);PlayerLyrics.bind(lyricRoot);
        Settings.set(Settings.HIDE_PLAYER_LYRICS,true);PlayerLyrics.bind(lyricRoot);PlayerLyrics.bind(lyricRoot);
        check(lyricSlot[0].params.height==0 && lyricSlot[0].clipped && lyricSlot[0].accessibility==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS && lyricSlot[0].visibility==View.VISIBLE,"hiding inline lyrics collapses and clips the slot without overriding Spotify's visibility");
        lyricSlot[0].visibility=View.GONE;Settings.set(Settings.HIDE_PLAYER_LYRICS,false);
        check(lyricSlot[0].params.height==96 && !lyricSlot[0].clipped && lyricSlot[0].accessibility==2 && lyricSlot[0].visibility==View.GONE && fullLyrics.params.height==240,"restore preserves lyric availability, accessibility and full lyrics");
        lyricSlot[0].visibility=View.VISIBLE;Settings.set(Settings.HIDE_PLAYER_LYRICS,true);
        ViewGroup.LayoutParams inheritedParams=lyricSlot[0].params;
        Field currentLyrics=PlayerLyrics.class.getDeclaredField("CURRENT");currentLyrics.setAccessible(true);((java.lang.ref.WeakReference)field(currentLyrics.get(null),"slot")).clear();
        lyricSlot[0]=new LyricSlot(0);lyricSlot[0].params=inheritedParams;PlayerLyrics.bind(lyricRoot);Settings.set(Settings.HIDE_PLAYER_LYRICS,false);
        check(lyricSlot[0].params.height==96,"replacing a stub with shared layout params must retain its native height even after collection");
        Settings.set(Settings.HIDE_PLAYER_LYRICS,true);lyricSlot[0].params.height=111;PlayerLyrics.bind(lyricRoot);Settings.set(Settings.HIDE_PLAYER_LYRICS,false);
        check(lyricSlot[0].params.height==111,"restoring lyrics uses Spotify's latest requested height");
        lyricSlot[0]=null;PlayerLyrics.bind(lyricRoot);Spectrum.reset();
        System.out.println("PASS inline lyrics toggle, replacement and native state restoration");

        // Native lyric results are accepted only for the playing URI and follow timestamps after seeks.
        p.S_LyricTimedLine firstLyric=new p.S_LyricTimedLine();firstLyric.a=1000;firstLyric.b="First line";
        p.S_LyricTimedLine secondLyric=new p.S_LyricTimedLine();secondLyric.a=2500;secondLyric.b="Second line";
        p.S_LyricContent lyricData=new p.S_LyricContent();lyricData.b=2;lyricData.a=java.util.Arrays.asList(firstLyric,secondLyric);
        p.S_LyricLoaded loadedLyrics=new p.S_LyricLoaded();loadedLyrics.b="spotify:track:one";loadedLyrics.a=new p.S_LyricData();loadedLyrics.a.a=lyricData;
        ImmersiveLyrics.model=loadedLyrics;
        ImmersiveLyrics reader=new ImmersiveLyrics();
        final String[] playingUri={"spotify:track:one"};final long[] nativePosition={2500},nativeNow={0};
        com.spotify.player.model.ContextTrack nativeTrack=new com.spotify.player.model.ContextTrack(){public String uri(){return playingUri[0];}};
        ImmersiveLyrics.playback=new com.spotify.player.model.PlayerState(){
            public p.S_PlayerOptional track(){return new p.S_PlayerOptional(){public Object h(){return nativeTrack;}};}
            public p.S_PlayerOptional position(long now){nativeNow[0]=now;return new p.S_PlayerOptional(){public Object h(){return Long.valueOf(nativePosition[0]);}};}
        };
        ImmersiveLyrics.clock=(p.S_PlayerClock)()->123456L;
        check(reader.update(300,18).equals("Second line") && nativeNow[0]==123456L,"lyrics use Spotify's own playback clock without requiring Android 12 media-session support");
        nativePosition[0]=1200;check(reader.update(300,18).equals("First line"),"native seek updates reach the lyric renderer");
        playingUri[0]="spotify:track:two";check(reader.update(300,18).equals("Lyrics unavailable"),"native track identity clears stale lyrics immediately");
        ImmersiveLyrics.playback=null;ImmersiveLyrics.clock=null;

        check(reader.update("spotify:track:one",500,300,18).isEmpty(),"lyrics wait for the first timestamp");
        check(reader.update("spotify:track:one",1200,300,18).equals("First line"),"the playing line follows its timestamp");
        Object layouts=field(reader,"lines");Object cachedLine=((Object[])layouts)[1];
        reader.update("spotify:track:one",1300,300,18);
        check(((Object[])layouts)[1]==cachedLine,"unchanged lyrics reuse their measured text");
        check(reader.update("spotify:track:one",2500,300,18).equals("Second line"),"a line starts at its exact timestamp");
        check(reader.update("spotify:track:one",1000,300,18).equals("First line"),"seeking backwards restores the earlier line");
        reader.update("spotify:track:one",1000,500,18);check((Integer)field(reader,"width")==500,"rotation remeasures wrapped lyrics");
        check(reader.update("spotify:track:two",1000,300,18).equals("Lyrics unavailable"),"a skip must never show the previous track's lyrics");
        ImmersiveLyrics.model=new Object();check(reader.update("spotify:track:one",1000,300,18).equals("Lyrics unavailable"),"loading and unavailable native states clear the line");
        check(ImmersiveLyrics.index(null,1000)==-1,"missing lyrics stay empty");
        lyricData.b=1;check(ImmersiveLyrics.index(lyricData,99999)==0,"unsynced lyrics never fake a timed current line");
        lyricData.b=2;ImmersiveLyrics.model=null;
        System.out.println("PASS immersive lyric timing, seeks, caching and track identity");

        Method immersiveLayout=Immersive.class.getDeclaredMethod("layout",float.class,float.class);immersiveLayout.setAccessible(true);
        Settings.set(Settings.IMM_LYRICS,true);Settings.set(Settings.IMM_LANDSCAPE,true);
        for(float[] viewport:new float[][]{{400,800},{800,400},{640,320},{1200,800},{1000,2000}}) {
            immersiveLayout.invoke(overlay,viewport[0],viewport[1]);
            float artworkRight=(Float)field(overlay,"artX")+(Float)field(overlay,"artSize");
            float controlsLeft=(Float)field(overlay,"contentCx")-(Float)field(overlay,"contentWidth")/2;
            check((Float)field(overlay,"seekR")>(Float)field(overlay,"seekL"),"seek row stays usable in both orientations");
            check((Float)field(overlay,"volY")+24<viewport[1],"volume stays above the bottom edge");
            check((Float)field(overlay,"lyricHeight")>24,"lyrics have their own space");
            if(viewport[0]>viewport[1]) check((Boolean)field(overlay,"landscape")&&artworkRight<controlsLeft,"landscape separates art/lyrics from the controls");
            else check(!(Boolean)field(overlay,"landscape")&&(Float)field(overlay,"lyricY")+(Float)field(overlay,"lyricHeight")<(Float)field(overlay,"titleY"),"portrait lyrics leave metadata clear");
        }
        Immersive wide=new Immersive(page.getContext(),page);wide.testWidth=800;wide.testHeight=400;
        wide.onDraw(new Canvas());
        ImmersiveButton[] wideButtons=(ImmersiveButton[])field(wide,"buttons");wideButtons[4].source=action;
        float wideNext=(Float)field(wide,"contentCx")+(Float)field(wide,"spacing"),wideControlY=(Float)field(wide,"ctrlY");
        int nextBefore=clicks;
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,wideNext,wideControlY,0));
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,wideNext,wideControlY,0));
        check(clicks==nextBefore+1,"landscape taps hit the drawn transport button");
        float lyricTapX=(Float)field(wide,"lyricCx"),lyricTapY=(Float)field(wide,"lyricY")+12;
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,lyricTapX,lyricTapY,0));
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,lyricTapX,lyricTapY,0));
        check(!(Boolean)field(wide,"lyricsExpanded"),"cancel does not expand lyrics");
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,lyricTapX,lyricTapY,0));
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,lyricTapX,lyricTapY,0));
        check((Boolean)field(wide,"lyricsExpanded"),"tapping the lyric region expands nearby lines");
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,lyricTapX,lyricTapY,0));
        wide.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,lyricTapX,lyricTapY,0));
        check(!(Boolean)field(wide,"lyricsExpanded"),"tapping again restores artwork");
        Settings.set(Settings.IMM_LANDSCAPE,false);immersiveLayout.invoke(wide,800f,400f);
        check(!(Boolean)field(wide,"landscape"),"landscape layout can be switched off");
        Settings.resetAll();immersiveLayout.invoke(overlay,1000f,2000f);
        System.out.println("PASS immersive portrait/landscape bounds and lyric/transport hit testing");

        // A configuration detach reopens on the new root; dismissal and ordinary detach do not.
        Field immersiveCurrent=Immersive.class.getDeclaredField("CURRENT");immersiveCurrent.setAccessible(true);
        Field immersiveReopen=Immersive.class.getDeclaredField("reopen");immersiveReopen.setAccessible(true);
        android.app.Activity rotating=new android.app.Activity(){public boolean isChangingConfigurations(){return true;}};
        wide.testContext=rotating;immersiveCurrent.set(null,wide);wide.onDetachedFromWindow();
        check(immersiveCurrent.get(null)==null && immersiveReopen.getBoolean(null),"rotation detaches the old overlay and requests reopening");
        ViewGroup rotatedRoot=new ViewGroup(page.getContext(),null);rotatedRoot.addView(next);View.root=rotatedRoot;
        Immersive.restore(next);check(next.testPosted!=null && !immersiveReopen.getBoolean(null),"new artwork schedules exactly one restoration");
        Runnable restoreOverlay=next.testPosted;next.testPosted=null;restoreOverlay.run();
        Immersive restoredOverlay=(Immersive)immersiveCurrent.get(null);
        check(restoredOverlay!=null && restoredOverlay.getParent()==rotatedRoot,"rotation reopens inside the new activity root");
        restoredOverlay.onDetachedFromWindow();check(!immersiveReopen.getBoolean(null),"ordinary detach must not reopen");
        Immersive dismissedOverlay=new Immersive(page.getContext(),page);dismissedOverlay.testContext=rotating;
        immersiveCurrent.set(null,dismissedOverlay);dismissedOverlay.dismiss();dismissedOverlay.onDetachedFromWindow();
        check(!immersiveReopen.getBoolean(null),"explicit dismissal wins over rotation");
        View.root=null;
        System.out.println("PASS immersive restoration after rotation and dismissal cleanup");

        OrientationActivity orientationHost=new OrientationActivity();
        Method rotateImmersive=Immersive.class.getDeclaredMethod("rotation",boolean.class);rotateImmersive.setAccessible(true);
        Immersive orienting=new Immersive(page.getContext(),page);orienting.testContext=orientationHost;
        Settings.set(Settings.IMM_LANDSCAPE,true);rotateImmersive.invoke(orienting,true);
        check(orientationHost.requested==13 && Immersive.orientation==1,"immersive allows rotation while saving the page policy");
        orientationHost.r(0);check(orientationHost.requested==13 && Immersive.orientation==0,"page policy changes cannot lock the active overlay");
        rotateImmersive.invoke(orienting,false);
        check(orientationHost.requested==0 && !Immersive.landscapeRequested,"closing restores the latest native orientation policy");
        Settings.set(Settings.IMM_LANDSCAPE,false);int orientationRequests=orientationHost.requests;
        rotateImmersive.invoke(orienting,true);check(orientationHost.requests==orientationRequests,"disabled landscape never changes orientation");
        Settings.set(Settings.IMM_LANDSCAPE,true);rotateImmersive.invoke(orienting,true);
        orientationHost.changing=true;immersiveCurrent.set(null,orienting);orienting.onDetachedFromWindow();
        check(Immersive.landscapeRequested && immersiveReopen.getBoolean(null),"rotation keeps the override until the replacement overlay opens");
        OrientationActivity replacementHost=new OrientationActivity();replacementHost.requested=13;
        Immersive replacementOverlay=new Immersive(page.getContext(),page);replacementOverlay.testContext=replacementHost;
        rotateImmersive.invoke(replacementOverlay,true);rotateImmersive.invoke(replacementOverlay,false);
        check(replacementHost.requested==0,"rotation must not replace the saved page policy with the temporary override");
        immersiveReopen.setBoolean(null,false);Immersive.orientation=-1;Settings.resetAll();
        System.out.println("PASS immersive orientation override, page updates, disable and restoration");

        Settings.set(Settings.IMM_SWIPE,true);
        PlayerArt swipeArt=new PlayerArt(page.getContext(),null);swipeArt.testWidth=400;swipeArt.testHeight=400;
        final int[] swipeActions={0},childCancels={0};final boolean[] intercepted={false};
        View swipeNext=new View(page.getContext()){public boolean performClick(){swipeActions[0]++;return true;}};
        View swipeControls=new View(page.getContext()){public View findViewById(int id){return id==R_id_next_button ? swipeNext : null;}};
        ViewGroup swipeHost=new ViewGroup(page.getContext(),null){
            public android.content.res.Resources getResources(){return getContext().getResources();}
            public View findViewById(int id){return id==R_id_playback_controls_container?swipeControls:null;}
            public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)childCancels[0]++;return true;}
        };swipeHost.testWidth=400;swipeHost.addView(swipeArt);
        ViewGroup swipeParent=new ViewGroup(page.getContext(),null){public void requestDisallowInterceptTouchEvent(boolean value){intercepted[0]=value;}};
        swipeParent.addView(swipeHost);View.root=swipeHost;
        check(!PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,8,150,0)) && intercepted[0],"edge-start swipes reserve the player from outer dismissal");
        check(PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_MOVE,-40,180,0)) && childCancels[0]==1,"a diagonal horizontal gesture cancels native scrolling once");
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,-60,185,0));
        check(swipeActions[0]==1 && !intercepted[0],"a short off-centre swipe skips exactly once and releases the parent");
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,200,150,0));
        check(!PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_MOVE,202,180,0)) && !intercepted[0],"vertical intent leaves scrolling/dismissal to Spotify");
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,100,180,0));
        check(swipeActions[0]==1,"vertical gestures cannot become skips later");
        swipeNext.setEnabled(false);
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,200,150,0));
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_MOVE,100,155,0));
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,80,155,0));
        check(swipeActions[0]==1,"swiping cannot bypass a disabled native action");swipeNext.setEnabled(true);
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,200,150,0));
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_MOVE,100,155,0));
        PlayerSwipe.touch(swipeHost,MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,80,155,0));
        check(swipeActions[0]==1 && !intercepted[0],"cancel never commits a swipe");
        check(PlayerSwipe.committed(24,60,400,1) && !PlayerSwipe.committed(12,60,400,1) && !PlayerSwipe.committed(24,600,400,1),"short fast flings work while jitter and slow short drags do not");

        final int[] descSkips = {0};
        View descNext = new View(page.getContext()){
            public CharSequence getContentDescription(){return "Next track";}
            public boolean performClick(){descSkips[0]++;return true;}
        };
        ViewGroup descControls = new ViewGroup(page.getContext(), null){};
        descControls.addView(descNext);
        ViewGroup descHost = new ViewGroup(page.getContext(), null){
            public View findViewById(int id){return id==R_id_playback_controls_container ? descControls : null;}
        };
        View.root = descHost;
        PlayerSwipe.skip(descHost, true);
        check(descSkips[0] == 1, "PlayerSwipe finds next button by content description when IDs are absent");

        Settings.resetAll();View.root=null;
        System.out.println("PASS player edge swipes, axis locking, cancellation and disabled actions");




        Atmosphere tuned=new Atmosphere(),untuned=new Atmosphere();
        check(!tuned.tune(100,100,100),"default blur keeps the original raster");
        tuned.tune(0,0,0);check((Integer)field(tuned,"raster")==48 && (Integer)field(untuned,"raster")==12,"blur tuning is local to the immersive backdrop");
        final java.util.ArrayList<Integer> bitmapAlphas=new java.util.ArrayList<Integer>();
        final java.util.ArrayList<Float> motePositions=new java.util.ArrayList<Float>();
        Canvas tuneCanvas=new Canvas(){public void drawBitmap(Bitmap b,Rect source,RectF dest,Paint p){bitmapAlphas.add(p.getAlpha());}public void drawCircle(float x,float y,float radius,Paint p){motePositions.add(y);}};
        Settings.set(Settings.BASS_SHOCKWAVE,false);Settings.setChoice(Settings.BACKDROP_FX,"stars");
        tuned.setBounds(0,0,400,800);tuned.draw(tuneCanvas);
        check(bitmapAlphas.size()==2 && bitmapAlphas.get(0)==0 && bitmapAlphas.get(1)==0,"zero colour intensity removes the album image wash");
        java.util.ArrayList<Float> stillMotes=new java.util.ArrayList<Float>(motePositions);motePositions.clear();
        android.os.SystemClock.now+=1000;tuned.draw(tuneCanvas);check(motePositions.equals(stillMotes),"zero flow freezes particles");
        tuned.tune(200,200,100);motePositions.clear();tuned.draw(tuneCanvas);
        check((Integer)field(tuned,"raster")==4 && !motePositions.equals(stillMotes),"stronger blur softens the raster and flow restores movement");
        Settings.resetAll();
        final java.util.ArrayList<Integer> backdropShades=new java.util.ArrayList<Integer>();
        Canvas darknessCanvas=new Canvas(){public void drawRect(float l,float t,float r,float b,Paint p){if(l==0&&t==0&&r==800&&b==400&&(p.getColor()&0xffffff)==0x14121c)backdropShades.add(p.getAlpha());}};
        Settings.setStrength(Settings.IMM_DARKNESS,0);wide.onDraw(darknessCanvas);
        check(backdropShades.get(backdropShades.size()-1)==0,"zero darkness removes the immersive dimming wash");
        backdropShades.clear();Settings.setStrength(Settings.IMM_DARKNESS,100);wide.onDraw(darknessCanvas);
        check(backdropShades.get(backdropShades.size()-1)==255,"full darkness makes the backdrop opaque");
        Settings.resetAll();
        System.out.println("PASS immersive backdrop blur, colour, flow and instance isolation");


        SplashRoot splashRoot=new SplashRoot();Settings.set(Settings.SEEK_SPLASH,true);
        SeekSplash.at(splashRoot,100,100);SeekSplash.at(splashRoot,100,100);SeekSplash.at(splashRoot,100,100);
        check(SeekSplash.live==2 && splashRoot.layers.size()==2,"seek splashes are bounded to two overlays");
        SeekSplash splash=(SeekSplash)splashRoot.layers.get(0);splash.finish();splash.finish();check(SeekSplash.live==1,"splash cleanup is idempotent");
        splashRoot.focus=false;((SeekSplash)splashRoot.layers.get(0)).run();check(SeekSplash.live==0 && splashRoot.layers.isEmpty() && splashRoot.pending==null,"hiding the window removes its splash and callbacks");splashRoot.focus=true;
        Settings.set(6,true);SeekSplash.at(splashRoot,100,100);check(SeekSplash.live==0,"reduced motion suppresses splash creation");Settings.set(6,false);
        Settings.set(Settings.SEEK_SPLASH,false);SeekSplash.at(splashRoot,100,100);check(SeekSplash.live==0,"splash switch off suppresses creation");Settings.set(Settings.SEEK_SPLASH,true);
        check(SeekSplash.dropY(0,-140)==0 && SeekSplash.dropY(.1f,-140)<0 && SeekSplash.dropY(.32f,-140)>SeekSplash.dropY(.16f,-140),"droplets rise and fall ballistically");
        Wave splashWave=new Wave();splashWave.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0,0,0));splashWave.onLevelChange(5000);splashWave.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0,0,0));check(SeekSplash.live==0,"cancelled seeks never splash");
        splashWave.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,0,0,0));splashWave.onLevelChange(6000);splashWave.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,0,0,0));splashWave.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,0,0,0));check(SeekSplash.live==1,"a committed seek splashes exactly once");
        splash=(SeekSplash)splashRoot.layers.get(0);android.os.SystemClock.now+=400;splash.run();check(SeekSplash.live==0 && splashRoot.pending==null,"finished splashes leave no callbacks");android.os.SystemClock.now-=400;
        Wave firstSeek=new Wave();firstSeek.setState(new int[]{0x010100a7});firstSeek.onLevelChange(7000);firstSeek.onTouch(splashRoot,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,0,0,0));check(SeekSplash.live==1,"the first seek can install its listener after DOWN and still splash");((SeekSplash)splashRoot.layers.get(0)).finish();
        System.out.println("PASS seek splash bounds, cancellation, reduced motion and cleanup");
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
        fromTint.setInt(null,0xff000000);toTint.setInt(null,0xffffffff);
        atTint.setLong(null,android.os.SystemClock.uptimeMillis()-450);
        Tint.follow(0xffff0000);
        int continued=fromTint.getInt(null)&255;
        check(continued>60 && continued<200 && toTint.getInt(null)==0xffff0000,"a quick skip must continue from the visible colour");
        long fadeStarted=atTint.getLong(null);
        Tint.follow(0xffff0000);
        check(atTint.getLong(null)==fadeStarted,"repeated artwork colours must not restart the fade");
        check(Tint.fading(),"a new album colour must request animation frames");
        Settings.set(Settings.MINI_ALBUM,true);
        MiniSurface albumSurface=new MiniSurface(0xff2b2640,28);
        albumSurface.draw(new Canvas());
        check(albumSurface.nextFrame>0,"the mini player must redraw during a colour fade even when paused");
        Settings.set(6,true);
        check(!Tint.fading() && Tint.album()==0xffff0000,"reduced motion must apply the new album colour immediately");
        albumSurface.nextFrame=0;
        albumSurface.draw(new Canvas());
        check(albumSurface.nextFrame==0,"reduced motion must not schedule colour animation");
        Settings.set(6,false);
        atTint.setLong(null,android.os.SystemClock.uptimeMillis()-5000);
        check(!Tint.fading(),"a completed colour fade must stop requesting frames");
        albumSurface.nextFrame=0;
        albumSurface.draw(new Canvas());
        check(albumSurface.nextFrame==0,"the mini player must stop redrawing after the fade");
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
        generated.testId=R_id_play_pause_button; boolean burstReached=false;
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

        java.lang.reflect.Constructor<Flourish> flourishConstructor=Flourish.class.getDeclaredConstructor(View.class,android.view.Window.Callback.class);flourishConstructor.setAccessible(true);
        View barsRoot=new View(new android.content.Context()) { public android.content.res.Resources getResources(){return new android.content.res.Resources();} };
        Flourish barsTest=flourishConstructor.newInstance(barsRoot,null);
        Field navBarField=Flourish.class.getDeclaredField("nav");navBarField.setAccessible(true);navBarField.set(barsTest,new View(new android.content.Context()));
        java.lang.reflect.Method slideBars=Flourish.class.getDeclaredMethod("bars",int.class);slideBars.setAccessible(true);
        Flourish.navigationTouch(true);slideBars.invoke(barsTest,1);
        check(!Flourish.barsGone,"upward menu drag cannot auto-hide navigation while the gesture owns it");
        Flourish.navigationTouch(false);slideBars.invoke(barsTest,1);
        check(Flourish.barsGone,"normal auto-hide resumes after releasing the navigation gesture");
        slideBars.invoke(barsTest,-1);
        System.out.println("PASS row cascade and hold ring");

        // Mini-player fling-to-skip, gooey tab pill and 3D card tilt/glint.
        check(MiniBar.flingCommits(-32,1,48) && MiniBar.flingCommits(32,1,48) && !MiniBar.flingCommits(-20,1,48) && !MiniBar.flingCommits(-32,1,16) && !MiniBar.flingCommits(32,1,32),"mini fling commits only past threshold when that skip direction is allowed");
        float[] f0=MiniArt.flingStep(50,30,-40,15,48,0), f1=MiniArt.flingStep(50,30,-40,15,48,1);
        check(f0[0]==10 && f0[1]==30 && f0[2]==15 && f0[3]==1 && f0[4]==1,"fling starts at the released cover position and angle");
        check(f1[0]<f0[0]-80 && f1[1]>f0[1] && f1[2]==15-480 && f1[3]<1 && f1[4]==0,"flung disc spins off in the swipe direction and fades out");
        float[] dropStart=MiniArt.dropStep(0,48,true), dropMid=MiniArt.dropStep(.55f,48,true), dropEnd=MiniArt.dropStep(1,48,true), popStart=MiniArt.dropStep(0,48,false);
        check(dropStart[0]<popStart[0] && dropStart[1]<-15 && dropMid[0]>1 && dropMid[1]>0 && Math.abs(dropEnd[0]-1)<.001f && Math.abs(dropEnd[1])<.001f,"incoming flung disc drops from above, overshoots and settles");
        MiniArt.flingAt(splashRoot,art,40,30,48,-40,10);MiniArt.flingAt(splashRoot,art,40,30,48,-40,10);MiniArt.flingAt(splashRoot,art,40,30,48,-40,10);
        check(MiniArt.live==2 && splashRoot.layers.size()==2,"mini fling overlays are bounded to two");
        MiniArt.Fling flyer=(MiniArt.Fling)splashRoot.layers.get(0);flyer.draw(new TraceCanvas());flyer.finish();flyer.finish();
        check(MiniArt.live==1,"mini fling cleanup is idempotent");
        android.os.SystemClock.now+=400;((MiniArt.Fling)splashRoot.layers.get(0)).run();android.os.SystemClock.now-=400;
        check(MiniArt.live==0 && splashRoot.layers.isEmpty() && splashRoot.pending==null,"finished mini fling removes its overlay and callbacks");
        check(NavIndicator.gooeyPinch(64,64)==0 && NavIndicator.gooeyPinch(90,64)>0 && NavIndicator.gooeyPinch(200,64)==1,"gooey waist pinches only while stretched");
        TracePath gooeyPath=new TracePath();NavIndicator.gooey(gooeyPath,100,14,220,46,.8f);
        check(gooeyPath.closes==1 && gooeyPath.points.size()==7 && Math.abs(gooeyPath.area(0,7))>0,"gooey pill is a closed contour");
        check(RowCards.fitsGrid(180,160,480,1000) && !RowCards.fitsGrid(56,1000,1000,1000) && !RowCards.fitsGrid(216,1000,1000,1000) && !RowCards.fitsGrid(56,80,80,1000),"grid card fit matches multi-column cards only");
        float[] tiltTR=RowCards.tiltTarget(180,20,200,100), tiltBL=RowCards.tiltTarget(20,80,200,100), tiltC=RowCards.tiltTarget(100,50,200,100);
        check(tiltTR[0]>0 && tiltTR[1]>0 && tiltBL[0]<0 && tiltBL[1]<0 && tiltC[0]==0 && tiltC[1]==0,"3D card tilt leans toward the pressed corner");
        check(RowCards.glintX(10,210,60,0)==-50 && RowCards.glintX(10,210,60,.5f)>RowCards.glintX(10,210,60,0) && RowCards.glintX(10,210,60,1)>210,"diagonal glint sweeps across the card");
        RowCards pressCard=new RowCards(1,false);pressCard.setBounds(0,0,1000,64);
        ViewGroup cardList=new ViewGroup(new android.content.Context(),null);View cardRow=new View(new android.content.Context());cardList.addView(cardRow);
        pressCard.onTouch(cardRow,MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,800,16,0));
        check((Float)field(pressCard,"targetX")>0 && (Float)field(pressCard,"targetY")>0 && (Long)field(pressCard,"glintAt")==android.os.SystemClock.now,"pressing a card aims 3D tilt and starts its glint");
        pressCard.onTouch(cardRow,MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,800,16,0));
        check((Float)field(pressCard,"targetX")==0 && (Float)field(pressCard,"targetY")==0,"releasing a card returns its tilt target to rest");
        System.out.println("PASS mini fling-to-skip, gooey tab pill and 3D card glint");

        String themeFile=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("fonts")).getParent().resolve("examples/custom-theme.toml"));
        int[][] imported=ThemeImport.parse(themeFile);
        check(ThemeImport.name(themeFile).equals("Lavender example"),"TOML theme name survives import");
        check(ThemeImport.name("\ufeffname = 'Ocean Blue' # title\n[dark]\n").equals("Ocean Blue"),"single quoted names and BOM are supported");
        check(ThemeImport.name("[dark]\nname = 'colour metadata'\n").equals("Imported theme"),"only root metadata supplies the name");
        check(ThemeImport.cleanName("  \n  ").equals("Imported theme"),"blank theme names use the fallback");
        check(imported[0][0]==0x14121c && imported[1][0]==0xfffbfe && imported[0].length==9,"TOML imports both complete palettes");
        for(String invalid:new String[]{themeFile.replace("#f3eeff","#14121c"),themeFile.replace("#d0bcff","not-a-colour"),themeFile.replace("[light]","[wrong]"),themeFile+"accent = '#123456'\n",themeFile.substring(0,themeFile.indexOf("[light]"))}) {
            try{ThemeImport.parse(invalid);throw new AssertionError("invalid or unreadable theme accepted");}catch(IllegalArgumentException expected){}
        }
        java.util.Map<String,String> material=new java.util.HashMap<String,String>();
        String[] materialRoles={"background","surface_container_low","surface_container_highest","on_surface","on_surface_variant","outline","primary"};
        for(int i=0;i<materialRoles.length;i++)material.put(materialRoles[i],String.format("#%06x",imported[0][i]));
        check(ThemeImport.variant(material,"dark")[6]==imported[0][6],"Material role names work alongside Spatch role names");
        Field paletteLight=Palette.class.getDeclaredField("light");paletteLight.setAccessible(true);
        Field palettePrefs=Palette.class.getDeclaredField("preferences");palettePrefs.setAccessible(true);Object oldPalettePrefs=palettePrefs.get(null);
        palettePrefs.set(null,new Prefs());activeField.setInt(null,0);
        Method wantsLight=Palette.class.getDeclaredMethod("wantsLight",android.content.Context.class);wantsLight.setAccessible(true);
        final android.content.res.Configuration phoneConfiguration=new android.content.res.Configuration();
        android.content.Context displayContext=new android.content.Context(){public android.content.res.Resources getResources(){return new android.content.res.Resources(){public android.content.res.Configuration getConfiguration(){return phoneConfiguration;}};}};
        Palette.mode("light");phoneConfiguration.uiMode=0x20;check((Boolean)wantsLight.invoke(null,displayContext),"forced light overrides a dark phone");
        Palette.mode("dark");phoneConfiguration.uiMode=0x10;check(!(Boolean)wantsLight.invoke(null,displayContext),"forced dark overrides a light phone");
        Palette.mode("system");check((Boolean)wantsLight.invoke(null,displayContext),"system follows the light phone");phoneConfiguration.uiMode=0x20;check(!(Boolean)wantsLight.invoke(null,displayContext),"system follows a dark phone");
        paletteLight.setBoolean(null,true);
        check(Palette.color(0xfff3eeff)==0xff1d1b20 && Palette.color(0xffffffff)==0xff1d1b20 && Palette.color(0xff000000)==0xfffffbfe,"light mode maps role text, native white ink and opaque black surfaces");
        check(Palette.color(0x44000000)==0x44000000 && Palette.dialogTheme()==16974546,"transparent shadows stay dark and dialogs follow light mode");
        byte[] lightResource=Palette.recolor(new byte[]{8,0,0,0x1c,(byte)0xff,(byte)0xee,(byte)0xf3,(byte)0xff},Palette.selectedRow("lavender"));
        check((lightResource[4]&255)==0x20 && (lightResource[7]&255)==0xff,"compiled resource recolouring uses the same light text and keeps alpha");
        String lightAnimation=new String(Palette.recolorAnimation("{\"k\":[0.815686,0.737255,1.000000,1]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),0xff0000),java.nio.charset.StandardCharsets.UTF_8);
        check(lightAnimation.contains("[1.000000,0.000000,0.000000,1]"),"template Lottie colours follow the selected accent");
        check(Palette.saveCustom(imported[0],imported[1],"Example") && Palette.hasCustom() && Palette.tone(Palette.CUSTOM,0)==0xfffffbfe,"validated imported colours persist and render in light mode");
        check(Palette.customName().equals("Example"),"saved custom theme name is read from preferences");
        ThemeSwatch namedSwatch=new ThemeSwatch(new android.content.Context(),Palette.CUSTOM,"Imported theme");
        namedSwatch.setName("Ocean");
        check(field(namedSwatch,"name").equals("Ocean") && field(namedSwatch,"secondLine")==null,"import refresh replaces the visible swatch title");
        imported[1][0]=0;check(Palette.tone(Palette.CUSTOM,0)==0xfffffbfe,"saving takes a copy of imported arrays");
        for(String named:new String[]{"default","spotify","catppuccin-macchiato","catppuccin-frappe","rose-pine-moon","tokyo-night-storm","everforest","kanagawa","solarized","one","wallpaper"})check(java.util.Arrays.asList(Palette.NAMES).contains(named),"missing screenshot theme: "+named);
        check(!java.util.Arrays.asList(Palette.NAMES).contains("swiftify"),"Swiftify is excluded");
        paletteLight.setBoolean(null,false);check(Palette.dialogTheme()==16974545,"dark dialogs stay dark");Palette.mode("system");palettePrefs.set(null,oldPalettePrefs);
        System.out.println("PASS theme import validation, palettes, global display modes, native ink and animations");

        SpatchSettings searchableSettings=new SpatchSettings(new android.content.Context());
        Method findSettings=SpatchSettings.class.getDeclaredMethod("search",android.widget.LinearLayout.class,String.class);findSettings.setAccessible(true);
        int indexed=((java.util.List)field(searchableSettings,"searchable")).size();
        for(String[] query:new String[][]{{"blur","Immersive player"},{"tilt strength","Gestures & touch"},{"frapp","Themes & display"},{"light","Themes & display"},{"lyrics","Player"}}){
            android.widget.LinearLayout results=new android.widget.LinearLayout(new android.content.Context());findSettings.invoke(searchableSettings,results,query[0]);boolean routed=false;
            for(int i=0;i<results.getChildCount();i++){Object target=results.getChildAt(i).getTag();if(target instanceof View && query[1].equals(((View)target).getTag()))routed=true;}
            check(routed,"search must route "+query[0]+" to "+query[1]);
        }
        check(((java.util.List)field(searchableSettings,"searchable")).size()==indexed,"search results must not re-index themselves");
        System.out.println("PASS settings search finds sliders, imported palettes and display modes on their pages");

    }
}
'''))
    subprocess.run([str(JAVA / 'javac'), '-d', str(out / 'classes'), *sources, str(harness)], check=True)
    subprocess.run([str(JAVA / 'java'), '-Dfonts=' + str(ROOT / 'theme/fonts'), '-cp', str(out / 'classes'), 'spatch.PlayerRuntimeTest'], check=True)
