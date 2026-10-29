package android.graphics;
// Compile-time stub only; never packaged (the device provides the real class).
public class Canvas {
 public Canvas(){} public Canvas(Bitmap b){}
 public void translate(float x,float y){} public void scale(float x,float y,float px,float py){}
 public int saveLayerAlpha(float l,float t,float r,float b,int alpha){return 0;}
 public int save(){return 0;} public boolean clipPath(Path p){return false;} public void restoreToCount(int n){}
 public void drawPath(Path p,Paint q){} public void drawColor(int c){}
 public void drawRect(float l,float t,float r,float b,Paint p){}
 public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){}
 public void drawText(String s,float x,float y,Paint p){}
 public void drawBitmap(Bitmap b,Rect src,RectF dst,Paint p){}
 public void drawCircle(float cx, float cy, float radius, Paint p) {}
 public void drawLine(float startX, float startY, float stopX, float stopY, Paint p) {}
}
