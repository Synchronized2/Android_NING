package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public final class CyberEffectsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean listening;
    private boolean processing;

    public CyberEffectsView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void setVoiceState(boolean listening, boolean processing) {
        this.listening = listening;
        this.processing = processing;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float seconds = SystemClock.uptimeMillis() / 1000f;
        String mode = String.valueOf(getTag());
        if ("mic".equals(mode)) {
            drawMic(canvas, seconds);
        } else if ("assistantPanel".equals(mode)) {
            drawAssistantPanel(canvas, seconds);
        } else {
            drawStage(canvas, seconds);
        }
        if (isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(listening || processing ? 33 : 65);
        }
    }

    private void drawStage(Canvas canvas, float seconds) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x12020a16, 0x06020a16, 0x18000814}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        float scanY = (seconds * dp(34)) % Math.max(h, 1);
        paint.setShader(new LinearGradient(0, scanY - dp(30), 0, scanY + dp(30),
                new int[]{0x0000dfff, 0x0d00dfff, 0x0000dfff}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, scanY - dp(30), w, scanY + dp(30), paint);
        paint.setShader(null);
    }

    private void drawMic(Canvas canvas, float seconds) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getHeight() * .33f, dp(30));
        float breath = .5f + .5f * (float) Math.sin(seconds * (float) Math.PI * 2f / 2.1f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * 1.36f,
                new int[]{0x47008cff, 0x270066cc, 0x000066cc},
                new float[]{0f, .64f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 1.36f, paint);
        paint.setShader(null);

        if (listening) {
            drawWaveform(canvas, cx, cy, radius, seconds);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * .88f,
                new int[]{0xff062c57, 0xff051a3b, 0xff030e26},
                new float[]{0f, .72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * .88f, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0x260083ff);
        canvas.drawCircle(cx, cy, radius * .96f, paint);
        paint.setStrokeWidth(dp(2.8f));
        paint.setColor(0x5300bcff);
        canvas.drawCircle(cx, cy, radius * .80f, paint);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(0xff26e8ff);
        canvas.drawCircle(cx, cy, radius * .81f, paint);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(0xb50083ff);
        canvas.drawCircle(cx, cy, radius * .72f, paint);

        float segmentRotation = processing ? seconds * 100f : listening ? seconds * 32f : seconds * 7f;
        RectF segments = circleBounds(cx, cy, radius * .98f);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(dp(3));
        for (int i = 0; i < 8; i++) {
            paint.setColor(i % 3 == 0 ? 0xe426e8ff : 0xa000a8ff);
            canvas.drawArc(segments, segmentRotation + i * 45f - 88f, 32f, false, paint);
        }
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x8f008cff);
        canvas.drawCircle(cx, cy, radius * 1.04f, paint);

        RectF scanner = circleBounds(cx, cy, radius * 1.12f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(0x4000bbff);
        canvas.drawArc(scanner, -40f, 64f, false, paint);
        canvas.drawArc(scanner, 140f, 64f, false, paint);
        paint.setStrokeWidth(dp(.9f));
        paint.setColor(0xc953f4ff);
        canvas.drawArc(scanner, -40f, 64f, false, paint);
        canvas.drawArc(scanner, 140f, 64f, false, paint);

        if (listening) {
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0x7053f4ff);
            canvas.drawCircle(cx, cy, radius * (.85f + breath * .10f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWaveform(Canvas canvas, float cx, float cy, float radius, float seconds) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(1.2f));
        float start = radius * 1.20f;
        float step = dp(2.4f);
        int count = Math.max(0, (int) ((getWidth() / 2f - start - dp(2)) / step));
        for (int i = 0; i < count; i++) {
            float envelope = (float) Math.sin(Math.PI * (i + 1f) / (count + 1f));
            float movement = .4f + .6f * Math.abs((float) Math.sin(seconds * 8f + i * .68f));
            float halfHeight = dp(2) + dp(12) * envelope * movement;
            paint.setColor(i % 3 == 0 ? 0xdd43eaff : 0x92008cff);
            float offset = start + step * i;
            canvas.drawLine(cx - offset, cy - halfHeight, cx - offset, cy + halfHeight, paint);
            canvas.drawLine(cx + offset, cy - halfHeight, cx + offset, cy + halfHeight, paint);
        }
    }

    private void drawAssistantPanel(Canvas canvas, float seconds) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pulse = .5f + .5f * (float) Math.sin(seconds * (float) Math.PI * 2f / 2.4f);
        float strength = listening ? 1f : processing ? .82f : .48f + .18f * pulse;
        float scale = listening ? .99f + .025f * pulse : .995f + .01f * pulse;

        canvas.save();
        canvas.scale(scale, scale, w / 2f, h / 2f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(28));
        paint.setShader(new LinearGradient(0, dp(13), 0, dp(42),
                new int[]{0xff4c91ed, 0xff1a376c}, null, Shader.TileMode.CLAMP));
        canvas.drawText("N", dp(14), dp(39), paint);
        paint.setShader(null);
        paint.setTextSize(dp(10));
        paint.setColor(0xff3975c6);
        canvas.drawText("NING", dp(14), dp(52), paint);

        drawAssistantWave(canvas, w, dp(70), seconds, strength);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        paint.setColor(withAlpha(0x001bdcff, .72f + .28f * pulse));
        paint.setTextSize(dp(15));
        canvas.drawText("AI", dp(14), dp(96), paint);
        paint.setColor(withAlpha(0x003f87d7, .72f + .18f * pulse));
        paint.setTextSize(dp(7));
        canvas.drawText("ASSISTANT", dp(14), dp(108), paint);
        canvas.restore();
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawAssistantWave(Canvas canvas, float width, float cy, float seconds, float strength) {
        float left = dp(14);
        float right = Math.min(width - dp(8), left + dp(66));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(0x800087ff);
        canvas.drawLine(left, cy, right, cy, paint);
        int bars = 27;
        float step = (right - left) / (bars - 1f);
        for (int i = 0; i < bars; i++) {
            float envelope = .22f + .78f * (float) Math.sin(Math.PI * i / (bars - 1f));
            float wave = .25f + .75f * Math.abs((float) Math.sin(seconds *
                    (listening ? 8f : processing ? 5f : 2.7f) + i * .73f));
            float halfHeight = dp(.8f + 7f * envelope * wave * strength);
            paint.setColor(i % 4 == 0 ? 0xe52eeaff : 0xa00087ff);
            float x = left + i * step;
            canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, paint);
        }
    }

    private int withAlpha(int rgb, float alpha) {
        return (Math.max(0, Math.min(255, (int) (alpha * 255f))) << 24) | (rgb & 0x00ffffff);
    }

    private RectF circleBounds(float cx, float cy, float radius) {
        return new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
