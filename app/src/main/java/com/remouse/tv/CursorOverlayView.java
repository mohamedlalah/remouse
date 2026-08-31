package com.remouse.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 浮动光标
 *
 * 视觉走 iPadOS 指针那一路：中性色、不发光、静止不动。
 * 深色半透明填充配一圈白描边，一暗一明两层，无论压在亮背景还是暗背景上都有一条边能看见。
 * 滚动模式不换颜色，改用圆内的上下箭头区分——形状比颜色更安静。
 */
public class CursorOverlayView extends View {

    private static final int FILL_COLOR = 0x750A0B0D;   // #0A0B0D 46%
    private static final int STROKE_COLOR = 0xDBFFFFFF; // 白 86%
    private static final int SHADOW_COLOR = 0x6B000000; // 黑 42%
    private static final int GLYPH_COLOR = 0xE6FFFFFF;

    private final Paint fillPaint;
    private final Paint strokePaint;
    private final Paint glyphPaint;
    private final Paint ripplePaint;

    private final float density;

    private boolean isScrollMode = false;
    private float rippleRadius = 0f;
    private float rippleAlpha = 0f;

    private ValueAnimator rippleAnimator;

    public CursorOverlayView(Context context) {
        super(context);

        density = context.getResources().getDisplayMetrics().density;

        // 阴影要靠软件层绘制
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FILL_COLOR);
        fillPaint.setShadowLayer(10f * density, 0, 2f * density, SHADOW_COLOR);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f * density);
        strokePaint.setColor(STROKE_COLOR);

        glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(GLYPH_COLOR);

        ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(2f * density);
        ripplePaint.setColor(STROKE_COLOR);
    }

    public void setScrollMode(boolean scrollMode) {
        if (this.isScrollMode != scrollMode) {
            this.isScrollMode = scrollMode;
            invalidate();
        }
    }

    public boolean isScrollMode() {
        return isScrollMode;
    }

    public void showClickRipple() {
        if (rippleAnimator != null && rippleAnimator.isRunning()) {
            rippleAnimator.cancel();
        }

        rippleAnimator = ValueAnimator.ofFloat(0f, 1f);
        rippleAnimator.setDuration(320);
        rippleAnimator.setInterpolator(new DecelerateInterpolator());
        rippleAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            float base = baseRadius();
            rippleRadius = base + base * 0.55f * fraction;
            rippleAlpha = 1f - fraction;
            invalidate();
        });
        rippleAnimator.start();
    }

    /** 光标圆的半径：留出描边和阴影的余量 */
    private float baseRadius() {
        return Math.min(getWidth(), getHeight()) / 2f - 5f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = baseRadius();
        if (r <= 0) return;

        canvas.drawCircle(cx, cy, r, fillPaint);
        canvas.drawCircle(cx, cy, r, strokePaint);

        if (isScrollMode) {
            float arrow = r * 0.26f;
            float offset = r * 0.46f;

            canvas.save();
            canvas.translate(cx, cy - offset);
            canvas.drawPath(arrowPath(arrow, true), glyphPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(cx, cy + offset);
            canvas.drawPath(arrowPath(arrow, false), glyphPaint);
            canvas.restore();
        }

        if (rippleAlpha > 0) {
            ripplePaint.setAlpha((int) (rippleAlpha * 200));
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint);
        }
    }

    private Path arrowPath(float size, boolean pointUp) {
        Path path = new Path();
        if (pointUp) {
            path.moveTo(0, -size);
            path.lineTo(size, size * 0.55f);
            path.lineTo(-size, size * 0.55f);
        } else {
            path.moveTo(0, size);
            path.lineTo(size, -size * 0.55f);
            path.lineTo(-size, -size * 0.55f);
        }
        path.close();
        return path;
    }

    public void cleanup() {
        if (rippleAnimator != null) rippleAnimator.cancel();
    }
}
