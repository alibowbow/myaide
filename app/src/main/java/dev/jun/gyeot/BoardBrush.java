package dev.jun.gyeot;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import org.json.JSONArray;

/** A continuous brush silhouette. Pen pressure controls its width; finger strokes use the angle of a broad nib. */
final class BoardBrush {
    private BoardBrush() {}

    static void draw(Canvas canvas, Paint ink, Path path, JSONArray points, JSONArray pressure, float width, float shown) {
        int count = Math.min(points.length()/2, Math.max(1, (int) Math.ceil(points.length()/2 * shown)));
        float x = (float) points.optDouble(0), y = (float) points.optDouble(1);
        ink.setStyle(Paint.Style.FILL);
        if (count == 1) {
            float weight = pressure == null ? .48f : .18f+.82f*(float)pressure.optDouble(0,60)/100;
            canvas.drawCircle(x,y,width*weight*.5f,ink);
            return;
        }
        path.reset();
        for (int side = 1; side >= -1; side -= 2) {
            float lastX=0, lastY=0;
            for (int j=0; j<count; j++) {
                int i = side == 1 ? j : count-1-j;
                x = (float) points.optDouble(i*2); y = (float) points.optDouble(i*2+1);
                int before = Math.max(0,i-1)*2, after = Math.min(count-1,i+1)*2;
                float dx = (float)(points.optDouble(after)-points.optDouble(before));
                float dy = (float)(points.optDouble(after+1)-points.optDouble(before+1));
                float length = Math.max(.01f,(float)Math.hypot(dx,dy));
                float weight = pressure != null ? .18f+.82f*(float)pressure.optDouble(i,60)/100
                        : .30f+.70f*Math.abs((dy*.866f+dx*.5f)/length);
                // Taper by position within the stroke, so both sides share the same profile.
                float taper = Math.min(1, .36f+.26f*Math.min(i,count-1-i));
                float half = width*.5f*weight*taper;
                float px=x-side*dy/length*half, py=y+side*dx/length*half;
                if (j==0) { if (side==1) path.moveTo(px,py); else path.lineTo(px,py); }
                else {
                    path.quadTo(lastX,lastY,(px+lastX)/2,(py+lastY)/2);
                    if (j==count-1) path.lineTo(px,py);
                }
                lastX=px;lastY=py;
            }
        }
        path.close(); canvas.drawPath(path,ink);
    }
}
