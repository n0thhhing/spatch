package android.graphics.drawable;
// Compile-time stub only; never packaged (the device provides the real class).
public abstract class Drawable {
    public interface Callback {}
    public Callback getCallback(){return null;}
    public final void setCallback(Callback c){}
    public final boolean setLevel(int level){return false;}
    public final int getLevel(){return 0;}
    public void setBounds(int l,int t,int r,int b){}
    public void setBounds(android.graphics.Rect r){}
    public boolean isVisible(){return true;}
    protected boolean onStateChange(int[] s){return false;}
    public boolean isStateful(){return false;}
    public boolean setState(int[] states){return false;}
    public int[] getState(){return new int[0];}
    public void setTintList(android.content.res.ColorStateList tint){}
    public void setTint(int tint){}
    public android.graphics.drawable.Drawable mutate(){return this;}
    public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode){}
    protected void onBoundsChange(android.graphics.Rect bounds){}
    public abstract void draw(android.graphics.Canvas canvas);
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(android.graphics.ColorFilter filter);
    public abstract int getOpacity();
    protected boolean onLevelChange(int level) { throw new RuntimeException(); }
    public final android.graphics.Rect getBounds() { throw new RuntimeException(); }
    public void invalidateSelf() { throw new RuntimeException(); }
    public void scheduleSelf(Runnable what, long when) { throw new RuntimeException(); }
}
