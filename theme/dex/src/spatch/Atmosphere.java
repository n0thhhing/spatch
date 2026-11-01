package spatch;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Small artwork raster supplies diffuse, slowly drifting colour fields; no per-frame bitmap work. */
public final class Atmosphere extends Drawable implements Runnable {
    private final Paint paint=new Paint(3);
    private final Bitmap sample=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
    private final int[] colors=new int[3];
    private final int[] fromColors=new int[3];
    private final Bitmap previousSample=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
    private long changedAt;
    private final RadialGradient[] glows=new RadialGradient[3];
    private final int[] glowColors=new int[3];
    private final Matrix place=new Matrix();
    private final RectF area=new RectF();
    private float glowRadius;
    public void capture(Drawable art) {
        float blend=blend();
        for(int i=0;i<3;i++) fromColors[i]=mix(fromColors[i],colors[i],blend);
        Paint copy=new Paint(3);
        previousSample.eraseColor(0xff14121c);
        new Canvas(previousSample).drawBitmap(sample,null,new RectF(0,0,12,12),copy);
        sample.eraseColor(0xff14121c);
        PlayerArt.drawArtwork(new Canvas(sample),art,0,0,12,12);
        colors[0]=sample.getPixel(3,3); colors[1]=sample.getPixel(8,4); colors[2]=sample.getPixel(6,9);
        changedAt=SystemClock.uptimeMillis();
        invalidateSelf();
    }
    public void draw(Canvas c) {
        Rect b=getBounds();
        c.drawColor(0xff14121c);
        float w=b.width(),h=b.height();
        float phase=Wave.animationsEnabled()&&Settings.artDrift()?(SystemClock.uptimeMillis()%30000)/30000f*6.283185f:0;
        // Very low resolution art, enlarged with bilinear filtering, forms the blurred base.
        float blend=blend();
        area.set(b.left,b.top,b.right,b.bottom);
        paint.setShader(null); paint.setAlpha((int)(65*(1-blend)));
        c.drawBitmap(previousSample,null,area,paint);
        paint.setAlpha((int)(65*blend));
        c.drawBitmap(sample,null,area,paint);
        paint.setAlpha(255);
        for(int i=0;i<3;i++) {
            float x=w*(.25f+i*.25f)+(float)Math.sin(phase+i*2)*w*.15f;
            float y=h*(.2f+i*.16f)+(float)Math.cos(phase+i)*h*.07f;
            int color=(mix(fromColors[i],colors[i],blend)&0xffffff)|0x88000000;
            // Colours only change during a song's fade: the drift just moves the same gradient.
            if(glows[i]==null || glowColors[i]!=color || glowRadius!=w) {
                glows[i]=new RadialGradient(0,0,w*.8f,new int[]{color,color&0xffffff},null,Shader.TileMode.CLAMP);
                glowColors[i]=color;
            }
            place.setTranslate(x,y);
            glows[i].setLocalMatrix(place);
            paint.setShader(glows[i]);
            c.drawRect(b.left,b.top,b.right,b.bottom,paint);
        }
        glowRadius=w;
        paint.setShader(null); paint.setColor(0x5514121c);
        c.drawRect(b.left,b.top,b.right,b.bottom,paint);
        if(Wave.animationsEnabled() && Settings.artDrift() && isVisible()) scheduleSelf(this,SystemClock.uptimeMillis()+66);
    }
    public void run(){invalidateSelf();}
    private float blend(){
        if(!Wave.animationsEnabled())return 1;
        float t=Math.min(1f,(SystemClock.uptimeMillis()-changedAt)/900f);
        return t*t*(3-2*t);
    }
    private static int mix(int a,int b,float t){
        int result=0xff000000;
        for(int shift=0;shift<=16;shift+=8){
            int x=(a>>shift)&255,y=(b>>shift)&255;
            result|=((int)(x+(y-x)*t))<<shift;
        }
        return result;
    }
    public void setAlpha(int a){}
    public void setColorFilter(ColorFilter f){}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
