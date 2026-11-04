package spatch;

import android.graphics.Path;

/** The artwork boundary and its outward unit normal, shared by the mask and its audio decorations. */
final class ArtContour {
    private static final float[] point = new float[4];

    static void point(String shape, double angle, float corner, float[] out) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle), r = 1;
        float nx = c, ny = s;
        if ("squircle".equals(shape)) {
            r = (float) Math.pow(c*c*c*c + s*s*s*s, -.25);
            nx = c*c*c; ny = s*s*s;
        } else if ("scallop".equals(shape)) {
            r = .95f + .05f * (float) Math.cos(10 * angle);
            float derivative = -.5f * (float) Math.sin(10 * angle);
            nx = r*c + derivative*s; ny = r*s - derivative*c;
        } else if ("soft".equals(shape)) {
            float q = Math.max(.001f, Math.min(1, corner)), edge = 1-q;
            float ac = Math.abs(c), as = Math.abs(s);
            if (ac >= as && as/ac <= edge) {
                r = 1/ac; nx = Math.signum(c); ny = 0;
            } else if (as > ac && ac/as <= edge) {
                r = 1/as; nx = 0; ny = Math.signum(s);
            } else {
                float x = Math.signum(c)*edge, y = Math.signum(s)*edge, projection = c*x+s*y;
                r = projection + (float) Math.sqrt(Math.max(0, projection*projection-x*x-y*y+q*q));
                nx = c*r-x; ny = s*r-y;
            }
        }
        float length = (float) Math.hypot(nx, ny);
        out[0] = c*r; out[1] = s*r; out[2] = nx/length; out[3] = ny/length;
    }

    static void trace(Path path, String shape, float cx, float cy, float radius) {
        path.reset();
        if ("circle".equals(shape)) path.addCircle(cx, cy, radius, Path.Direction.CW);
        else if ("soft".equals(shape)) path.addRoundRect(cx-radius, cy-radius, cx+radius, cy+radius, radius*.26f, radius*.26f, Path.Direction.CW);
        else {
            for (int i = 0; i <= 120; i++) {
                point(shape, i*Math.PI/60, .26f, point);
                float x = cx + radius*point[0], y = cy + radius*point[1];
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
        }
    }
}
