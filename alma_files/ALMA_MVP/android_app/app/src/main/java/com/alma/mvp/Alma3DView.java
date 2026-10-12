package com.alma.mvp;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
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
    private LightManager frontalManager;
    private int frontalEntity;

    private boolean rendering = false;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!rendering) return;

                    if (modelViewer != null) {
                        
                        if (frontalManager != null) {
                            float[] d = modelViewer.getCamera().getForwardVector(null);
                            frontalManager.setDirection(
                                frontalManager.getInstance(frontalEntity),
                                d[0], d[1], d[2]
                            );
                        }
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
                            new com.google.android.filament.utils.Manipulator.Builder()
    .targetPosition(0f, 0f, -4f)
    .viewport(surfaceView.getWidth(), surfaceView.getHeight())
    .build(com.google.android.filament.utils.Manipulator.Mode.ORBIT)
                    );

            surfaceView.setOnTouchListener(modelViewer);

            int keyLight = EntityManager.get().create();
            new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(1.0f, 0.96f, 0.92f)
                    .intensity(35000.0f)
                    .direction(0.0f, -0.15f, -1.0f)
                    .castShadows(false)
                    .build(engine, keyLight);
            modelViewer.getScene().addEntity(keyLight);

            int fillLight = EntityManager.get().create();
            new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(0.78f, 0.86f, 1.0f)
                    .intensity(12000.0f)
                    .direction(-0.8f, -0.10f, -0.6f)
                    .castShadows(false)
                    .build(engine, fillLight);
            modelViewer.getScene().addEntity(fillLight);

            // Luz frontal para mejorar visibilidad del rostro
int faceLight = EntityManager.get().create();
new LightManager.Builder(LightManager.Type.DIRECTIONAL)
        .color(1.0f, 0.96f, 0.92f)
        .intensity(25000.0f)
        .direction(0.0f, -0.2f, -1.0f)
        .castShadows(false)
        .build(engine, faceLight);
modelViewer.getScene().addEntity(faceLight);
            frontalManager = engine.getLightManager();
            frontalEntity = faceLight;


modelViewer.getCamera().setExposure(
                    8.0f,
                    1.0f / 60.0f,
                    400.0f
            );

            modelViewer.getScene().setSkybox(
    new com.google.android.filament.Skybox.Builder()
        .color(0.65f, 0.68f, 0.72f, 1.0f)
        .build(engine)
);

modelViewer.setCameraFocalLength(53.0f);

            try (InputStream input =
                         getContext()
                                 .getAssets()
                                 .open("ALMA_ORIGINAL_COLOR.glb")) {

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

                modelViewer.setAutoPlayAnimations(true);

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
