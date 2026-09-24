package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class CyberBubbleDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path details = new Path();
    private final float scale;
    private final boolean user;
    private int alpha = 255;

    CyberBubbleDrawable(Context context, boolean user) {
        scale = DesignScale.factor(context);
        this.user = user;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor(user ? "#DA07375A" : "#E5082948"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(px(user ? 1.8f : 1.6f));
        borderPaint.setColor(Color.parseColor(user ? "#FF20DFFF" : "#E914B8ED"));
        detailPaint.setStyle(Paint.Style.STROKE);
        detailPaint.setStrokeWidth(px(1.2f));
        detailPaint.setStrokeCap(Paint.Cap.ROUND);
        detailPaint.setColor(Color.parseColor(user ? "#AA65F5FF" : "#773C9CE7"));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        fillPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                Color.parseColor(user ? "#F20A4058" : "#F2072947"),
                Color.parseColor(user ? "#F205223D" : "#F203172D"), Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float left = bounds.left + px(2f);
        float top = bounds.top + px(2f);
        float right = bounds.right - px(2f);
        float bottom = bounds.bottom - px(2f);
        float large = Math.min(px(20), Math.max(0, (bottom - top) / 4));
        float small = Math.min(px(8), large);

        outline.reset();
        if (user) {
            outline.moveTo(left + large, top);
            outline.lineTo(right - small, top);
            outline.lineTo(right, top + small);
            outline.lineTo(right, bottom - large);
            outline.lineTo(right - large, bottom);
            outline.lineTo(left + small, bottom);
            outline.lineTo(left, bottom - small);
            outline.lineTo(left, top + large);
        } else {
            outline.moveTo(left + small, top);
            outline.lineTo(right - large, top);
            outline.lineTo(right, top + large);
            outline.lineTo(right, bottom - small);
            outline.lineTo(right - small, bottom);
            outline.lineTo(left + large, bottom);
            outline.lineTo(left, bottom - large);
            outline.lineTo(left, top + small);
        }
        outline.close();
        canvas.drawPath(outline, fillPaint);
        canvas.drawPath(outline, borderPaint);

        details.reset();
        if (user) {
            details.moveTo(left + large + px(6), top + px(3));
            details.lineTo(right - px(24), top + px(3));
            details.moveTo(right - large - px(16), bottom - px(3));
            details.lineTo(right - large + px(2), bottom - px(3));
        } else {
            details.moveTo(left + px(3), top + small + px(5));
            details.lineTo(left + px(3), bottom - large - px(3));
            details.moveTo(left + large - px(2), bottom - px(3));
            details.lineTo(left + large + px(18), bottom - px(3));
        }
        canvas.drawPath(details, detailPaint);
    }

    @Override
    public void setAlpha(int value) {
        alpha = value;
        fillPaint.setAlpha(value);
        borderPaint.setAlpha(value);
        detailPaint.setAlpha(value);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        borderPaint.setColorFilter(colorFilter);
        detailPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private float px(float designPixels) {
        return designPixels * scale;
    }
}
