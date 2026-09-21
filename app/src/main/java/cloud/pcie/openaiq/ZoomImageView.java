package cloud.pcie.openaiq;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public final class ZoomImageView extends ImageView {
    interface SwipeListener {
        void onSwipePrevious();
        void onSwipeNext();
    }

    private static final float MAX_SCALE = 5f;
    private final Matrix displayMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float baseScale = 1f;
    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private SwipeListener swipeListener;

    public ZoomImageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setScaleType(ScaleType.MATRIX);
        setSaveEnabled(false);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float desired = currentScale * detector.getScaleFactor();
                float bounded = Math.max(1f, Math.min(MAX_SCALE, desired));
                float factor = bounded / currentScale;
                currentScale = bounded;
                displayMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                constrainMatrix();
                setImageMatrix(displayMatrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (currentScale > 1.02f) {
                    resetZoom();
                } else {
                    float factor = 2f;
                    currentScale = factor;
                    displayMatrix.postScale(factor, factor, event.getX(), event.getY());
                    constrainMatrix();
                    setImageMatrix(displayMatrix);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent first, MotionEvent second, float velocityX, float velocityY) {
                if (currentScale > 1.02f || swipeListener == null || first == null || second == null) {
                    return false;
                }
                float distanceX = second.getX() - first.getX();
                if (Math.abs(distanceX) < getWidth() * 0.18f
                        || Math.abs(velocityX) < Math.abs(velocityY)) {
                    return false;
                }
                if (distanceX > 0) {
                    swipeListener.onSwipePrevious();
                } else {
                    swipeListener.onSwipeNext();
                }
                return true;
            }
        });
    }

    void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    boolean isZoomed() {
        return currentScale > 1.02f;
    }

    void resetZoom() {
        currentScale = 1f;
        configureBaseMatrix();
    }

    void showImageCentered(Uri uri) {
        currentScale = 1f;
        dragging = false;
        displayMatrix.reset();
        setImageMatrix(displayMatrix);
        setImageURI(null);
        setImageURI(uri);
        post(() -> {
            currentScale = 1f;
            configureBaseMatrix();
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && isZoomed()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                        dragging = true;
                        displayMatrix.postTranslate(dx, dy);
                        constrainMatrix();
                        setImageMatrix(displayMatrix);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!dragging && !scaleDetector.isInProgress()) {
                    performClick();
                }
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        configureBaseMatrix();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        currentScale = 1f;
        post(this::configureBaseMatrix);
    }

    private void configureBaseMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            return;
        }
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0f || contentHeight <= 0f) {
            return;
        }
        baseScale = Math.min(
                contentWidth / drawable.getIntrinsicWidth(),
                contentHeight / drawable.getIntrinsicHeight());
        float dx = getPaddingLeft()
                + (contentWidth - drawable.getIntrinsicWidth() * baseScale) / 2f;
        float dy = getPaddingTop()
                + (contentHeight - drawable.getIntrinsicHeight() * baseScale) / 2f;
        displayMatrix.reset();
        displayMatrix.postScale(baseScale, baseScale);
        displayMatrix.postTranslate(dx, dy);
        setImageMatrix(displayMatrix);
        invalidate();
    }

    private void constrainMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        displayMatrix.mapRect(bounds);
        float dx = 0f;
        float dy = 0f;
        if (bounds.width() <= getWidth()) {
            dx = getWidth() / 2f - bounds.centerX();
        } else if (bounds.left > 0) {
            dx = -bounds.left;
        } else if (bounds.right < getWidth()) {
            dx = getWidth() - bounds.right;
        }
        if (bounds.height() <= getHeight()) {
            dy = getHeight() / 2f - bounds.centerY();
        } else if (bounds.top > 0) {
            dy = -bounds.top;
        } else if (bounds.bottom < getHeight()) {
            dy = getHeight() - bounds.bottom;
        }
        displayMatrix.postTranslate(dx, dy);
    }
}
