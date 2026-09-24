package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small static edge accents leave the live character unobstructed. */
final class AvatarPreviewFrame extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    AvatarPreviewFrame(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float d = density, inset = 2 * d, arm = 18 * d;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d);
        paint.setColor(0x703395BF);
        canvas.drawRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, paint);
        paint.setStrokeWidth(2 * d);
        paint.setColor(0xCB43DFFF);
        for (int xSide : new int[]{-1, 1}) {
            for (int ySide : new int[]{-1, 1}) {
                float x = xSide < 0 ? b.left + inset : b.right - inset;
                float y = ySide < 0 ? b.top + inset : b.bottom - inset;
                canvas.drawLine(x, y, x - xSide * arm, y, paint);
                canvas.drawLine(x, y, x, y - ySide * arm, paint);
            }
        }
        paint.setStrokeWidth(d);
        paint.setColor(0x806CADD5);
        for (int i = -3; i <= 3; i++) {
            float y = b.exactCenterY() + i * 10 * d;
            float length = (i == 0 ? 8 : 4) * d;
            canvas.drawLine(b.left + 6*d, y, b.left + 6*d + length, y, paint);
            canvas.drawLine(b.right - 6*d, y, b.right - 6*d - length, y, paint);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
