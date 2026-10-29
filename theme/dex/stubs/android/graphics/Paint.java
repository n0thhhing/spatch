package android.graphics;
// Compile-time stub only; never packaged (the device provides the real class).
public class Paint {
    public Shader setShader(Shader s){return null;}
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Align { LEFT, CENTER, RIGHT }
    public Paint(int flags) { throw new RuntimeException(); }
    public Paint(Paint src) { throw new RuntimeException(); }
    public int getAlpha() { throw new RuntimeException(); }
    public void setAlpha(int a) { throw new RuntimeException(); }
    public void setColor(int c) { throw new RuntimeException(); }
    public int getColor() { throw new RuntimeException(); }
    public void setStyle(Style s) { throw new RuntimeException(); }
    public void setStrokeCap(Cap c) { throw new RuntimeException(); }
    public void setStrokeWidth(float w) { throw new RuntimeException(); }
    public void setTextSize(float s) { throw new RuntimeException(); }
    public void setFakeBoldText(boolean b) { throw new RuntimeException(); }
    public Typeface setTypeface(Typeface t) { throw new RuntimeException(); }
    public void setShadowLayer(float radius, float dx, float dy, int color) { throw new RuntimeException(); }
    public void clearShadowLayer() { throw new RuntimeException(); }
    public float measureText(String s) { throw new RuntimeException(); }
    public int breakText(String s, boolean forwards, float maxWidth, float[] measured) { throw new RuntimeException(); }
    public void setTextAlign(Align a) { throw new RuntimeException(); }
    public ColorFilter setColorFilter(ColorFilter f) { throw new RuntimeException(); }
}
