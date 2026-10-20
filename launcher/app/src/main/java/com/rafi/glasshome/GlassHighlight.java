package com.rafi.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The single focus highlight: a translucent "glass" plate that springs from one
 * focus target to the next. It draws only a rounded rect, so it's cheap to animate.
 */
final class GlassHighlight extends View {
    private final RectF cur = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private float curRadius, fromRadius, toRadius;
    private boolean placed;
    private ValueAnimator anim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient fillShader;
    private final LinearGradient strokeShader;
    private final Matrix shaderMatrix = new Matrix();

    GlassHighlight(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        // Unit-height gradients; a matrix stretches them to the current rect each frame.
        fillShader = new LinearGradient(0, 0, 0, 1, 0x4DFFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP);
        strokeShader = new LinearGradient(0, 0, 0, 1, 0xB3FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP);
        fill.setShader(fillShader);
        stroke.setShader(strokeShader);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * dp);
        setWillNotDraw(false);
    }

    void moveTo(RectF target, float radius, boolean animate) {
        if (anim != null) anim.cancel();
        if (!placed || !animate) {
            cur.set(target);
            curRadius = radius;
            placed = true;
            invalidate();
            return;
        }
        from.set(cur);
        fromRadius = curRadius;
        to.set(target);
        toRadius = radius;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(480);
        anim.setInterpolator(new SpringInterpolator(0.7, 7.5));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            cur.set(lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                    lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f));
            curRadius = lerp(fromRadius, toRadius, f);
            invalidate();
        });
        anim.start();
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        shaderMatrix.setScale(1f, cur.height());
        shaderMatrix.postTranslate(0, cur.top);
        fillShader.setLocalMatrix(shaderMatrix);
        strokeShader.setLocalMatrix(shaderMatrix);
        canvas.drawRoundRect(cur, curRadius, curRadius, fill);
        canvas.drawRoundRect(cur, curRadius, curRadius, stroke);
    }
}
