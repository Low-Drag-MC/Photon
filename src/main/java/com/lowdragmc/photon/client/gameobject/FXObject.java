package com.lowdragmc.photon.client.gameobject;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.fx.ParticleTickHost;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@Getter
public abstract class FXObject extends Particle implements IFXObject {
    @Setter
    @Configurable(name = "photon.fx_object.name")
    public String name = name();
    @Nullable
    private IScene scene;
    @Accessors(fluent = true)
    @Configurable(name = "FXObject.transform", subConfigurable = true, collapse = false)
    public final Transform transform = new Transform(this);
    // runtime
    @Getter
    private long lastTick;
    @Getter
    private float lastTickTime;
    @Getter
    private float deltaTime = 0;
    @Setter
    private int delay = 0;
    /** Timeline active flag (this node only; see hierarchical {@link #isActive()}). Default active. */
    @Setter
    protected boolean selfActive = true;
    /** Timeline playback-speed flag (this node only; see hierarchical {@link #timeScale()}). Default 1. */
    @Setter
    protected float selfTimeScale = 1;
    /** Max sim sub-steps per tick (bounds cost at extreme speed; speed>this is capped per tick). */
    private static final int MAX_SUBSTEPS = 16;
    /** Timeline visibility flag (this node only; folded into hierarchical {@link #isVisible()}). */
    @Setter
    protected boolean selfTimelineVisible = true;
    /**
     * Optional per-tick callback used by {@link com.lowdragmc.photon.client.fx.FXRuntime} to drive
     * the timeline from the always-on root object. Runs after the start delay, before {@code updateTick}.
     */
    @Setter
    @Nullable
    protected Runnable onUpdateTick;
    /**
     * Optional per-frame callback (with partialTicks) used by {@code FXRuntime} to drive smooth,
     * interpolated timeline animation from the always-on root. Runs before the {@code isActive()} gate.
     */
    @Setter
    @Nullable
    protected Consumer<Float> onUpdateFrame;
    @Setter
    protected boolean hasPhysics = false;
    @Nullable
    private Level realLevel;
    @Setter
    protected boolean selfVisible = true;
    @Nullable
    @Getter
    protected IEffectExecutor effectExecutor;
    /**
     * Runtime wiring (set by {@code FXRuntime}, not persisted, not touched by {@link #reset()}): while
     * it returns true the particle engine must retain this object even if it is otherwise done — used
     * to keep timeline objects available for future clip restarts and the root alive to drive the clock.
     */
    @Setter
    @Nullable
    protected BooleanSupplier keepAlive;
    /**
     * Heartbeat wiring for {@code FXRuntime.isValid()} (set on the ROOT object only): every actual
     * tick records the host's tick counter into {@link #lastHostTick}; a stale reading means the
     * engine no longer ticks this runtime (it was discarded without notice).
     */
    @Setter
    @Nullable
    protected ParticleTickHost tickHost;
    @Setter
    protected long lastHostTick;

    protected FXObject() {
        super(null, 0, 0, 0);
        this.hasPhysics = false;
        this.friction = 1;
    }

    @Override
    public final IFXObject copy(boolean deep) {
        var copied = IFXObject.super.copy(deep);
        if (!deep) {
            copied.setName(name);
            copied.copyTransformFrom(this);
        }
        return copied;
    }

    @Override
    public void setEffect(IEffectExecutor effectExecutor) {
        this.effectExecutor = effectExecutor;
        random.setSeed(effectExecutor.getRandomSource().nextLong());
    }

    /** Reseed this object's RNG (used by control-track clips on restart). */
    public void setRandomSeed(long seed) {
        random.setSeed(seed);
    }

    @Override
    public final void setSceneInternal(IScene scene) {
        this.scene = scene;
    }

