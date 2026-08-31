package com.remouse.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 全屏高亮浮层
 * 在光标悬停的可点击元素外框上画一圈描边，让用户在按 OK 之前就知道会点到哪个元素。
 * 与光标浮层分开是因为光标窗口只有光标那么大，画不下元素外框。
 */
public class HighlightOverlayView extends View {

    // 跟光标一样走中性色：一层很淡的白压在元素上，像薄玻璃，不跟界面本身的配色打架
    private static final int STROKE_COLOR = 0xEBFFFFFF; // 白 92%
    private static final int FILL_COLOR = 0x21FFFFFF;   // 白 13%
    private static final int EDGE_COLOR = 0x59000000;   // 外侧暗边，压在亮背景上也分得清

    private final Paint strokePaint;
    private final Paint fillPaint;
    private final Paint edgePaint;

    private final RectF current = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();

    private boolean hasTarget = false;
    private boolean isScrollMode = false;
    private float cornerRadius;

    private ValueAnimator moveAnimator;

    public HighlightOverlayView(Context context) {
        super(context);

        float density = context.getResources().getDisplayMetrics().density;
        cornerRadius = 12f * density;

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f * density);
        strokePaint.setColor(STROKE_COLOR);

        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(0.5f * density);
        edgePaint.setColor(EDGE_COLOR);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FILL_COLOR);
    }

    public void setScrollMode(boolean scrollMode) {
        if (this.isScrollMode != scrollMode) {
            this.isScrollMode = scrollMode;
            invalidate();
        }
    }

    /** 设置当前悬停元素的屏幕坐标外框；传 null 表示光标下没有可点元素 */
    public void setTarget(Rect bounds) {
        if (bounds == null) {
            if (hasTarget) {
                hasTarget = false;
                cancelAnimator();
                invalidate();
            }
            return;
        }

        to.set(bounds);
        if (!hasTarget) {
            hasTarget = true;
            current.set(to);
            invalidate();
            return;
        }

        if (current.equals(to)) return;

        // 在两个元素之间平滑滑动，避免高亮框生硬跳变
        from.set(current);
        cancelAnimator();
        moveAnimator = ValueAnimator.ofFloat(0f, 1f);
        moveAnimator.setDuration(140);
        moveAnimator.setInterpolator(new DecelerateInterpolator());
        moveAnimator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            current.set(
                    from.left + (to.left - from.left) * f,
                    from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f,
                    from.bottom + (to.bottom - from.bottom) * f);
            invalidate();
        });
        moveAnimator.start();
    }

    private void cancelAnimator() {
        if (moveAnimator != null && moveAnimator.isRunning()) {
            moveAnimator.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasTarget) return;

        canvas.drawRoundRect(current, cornerRadius, cornerRadius, fillPaint);
        canvas.drawRoundRect(current, cornerRadius, cornerRadius, strokePaint);
        canvas.drawRoundRect(current, cornerRadius, cornerRadius, edgePaint);
    }

    public void cleanup() {
        cancelAnimator();
    }
}
