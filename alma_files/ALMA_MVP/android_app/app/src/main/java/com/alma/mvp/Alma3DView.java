package com.alma.mvp;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.google.android.filament.Engine;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.utils.Utils;

import java.io.InputStream;
import java.nio.ByteBuffer;

public class Alma3DView extends FrameLayout {

    static {
        Utils.init();
    }

    private SurfaceView surfaceView;
    private ModelViewer modelViewer;
    private boolean rendering = false;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!rendering) return;

                    if (modelViewer != null) {
                        modelViewer.render(frameTimeNanos);
                    }

                    Choreographer.getInstance()
                            .postFrameCallback(this);
                }
            };

    public Alma3DView(Context context) {
        super(context);
        init(context);
    }

    public Alma3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public Alma3DView(
            Context context,
            AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        surfaceView = new SurfaceView(context);

        addView(
                surfaceView,
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                )
        );

        post(this::createViewer);
    }

    private void createViewer() {
        if (modelViewer != null) return;

        try {
            Engine engine = Engine.create();

            UiHelper uiHelper =
                    new UiHelper(
                            UiHelper.ContextErrorPolicy.DONT_CHECK
                    );

            modelViewer =
                    new ModelViewer(
                            surfaceView,
                            engine,
                            uiHelper,
                            null
                    );

            surfaceView.setOnTouchListener(modelViewer);

            try (InputStream input =
                         getContext()
                                 .getAssets()
                                 .open("models/alma_meshy_textured.glb")) {

                byte[] bytes = new byte[input.available()];

                int total = 0;
                while (total < bytes.length) {
                    int read =
                            input.read(
                                    bytes,
                                    total,
                                    bytes.length - total
                            );

                    if (read < 0) break;
                    total += read;
                }

                ByteBuffer buffer =
                        ByteBuffer.wrap(bytes);

                modelViewer.loadModelGlb(buffer);

                modelViewer.transformToUnitCube(
                        new Float3(
                                0f,
                                0f,
                                -4f
                        )
                );

                modelViewer.setAutoPlayAnimations(false);

            } catch (Exception e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setSpeaking(boolean speaking) {
        if (modelViewer == null) return;

        modelViewer.setAutoPlayAnimations(speaking);

        if (!speaking) {
            Animator animator =
                    modelViewer.getAnimator();

            if (animator != null
                    && animator.getAnimationCount() > 0) {

                animator.applyAnimation(
                        0,
                        0f
                );

                animator.updateBoneMatrices();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        rendering = true;

        Choreographer.getInstance()
                .postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        rendering = false;

        Choreographer.getInstance()
                .removeFrameCallback(frameCallback);

        super.onDetachedFromWindow();
    }
}
