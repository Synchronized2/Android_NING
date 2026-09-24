package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.ImageView;

/** Square, center-cropped artwork with a separate clipped HUD rim. */
final class StylePreviewView extends ImageView {
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final float unit;

    StylePreviewView(Context context, int imageResource) {
        super(context);
        unit = DesignScale.referenceDp(context, 1);
        setScaleType(ScaleType.CENTER_CROP);
        setImageResource(imageResource);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int side = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(side, side);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = 2.5f * unit;
        float end = Math.min(w, h) - inset;
        float cut = Math.min(w * .13f, 6 * unit);
        outline.reset();
        outline.moveTo(inset + cut, inset);
        outline.lineTo(end - cut, inset);
        outline.lineTo(end, inset + cut);
        outline.lineTo(end, end - cut);
        outline.lineTo(end - cut, end);
        outline.lineTo(inset + cut, end);
        outline.lineTo(inset, end - cut);
        outline.lineTo(inset, inset + cut);
        outline.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(outline);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
        rim.setStyle(Paint.Style.STROKE);
        if (isSelected()) {
            rim.setColor(0x3532EFFF);
            rim.setStrokeWidth(5 * unit);
            canvas.drawPath(outline, rim);
            rim.setColor(0x8032DEFF);
            rim.setStrokeWidth(3 * unit);
            canvas.drawPath(outline, rim);
        }
        rim.setColor(isSelected() ? 0xFF66F6FF : 0xFF2D78B9);
        rim.setStrokeWidth((isSelected() ? 1.5f : .7f) * unit);
        canvas.drawPath(outline, rim);
    }

    @Override public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }
}
