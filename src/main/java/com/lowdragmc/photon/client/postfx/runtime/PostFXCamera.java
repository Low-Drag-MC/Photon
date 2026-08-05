package com.lowdragmc.photon.client.postfx.runtime;

import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * The render camera a post-processing pass reconstructs world space with — captured while the frame is
 * being rendered, replayed at dispatch.
 *
 * <p><b>Why a snapshot and not {@code RenderSystem}:</b> a fullscreen pass is a bare quad blit.
 * {@code ShaderInstance.apply()} does <b>not</b> set {@code ModelViewMat}/{@code ProjMat} (only
 * {@code setDefaultUniforms} does, which no blit path calls), and {@code KGBuiltinUniforms} derives its
 * {@code kg_I*Mat} inverses from {@code RenderSystem} — which by the time the chain runs is no longer the
 * camera: {@code LevelRenderer.renderLevel} pops the camera rotation off the model-view stack before
 * returning, so the {@code FXCompositeMode.LATE} / {@code onLevelRenderComplete} slot (also the shader-pack
 * slot) would see an identity view. Reading those uniforms in a pass would be silently wrong rather than
 * obviously broken.</p>
 *
 * <p>{@link #bind(ShaderInstance)} writes the snapshot over whatever {@code KGBuiltinUniforms} staged — the
 * same "override after the generic bind" pattern {@code ShaderGraphMaterial} uses for particle materials,
 * and it likewise fixes {@code kg_CameraBlockPos}/{@code kg_CameraOffset}, which that generic bind takes
 * from the GAME camera (wrong for the editor scene).</p>
 *
 * <p>There are two independent slots, keyed exactly like the effect stacks
 * ({@code PostEffectStack.currentSink()}): the world's and the editor scene's. The editor's preview scene
 * renders after the level in the same frame, so a single slot would leave whichever ran last in place — the
 * per-context split makes dispatch order irrelevant.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PostFXCamera {

    private static final Snapshot WORLD = new Snapshot();
    private static final Snapshot EDITOR_SCENE = new Snapshot();

    private PostFXCamera() {}

    /**
     * Snapshot the camera the <b>level</b> is being rendered with. {@code modelView} must be the matrix the
     * frame's draws see as {@code ModelViewMat} (camera rotation, camera at the origin — 1.21 renders
     * camera-relative), and {@code cameraPosition} the absolute world position it is relative to.
     *
     * <p>The level always fills the frame the chain runs over, so the projected rect is the whole target.
     * Deliberately NOT read from the GL viewport here: under a shader pack the bound target at this point is
     * the pack's, whose size need not match the main target the chain input is copied from.</p>
     */
    public static void capture(Matrix4f modelView, Matrix4f projection, Vec3 cameraPosition) {
        WORLD.set(modelView, projection, cameraPosition);
        WORLD.fullRect();
    }

    /**
     * Snapshot the <b>editor scene's</b> camera. Same contract as {@link #capture}, except the scene occupies
     * a sub-viewport of the frame (the editor draws it into a widget), so the rect is read from the live GL
     * viewport — call this while the scene's camera is set up.
     */
    public static void captureSubViewport(Matrix4f modelView, Matrix4f projection, Vec3 cameraPosition) {
        EDITOR_SCENE.set(modelView, projection, cameraPosition);
        EDITOR_SCENE.viewportRect();
    }

    /** Stage the current context's camera on a pass shader (only the uniforms it declared are touched). */
    public static void bind(ShaderInstance shader) {
        current().bind(shader);
    }

    /**
     * Stage {@code U_ViewPort} for a pass drawing into a {@code passWidth × passHeight} target: the rect the
     * captured camera projects into, rescaled to that target (a half-res pass gets a half-res rect). Screen
     * -space nodes remap through it, so World To Screen UV / Screen To World stay correct in the editor,
     * where the scene occupies only part of the frame the chain runs over.
     */
    public static void bindViewport(ShaderInstance shader, int passWidth, int passHeight) {
        current().bindViewport(shader, passWidth, passHeight);
    }

    private static Snapshot current() {
        return PostEffectStack.isEditorSceneRendering() ? EDITOR_SCENE : WORLD;
    }

    /** One context's camera. Starts as identity/whole-frame, so a pass dispatched before anything was
     *  captured degrades to a harmless no-op transform instead of a singular matrix. */
    private static final class Snapshot {
        private final Matrix4f view = new Matrix4f();
        private final Matrix4f projection = new Matrix4f();
        private final Matrix4f inverseView = new Matrix4f();
        private final Matrix4f inverseProjection = new Matrix4f();
        private Vec3 position = Vec3.ZERO;
        /** The inverses are only needed by passes that declared them — invert lazily, once per capture. */
        private boolean inversesDirty = true;
        /** The rect this camera projects into, as a fraction of the frame (x, y, width, height). */
        private float rectX, rectY, rectWidth = 1f, rectHeight = 1f;

        void set(Matrix4f modelView, Matrix4f projectionMatrix, Vec3 cameraPosition) {
            view.set(modelView);
            projection.set(projectionMatrix);
            position = cameraPosition;
            inversesDirty = true;
        }

        void fullRect() {
            rectX = rectY = 0f;
            rectWidth = rectHeight = 1f;
        }

        /** The GL viewport as a fraction of the main target (the size the effect chain runs at). */
        void viewportRect() {
            var target = Minecraft.getInstance().getMainRenderTarget();
            float width = target.width, height = target.height;
            int viewportWidth = GlStateManager.Viewport.width(), viewportHeight = GlStateManager.Viewport.height();
            if (width <= 0 || height <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
                fullRect();
                return;
            }
            rectX = GlStateManager.Viewport.x() / width;
            rectY = GlStateManager.Viewport.y() / height;
            rectWidth = viewportWidth / width;
            rectHeight = viewportHeight / height;
        }

        void bind(ShaderInstance shader) {
            if (inversesDirty) {
                view.invert(inverseView);
                projection.invert(inverseProjection);
                inversesDirty = false;
            }
            // 1.21.1 renders camera-relative, so the view matrix IS the model-view (see KGBuiltinUniforms).
            setMatrix(shader, "ModelViewMat", view);
            setMatrix(shader, "kg_ViewMat", view);
            setMatrix(shader, "ProjMat", projection);
            setMatrix(shader, "kg_IViewMat", inverseView);
            setMatrix(shader, "kg_IModelViewMat", inverseView);
            setMatrix(shader, "kg_IProjMat", inverseProjection);

            Uniform blockPos = shader.getUniform("kg_CameraBlockPos");
            Uniform offset = shader.getUniform("kg_CameraOffset");
            if (blockPos != null || offset != null) {
                // KilaGraph's precision-split camera position: camPos = block - offset (jitter-free far out).
                double bx = Math.floor(position.x), by = Math.floor(position.y), bz = Math.floor(position.z);
                if (blockPos != null) blockPos.set((float) bx, (float) by, (float) bz);
                if (offset != null) {
                    offset.set((float) (bx - position.x), (float) (by - position.y), (float) (bz - position.z));
                }
            }
        }

        void bindViewport(ShaderInstance shader, int passWidth, int passHeight) {
            Uniform uniform = shader.getUniform(PhotonShaderCompiler.VIEWPORT);
            if (uniform == null) return;
            uniform.set(rectX * passWidth, rectY * passHeight, rectWidth * passWidth, rectHeight * passHeight);
        }

        private static void setMatrix(ShaderInstance shader, String name, Matrix4f value) {
            Uniform uniform = shader.getUniform(name);
            if (uniform != null) uniform.set(value);
        }
    }
}
