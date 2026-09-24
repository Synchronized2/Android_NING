package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Resolution-independent HUD surfaces; all geometry uses the same design ruler. */
final class CyberPanelDrawable extends Drawable {
    enum Kind { DOCK, INPUT, ACTION, SEND, TAB, SECTION, CHOICE, PRIMARY }

    private final Kind kind;
    private final float scale;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private LinearGradient gradient;
    private boolean highlighted;
    private boolean pressed;
    private int opacity = 255;

    CyberPanelDrawable(Context context, Kind kind) {
        this.kind = kind;
        scale = DesignScale.factor(context);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        updateGradient();
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] states) {
        boolean nextHighlight = kind == Kind.SEND || kind == Kind.PRIMARY;
        boolean nextPressed = false;
        for (int state : states) {
            nextHighlight |= state == android.R.attr.state_selected
                    || (kind == Kind.INPUT && state == android.R.attr.state_focused);
            nextPressed |= state == android.R.attr.state_pressed;
        }
        highlighted = nextHighlight;
        pressed = nextPressed;
        updateGradient();
        invalidateSelf();
        return true;
    }

    private void updateGradient() {
        boolean bright = highlighted || pressed || kind == Kind.SEND;
        if (kind == Kind.SECTION) {
            gradient = new LinearGradient(0, getBounds().top, 0, Math.max(getBounds().top + 1, getBounds().bottom),
                    0xDA041E3C, 0xDB020C22, Shader.TileMode.CLAMP);
            return;
        }
        if (kind == Kind.PRIMARY || (kind == Kind.CHOICE && bright)) {
            gradient = new LinearGradient(0, getBounds().top, 0, Math.max(getBounds().top + 1, getBounds().bottom),
                    new int[]{0xFF063A65, 0xFF087EAC, 0xFF063D64}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
            return;
        }
        gradient = new LinearGradient(0, getBounds().top, 0, Math.max(getBounds().top + 1, getBounds().bottom),
                Color.parseColor(bright ? "#123F62" : "#0A233B"),
                Color.parseColor(bright ? "#05243E" : "#031321"), Shader.TileMode.CLAMP);
    }

    private void outline(float inset) {
        Rect b = getBounds();
        float l = b.left + inset, t = b.top + inset;
        float r = b.right - inset, bottom = b.bottom - inset;
        float cut = Math.min(18 * scale, Math.max(0, (bottom - t) / 4));
        path.reset();
        path.moveTo(l + cut, t);
        path.lineTo(r - cut, t);
        path.lineTo(r, t + cut);
        path.lineTo(r, bottom - cut);
        path.lineTo(r - cut, bottom);
        path.lineTo(l + cut, bottom);
        path.lineTo(l, bottom - cut);
        path.lineTo(l, t + cut);
        path.close();
    }

    @Override public void draw(Canvas canvas) {
        boolean bright = highlighted || pressed || kind == Kind.SEND || kind == Kind.PRIMARY;
        outline(5 * scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(gradient);
        paint.setAlpha(opacity);
        canvas.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        // Several translucent strokes give a small halo without software blur layers.
        paint.setColor(bright ? 0x3037DBFF : 0x10168DCF);
        paint.setStrokeWidth(9 * scale);
        canvas.drawPath(path, paint);
        paint.setColor(bright ? 0xE543DDFF : 0xAD237EAA);
        paint.setStrokeWidth((bright ? (kind == Kind.CHOICE || kind == Kind.PRIMARY ? 3.2f : 1.8f) : 1.2f) * scale);
        canvas.drawPath(path, paint);
        outline(10 * scale);
        paint.setColor(bright ? 0x6839BEEB : 0x302D80A6);
        paint.setStrokeWidth(scale);
        canvas.drawPath(path, paint);

        Rect b = getBounds();
        paint.setColor(bright ? 0xF061E9FF : 0xA532B5DE);
        paint.setStrokeWidth(2 * scale);
        float x = b.left + 24 * scale, y = b.top + 5 * scale;
        canvas.drawLine(x, y, Math.min(b.right - 24 * scale, x + 60 * scale), y, paint);
        x = b.right - 24 * scale;
        y = b.bottom - 5 * scale;
        canvas.drawLine(Math.max(b.left + 24 * scale, x - 36 * scale), y, x, y, paint);
        if (kind == Kind.SECTION || kind == Kind.PRIMARY) {
            paint.setStrokeWidth(3 * scale);
            paint.setColor(0xDB1BD8FF);
            canvas.drawLine(b.left + 5*scale, b.top + 30*scale, b.left + 5*scale, b.top + 62*scale, paint);
            canvas.drawLine(b.right - 5*scale, b.bottom - 30*scale, b.right - 5*scale, b.bottom - 62*scale, paint);
        }
    }

    @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
