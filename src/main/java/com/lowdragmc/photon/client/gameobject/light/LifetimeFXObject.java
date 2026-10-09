package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/** An FX object that runs for a lifetime, or loops, and serves its level something every frame while it does. */
@ParametersAreNonnullByDefault
public abstract class LifetimeFXObject extends FXObject {
    /** Age in ticks since the start delay elapsed; {@code < 0} until the first tick. */
    protected float age = -1;
    protected float seed;
    @Nullable
    private Level registeredLevel;

    protected abstract int lifetime();

    protected abstract boolean looping();

    protected abstract void attach(Level level);

    protected abstract void detach(Level level);

    /** Drops the timeline overrides. */
    protected abstract void clearRuntime();

    @Override
    public void reset() {
        super.reset();
        age = -1;
        clearRuntime();
    }

    @Override
    public void updateTick(float dt) {
        super.updateTick(dt);
        if (age < 0) {
            age = 0;
            seed = random.nextFloat();
        } else {
            age += dt;
        }
        register(getLevel());
        if (!looping() && age >= lifetime()) {
            remove(false);
        }
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        register(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        register(null);
    }

    private void register(@Nullable Level level) {
        if (level == registeredLevel) return;
        if (registeredLevel != null) detach(registeredLevel);
        if (level != null) attach(level);
        registeredLevel = level;
    }

    /** Pending through the start delay, then running for its lifetime. */
    protected boolean isRunning() {
        return !removed && isActive() && (age < 0 || looping() || age < lifetime());
    }

    @Override
    public boolean isAlive() {
        return isRunning() || super.isAlive();
    }

    @Override
    public boolean isPlaying() {
        return isRunning() || super.isPlaying();
    }

    /** Started, running, shown, and not wiped along with its FX. */
    protected boolean isShowing() {
        return age >= 0 && isRunning() && isVisible() && !isDiscarded();
    }

    /** Ticks since it started, between ticks too. */
    protected float time(float partialTick) {
        return Math.max(age, 0) + partialTick * timeScale();
    }

    /** {@code time} as a share of the lifetime, wrapping when looping. */
    protected float lifetimeT(float time) {
        int lifetime = Math.max(1, lifetime());
        return looping() ? (time % lifetime) / lifetime : Math.min(time / lifetime, 1f);
    }

    /** Lines drawn over the scene (no depth test): a light's reach shows through walls. 26.1's line types all
     *  depth-test. */
    private static final RenderType GIZMO_LINES = RenderType.create("photon_gizmo_lines",
            RenderSetup.builder(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Photon.id("pipeline/gizmo_lines"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build()).createRenderSetup());

    /** Editor gizmo lines in the object's space, drawn over the scene. */
    protected void drawGizmo(MultiBufferSource bufferSource, List<Pair<Vector3f, Vector3f>> edges, int color) {
        if (edges.isEmpty()) return;
        var poseStack = new PoseStack();
        poseStack.mulPose(transform().localToWorldMatrix());
        RenderBufferUtils.drawEdges(poseStack, bufferSource.getBuffer(GIZMO_LINES), edges, color, 5);
    }
}