    /**
     * Vanilla-engine retention contract: "should the particle engine keep ticking me?". True while the
     * runtime keeps this object for the timeline ({@link #keepAlive}) or any child is still alive.
     * NOT the "is the FX done" signal — that is {@link #isPlaying()} (an object retained only by
     * keep-alive, e.g. deactivated during a clip gap, is alive but not playing).
     */
    @Override
    public boolean isAlive() {
        if (keepAlive != null && keepAlive.getAsBoolean()) {
            return true;
        }
        for (var child : transform.children()) {
            if (child.sceneObject() instanceof FXObject fxObject && fxObject.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPlaying() {
        if (!isActive()) {
            return false;
        }
        for (var child : transform.children()) {
            if (child.sceneObject() instanceof IFXObject fxObject && fxObject.isPlaying()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset() {
        this.delay = 0;
        this.age = 0;
        this.removed = false;
        this.onGround = false;
        // restore timeline defaults; the player re-applies gating each tick for controlled objects.
        // (objects that stop being timeline-controlled must fall back to active/visible.)
        this.selfActive = true;
        this.selfTimelineVisible = true;
        this.selfTimeScale = 1;
    }

    @Nullable
    @Override
    public Level getLevel() {
        return realLevel == null ? super.level : realLevel;
    }

    @Override
    public void setLevel(@Nullable Level level) {
        this.realLevel = level;
    }

    @Override
    public void move(double x, double y, double z) {
    }

    @Override
    protected int getLightColor(float partialTick) {
        if (this.realLevel == null) {
            return 0;
        }
        var pos = transform.position();
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
        return this.realLevel.isLoaded(blockPos) ? LevelRenderer.getLightColor(this.realLevel, blockPos) : 0;
    }

    /**
     * Contract: {@code force} means "drop every visible remnant immediately"; non-force means "stop
     * doing new work and let remnants (live particles/trails) drain naturally". The base object has
     * no remnants, so both just mark this particle removed; emitters override to honor {@code force}.
     */
    @Override
    public void remove(boolean force) {
        remove();
    }

    @Override
    public final void tick() {
        // heartbeat first — it must beat during the start delay too
        if (tickHost != null) {
            lastHostTick = tickHost.tickCount();
        }
        lastTick++;
        if (delay > 0) {
            delay--;
            return;
        }
        // drive the timeline (root only) once the start delay has elapsed
        if (onUpdateTick != null) {
            onUpdateTick.run();
        }
        // inactive (timeline) nodes and their descendants neither tick nor render
        if (!isActive()) {
            return;
        }
        // snapshot the render origin (xo=x) ONCE per tick, before sub-stepping, so a frozen object holds
        // still (no xo!=x jitter) and a sped-up one interpolates the whole tick (not just the last step).
        onTickBegin();
        // advance the simulation by this tick's scaled dt (speed track). dt is sampled from the master
        // clock so it stays reproducible under simulateTo; default timeScale()==1 => one updateTick(1f).
        float owed = timeScale();
        int guard = 0;
        while (owed > 1e-6f && guard++ < MAX_SUBSTEPS) {
            float step = Math.min(owed, 1f); // <=1 keeps integration/collision stable at high speed
            updateTick(step);
            owed -= step;
        }
    }

    /** Per-tick render-origin snapshot (xo=x), run once before sub-stepping. Emitters override to snapshot
     *  their own and their particles' origins; default no-op. */
    protected void onTickBegin() {
    }

    @Override
    public void updateTick() {
        updateTick(1f);
    }

    /** Advance this object's simulation by {@code dt} ticks (1 = a full game tick). */
    public void updateTick(float dt) {
        if (dt > 0 && effectExecutor != null) {
            effectExecutor.updateFXObjectTick(this);
        }
    }

    @Override
    public void render(@Nonnull VertexConsumer buffer, Camera pRenderInfo, float pPartialTicks) {
        var tickTime = lastTick + pPartialTicks;
        deltaTime = tickTime - lastTickTime;
        lastTickTime = tickTime;
        if (delay > 0) return;
        updateFrame(pPartialTicks);
        if (buffer instanceof RenderPassPipeline passBuffer) {
            passBuffer.setupRenderingState(pRenderInfo, pPartialTicks);
            prepareRenderPass(passBuffer);
        } else {
            Photon.LOGGER.error("Photon FX Object {} is not using a RenderPassBuffer. " +
                            "Please use a RenderPassBuffer to render your FX Objects.", name);
        }
    }

    public void prepareRenderPass(RenderPassPipeline buffer) {

    }

    @Override
    public void updateFrame(float partialTicks) {
        // drive the timeline's per-frame animation pass (root only), before the active gate
        if (onUpdateFrame != null) {
            onUpdateFrame.accept(partialTicks);
        }
        if (!isActive()) {
            return;
        }
        if (effectExecutor != null) {
            effectExecutor.updateFXObjectFrame(this, partialTicks);
        }
    }

    @Override
    @Nonnull
    public ParticleRenderType getRenderType() {
        return NO_RENDER_RENDER_TYPE;
    }

    @Override
    @Nonnull
    public AABB getRenderBoundingBox(float partialTicks) {
        return AABB.INFINITE;
    }

    public static ParticleRenderType NO_RENDER_RENDER_TYPE = new ParticleRenderType() {
        public final RenderPassPipeline pipeline = new RenderPassPipeline(new ByteBufferBuilder(1));

        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            return pipeline;
        }

        @Override
        public boolean isTranslucent() {
            return false;
        }
    };
}
