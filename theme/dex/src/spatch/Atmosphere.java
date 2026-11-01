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
    private static final int MOTES=36;
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
        String fx=Settings.choice(Settings.BACKDROP_FX);
        boolean motes=!"none".equals(fx);
        if(motes) drawMotes(c,w,h,"stars".equals(fx),mix(fromColors[0],colors[0],blend));
        if(Wave.animationsEnabled() && (Settings.artDrift()||motes) && isVisible()) scheduleSelf(this,SystemClock.uptimeMillis()+(motes?33:66));
    }
    public void run(){invalidateSelf();}

    /** Slow specks rising through the backdrop, swelling with the bass when audio is being read. */
    private void drawMotes(Canvas c,float w,float h,boolean stars,int tint){
        float dp=Math.max(1,w/400f), now=Wave.animationsEnabled()?SystemClock.uptimeMillis()/1000f:0;
        float energy=Spectrum.alpha*(Spectrum.bands[0]+Spectrum.bands[1])/2;
        int light=mix(tint,0xffffffff,.65f)&0xffffff;
        for(int i=0;i<MOTES;i++){
            float seed=(i*2654435761L&0xffffffffL)/4294967296f, speed=.012f+seed*.03f;
            float rise=(seed*7+now*speed+i*.13f)%1f, x=(seed*97%1f)*w+(float)Math.sin(now*.3f+i)*14*dp;
            float twinkle=.5f+.5f*(float)Math.sin(now*(.7f+seed)+i), size=(1+seed*2)*dp*(1+energy);
            paint.setColor(light|(int)((stars?70+150*twinkle:30+60*twinkle)*(1-rise*.5f))<<24);
            float y=h*(1-rise);
            c.drawCircle(x,y,size,paint);
            if(stars&&seed>.7f){c.drawRect(x-size*3,y-size*.2f,x+size*3,y+size*.2f,paint);c.drawRect(x-size*.2f,y-size*3,x+size*.2f,y+size*3,paint);}
        }
    }
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
