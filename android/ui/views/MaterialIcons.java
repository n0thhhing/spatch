package spatch;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.HashMap;
import java.util.Map;

/** Optional Google Material Symbols Rounded icons (Apache 2.0), parsed lazily into cached Paths. */
final class MaterialIcons {
    private static final Map<String, String> DATA = new HashMap<>();
    private static final Map<String, Path> CACHE = new HashMap<>();

    static {
        DATA.put("play", "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z");
        DATA.put("pause", "M600-200q-33 0-56.5-23.5T520-280v-400q0-33 23.5-56.5T600-760h80q33 0 56.5 23.5T760-680v400q0 33-23.5 56.5T680-200h-80Zm-320 0q-33 0-56.5-23.5T200-280v-400q0-33 23.5-56.5T280-760h80q33 0 56.5 23.5T440-680v400q0 33-23.5 56.5T360-200h-80Z");
        DATA.put("next", "M660-280v-400q0-17 11.5-28.5T700-720q17 0 28.5 11.5T740-680v400q0 17-11.5 28.5T700-240q-17 0-28.5-11.5T660-280Zm-440-35v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T548-480q0 10-4.5 18.5T530-447L282-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Z");
        DATA.put("back", "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z");
        DATA.put("chevron", "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 11-28 11t-28-11q-11-11-11-28t11-28l156-156Z");
        DATA.put("check", "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z");
        DATA.put("close", "M291-249q-9 9-21.5 9t-21.5-9q-9-9-9-21.5t9-21.5l189-188-189-188q-9-9-9-21.5t9-21.5q9-9 21.5-9t21.5 9l189 188 188-188q9-9 21.5-9t21.5 9q9 9 9 21.5t-9 21.5L502-480l188 188q9 9 9 21.5t-9 21.5q-9 9-21.5 9t-21.5-9L480-438 291-249Z");
        DATA.put("add", "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z");
        DATA.put("shuffle", "M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Zm-428-12q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Zm-1-560q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z");
        DATA.put("repeat", "m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z");
        DATA.put("volume", "M280-360v-240h120l140-140q14-14 31-8.5t17 24.5v488q0 19-17 24.5t-31-8.5L400-360H280Zm360-120q0-46-24.5-84T550-618q-14-7-19-21.5t2-27.5q7-14 21.5-19t27.5 2q56 29 91 80t35 124q0 70-35 122t-91 81q-14 7-28 2t-20-20q-7-14-2-28t20-21q41-20 65.5-57.5T640-480Z");
        DATA.put("queue", "M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h400q17 0 28.5 11.5T600-280q0 17-11.5 28.5T560-240H160Zm0-160q-17 0-28.5-11.5T120-440q0-17 11.5-28.5T160-480h400q17 0 28.5 11.5T600-440q0 17-11.5 28.5T560-400H160Zm0-160q-17 0-28.5-11.5T120-600q0-17 11.5-28.5T160-640h560q17 0 28.5 11.5T760-600q0 17-11.5 28.5T720-560H160Zm560 360v-200q0-17 11.5-28.5T760-440q17 0 28.5 11.5T800-400v200q0 17-11.5 28.5T760-160q-17 0-28.5-11.5T720-200Z");
        DATA.put("home", "M240-200h120v-200q0-17 11.5-28.5T400-440h160q17 0 28.5 11.5T600-400v200h120v-360L480-740 240-560v360Zm-80 0v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H560q-17 0-28.5-11.5T520-160v-200h-80v200q0 17-11.5 28.5T400-120H240q-33 0-56.5-23.5T160-200Zm320-270Z");
        DATA.put("library", "M500-360q42 0 71-29t29-71v-220h80q17 0 28.5-11.5T720-720q0-17-11.5-28.5T680-760h-80q-17 0-28.5 11.5T560-720v180q-13-10-28-15t-32-5q-42 0-71 29t-29 71q0 42 29 71t71 29ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-680v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Zm160-720v480-480Z");
        DATA.put("heart", "M480-147q-14 0-28.5-5T426-168l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 80-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5Z");
        DATA.put("heart-outline", "M480-147q-14 0-28.5-5T426-168l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 80-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5Zm-38-543q-29-41-62-62.5T300-774q-60 0-100 40t-40 100q0 52 37 110.5T285.5-410q51.5 55 106 103t88.5 79q34-31 88.5-79t106-103Q726-465 763-523.5T800-634q0-60-40-100t-100-40q-47 0-80 21.5T518-690q-7 10-17 15t-21 5q-11 0-21-5t-17-15Zm38 189Z");
        DATA.put("search", "M796-121 533-384q-30 26-69.5 41T380-328q-109 0-184.5-75.5T120-588q0-109 75.5-184.5T380-848q109 0 184.5 75.5T640-588q0 44-15 83.5T584-435l264 262q9 9 9 21t-9 21q-9 9-21 9t-21-9ZM380-400q78 0 133-55t55-133q0-78-55-133t-133-55q-78 0-133 55t-55 133q0 78 55 133t133 55Z");
        DATA.put("timer", "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 320q133 0 226.5-93.5T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 133 93.5 226.5T480-160Z");
        DATA.put("devices", "M280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v720q0 33-23.5 56.5T680-40H280Zm0-120v40h400v-40H280Zm0-80h400v-480H280v480Zm0-560h400v-40H280v40Zm0 0v-40 40Zm0 640v40-40Z");
        DATA.put("album", "M480-300q75 0 127.5-52.5T660-480q0-75-52.5-127.5T480-660q-75 0-127.5 52.5T300-480q0 75 52.5 127.5T480-300Zm0-140q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520q17 0 28.5 11.5T520-480q0 17-11.5 28.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z");
        DATA.put("artist", "M480-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47ZM160-240v-32q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-33 0-56.5-23.5T160-240Zm80 0h480v-32q0-11-5.5-20T700-306q-54-27-109-40.5T480-360q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32Zm240-320q33 0 56.5-23.5T560-640q0-33-23.5-56.5T480-720q-33 0-56.5 23.5T400-640q0 33 23.5 56.5T480-560Zm0-80Zm0 400Z");
        DATA.put("note", "M400-120q-66 0-113-47t-47-113q0-66 47-113t113-47q23 0 42.5 5.5T480-418v-382q0-17 11.5-28.5T520-840h160q17 0 28.5 11.5T720-800v80q0 17-11.5 28.5T680-680H560v400q0 66-47 113t-113 47Z");
        DATA.put("people", "M40-272q0-34 17.5-62.5T104-378q62-31 126-46.5T360-440q66 0 130 15.5T616-378q29 15 46.5 43.5T680-272v32q0 33-23.5 56.5T600-160H120q-33 0-56.5-23.5T40-240v-32Zm800 112H738q11-18 16.5-38.5T760-240v-40q0-44-24.5-84.5T666-434q51 6 96 20.5t84 35.5q36 20 55 44.5t19 53.5v40q0 33-23.5 56.5T840-160ZM360-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47Zm400-160q0 66-47 113t-113 47q-11 0-28-2.5t-28-5.5q27-32 41.5-71t14.5-81q0-42-14.5-81T544-792q14-5 28-6.5t28-1.5q66 0 113 47t47 113ZM120-240h480v-32q0-11-5.5-20T580-306q-54-27-109-40.5T360-360q-56 0-111 13.5T140-306q-9 5-14.5 14t-5.5 20v32Zm240-320q33 0 56.5-23.5T440-640q0-33-23.5-56.5T360-720q-33 0-56.5 23.5T280-640q0 33 23.5 56.5T360-560Zm0 320Zm0-400Z");
        DATA.put("mix", "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z");
    }

