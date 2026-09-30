package com.alma.mvp;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

public class AlmaAvatarView extends AppCompatImageView {

    private float headRotation = 0f;
    private float torsoRotation = 0f;
    private float breathScale = 1f;
    private float bodyShiftY = 0f;

    public AlmaAvatarView(Context context) {
        super(context);
    }

    public AlmaAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AlmaAvatarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public float getHeadRotation() {
        return headRotation;
    }

    public void setHeadRotation(float value) {
        headRotation = value;
        invalidate();
    }

    public float getTorsoRotation() {
        return torsoRotation;
    }

    public void setTorsoRotation(float value) {
        torsoRotation = value;
        invalidate();
    }

    public float getBreathScale() {
        return breathScale;
    }

    public void setBreathScale(float value) {
        breathScale = value;
        invalidate();
    }

    public float getBodyShiftY() {
        return bodyShiftY;
    }

    public void setBodyShiftY(float value) {
        bodyShiftY = value;
        invalidate();
    }

    public void resetPose() {
        headRotation = 0f;
        torsoRotation = 0f;
        breathScale = 1f;
        bodyShiftY = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();

        if (w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }

        float headEnd = h * 0.34f;
        float torsoEnd = h * 0.70f;

        // CABEZA
        canvas.save();
        canvas.clipRect(0f, 0f, w, headEnd + 4f);
        canvas.rotate(
                headRotation,
                w * 0.50f,
                h * 0.25f
        );
        super.onDraw(canvas);
        canvas.restore();

        // TORSO
        canvas.save();
        canvas.clipRect(
                0f,
                headEnd - 4f,
                w,
                torsoEnd + 4f
        );
        canvas.translate(0f, bodyShiftY);
        canvas.rotate(
                torsoRotation,
                w * 0.50f,
                h * 0.54f
        );
        canvas.scale(
                breathScale,
                breathScale,
                w * 0.50f,
                h * 0.52f
        );
        super.onDraw(canvas);
        canvas.restore();

        // PIERNAS / BASE
        canvas.save();
        canvas.clipRect(
                0f,
                torsoEnd - 4f,
                w,
                h
        );
        super.onDraw(canvas);
        canvas.restore();
    }
}