    static boolean draw(Canvas canvas, Paint paint, String key, int color, float size) {
        if ("share".equals(key)) {
            canvas.save();
            canvas.scale(size / 960f, size / 960f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(74f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setPathEffect(null);
            float r = 92f;
            canvas.drawCircle(700f, 220f, r, paint);
            canvas.drawCircle(260f, 480f, r, paint);
            canvas.drawCircle(700f, 740f, r, paint);
            canvas.drawLine(340f, 433f, 620f, 267f, paint);
            canvas.drawLine(340f, 527f, 620f, 693f, paint);
            canvas.restore();
            return true;
        }
        Path path = path(key);
        if (path == null) return false;
        canvas.save();
        canvas.scale(size / 960f, size / 960f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        canvas.drawPath(path, paint);
        canvas.restore();
        return true;
    }

    private static synchronized Path path(String key) {
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Path out = null;
        if ("previous".equals(key)) {
            Path base = path("next");
            if (base != null) {
                out = new Path(base);
                Matrix m = new Matrix();
                m.setScale(-1f, 1f, 480f, 480f);
                out.transform(m);
            }
        } else if ("down".equals(key)) {
            Path base = path("chevron");
            if (base != null) {
                out = new Path(base);
                Matrix m = new Matrix();
                m.setRotate(90f, 480f, 480f);
                out.transform(m);
            }
        } else {
            String d = DATA.get(key);
            if (d != null) out = parse(d);
        }
        CACHE.put(key, out);
        return out;
    }

    /** Parses Material Symbols pathData (M/m L/l H/h V/v Q/q T/t Z/z) into a [0..960] Path. */
    private static Path parse(String d) {
        Path p = new Path();
        int n = d.length(), i = 0;
        char cmd = 0, prev = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0, qx = 0, qy = 0;
        float[] num = new float[1];
        while (i < n) {
            while (i < n && (d.charAt(i) <= ' ' || d.charAt(i) == ',')) i++;
            if (i >= n) break;
            char ch = d.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) { cmd = ch; i++; }
            else if (cmd == 'M') cmd = 'L';
            else if (cmd == 'm') cmd = 'l';
            switch (cmd) {
            case 'M': case 'm': {
                i = readNum(d, i, num); float x = num[0];
                i = readNum(d, i, num); float y = num[0];
                if (cmd == 'm') { cx += x; cy += y; } else { cx = x; cy = y; }
                p.moveTo(cx, cy + 960f); sx = cx; sy = cy; prev = cmd; break;
            }
            case 'L': case 'l': {
                i = readNum(d, i, num); float x = num[0];
                i = readNum(d, i, num); float y = num[0];
                if (cmd == 'l') { cx += x; cy += y; } else { cx = x; cy = y; }
                p.lineTo(cx, cy + 960f); prev = cmd; break;
            }
            case 'H': case 'h': {
                i = readNum(d, i, num); float x = num[0];
                cx = cmd == 'h' ? cx + x : x;
                p.lineTo(cx, cy + 960f); prev = cmd; break;
            }
            case 'V': case 'v': {
                i = readNum(d, i, num); float y = num[0];
                cy = cmd == 'v' ? cy + y : y;
                p.lineTo(cx, cy + 960f); prev = cmd; break;
            }
            case 'Q': case 'q': {
                i = readNum(d, i, num); float x1 = num[0];
                i = readNum(d, i, num); float y1 = num[0];
                i = readNum(d, i, num); float x = num[0];
                i = readNum(d, i, num); float y = num[0];
                if (cmd == 'q') { qx = cx + x1; qy = cy + y1; cx += x; cy += y; }
                else { qx = x1; qy = y1; cx = x; cy = y; }
                p.quadTo(qx, qy + 960f, cx, cy + 960f); prev = cmd; break;
            }
            case 'T': case 't': {
                i = readNum(d, i, num); float x = num[0];
                i = readNum(d, i, num); float y = num[0];
                if (prev == 'Q' || prev == 'q' || prev == 'T' || prev == 't') { qx = 2 * cx - qx; qy = 2 * cy - qy; }
                else { qx = cx; qy = cy; }
                if (cmd == 't') { cx += x; cy += y; } else { cx = x; cy = y; }
                p.quadTo(qx, qy + 960f, cx, cy + 960f); prev = cmd; break;
            }
            case 'Z': case 'z': p.close(); cx = sx; cy = sy; prev = cmd; break;
            default: i++; break;
            }
        }
        return p;
    }

    private static int readNum(String s, int i, float[] out) {
        int n = s.length();
        while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' && !dot) { dot = true; i++; }
            else break;
        }
        out[0] = i > start ? Float.parseFloat(s.substring(start, i)) : 0f;
        return i;
    }
}
