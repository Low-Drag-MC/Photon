package com.lowdragmc.photon.client.gameobject.collider;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.collision.ColliderTarget;
import com.lowdragmc.photon.client.collision.CollisionDebug;
import com.lowdragmc.photon.client.collision.CollisionScene;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/** A plane, box or sphere the particles of the same FX collide with, or a trigger they report; placed by its transform. */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class ColliderObject extends FXObject {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "collider");

    @LDLRegisterClient(name = "collider", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new ColliderObject();
        }

        @Override
        public IGuiTexture icon() {
            return ICON;
        }

        @Override
        public List<RuntimeBinding> runtimeBindings() {
            return RUNTIME_BINDINGS;
        }
    };

    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("radius", "ColliderConfig.radius", ConfigValueType.FLOAT,
                    o -> ((ColliderObject) o).runtime().radius),
            new RuntimeBinding("trigger", "ColliderConfig.trigger", ConfigValueType.BOOL,
                    o -> ((ColliderObject) o).runtime().trigger));

    @Persisted(subPersisted = true)
    public final ColliderConfig config = new ColliderConfig();

    private ColliderConfig.Runtime runtime;
    private long sampledAt = Long.MIN_VALUE;
    private final Vector3f sampledCenter = new Vector3f();
    private final Vector3f motion = new Vector3f();

    public ColliderConfig.Runtime runtime() {
        if (runtime == null) {
            runtime = config.createRuntime();
        }
        return runtime;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    public boolean isRemoved() {
        return this.removed;
    }

    @Override
    public IGuiTexture getIcon() {
        return ICON;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        config.buildConfigurator(father);
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear();
        }
        sampledAt = Long.MIN_VALUE;
        motion.zero();
    }

    /**
     * This collider frozen in world space for the current tick, with how far it moved since the last; null while
     * it has no extent (scaled to nothing) or its transform is not finite. Game thread.
     */
    @Nullable
    public ColliderTarget target() {
        var uuid = transform().id();
        long index = (uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()) & 0xFFFFFFFFFFFFL;
        var matrix = transform().localToWorldMatrix();
        var center = matrix.transformPosition(new Vector3f());
        var x = matrix.transformDirection(new Vector3f(1, 0, 0));
        var y = matrix.transformDirection(new Vector3f(0, 1, 0));
        var z = matrix.transformDirection(new Vector3f(0, 0, 1));
        if (!center.isFinite() || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            sampledAt = Long.MIN_VALUE;
            return null;
        }
        long now = clock();
        if (sampledAt != now) {
            motion.zero();
            if (sampledAt == now - 1) {
                center.sub(sampledCenter, motion);
                if (motion.lengthSquared() > CollisionScene.MAX_TARGET_MOTION * CollisionScene.MAX_TARGET_MOTION) {
                    motion.zero();
                }
            }
            sampledCenter.set(center);
            sampledAt = now;
        }
        boolean trigger = runtime().trigger.get();
        ColliderTarget target;
        switch (config.getShape()) {
            case Plane -> {
                // the plane the gizmo draws, through local x and z, facing local +Y's side
                var normal = new Vector3f(z).cross(x);
                if (normal.dot(y) < 0) normal.negate();
                if (normal.lengthSquared() < 1e-12) normal.set(y);
                if (normal.lengthSquared() < 1e-12) return null;
                target = ColliderTarget.plane(index, this, trigger, center.x, center.y, center.z, normal.x, normal.y, normal.z);
            }
            case Sphere -> {
                double radius = runtime().radius.get() * Math.max(x.length(), Math.max(y.length(), z.length()));
                if (!(radius > 0)) return null;
                target = ColliderTarget.sphere(index, this, trigger, center.x, center.y, center.z, radius);
            }
            default -> {
                var size = config.size;
                target = ColliderTarget.box(index, this, trigger, center.x, center.y, center.z,
                        new double[]{x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z},
                        size.x / 2 * x.length(), size.y / 2 * y.length(), size.z / 2 * z.length());
                if (target == null) return null;
            }
        }
        target = target.framed(new double[]{x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z});
        return motion.lengthSquared() == 0 ? target : target.moving(motion.x, motion.y, motion.z);
    }

    /** The effect's clock: its root ticks while anything in the effect lives, where a leaf like this leaves the engine. */
    private long clock() {
        return getScene() instanceof FXRuntime fxRuntime && fxRuntime.root instanceof FXObject root
                ? root.getLastTick() : getLastTick();
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if (scene.sceneView().isShapeVisible() || CollisionDebug.enabled) drawGizmo();
    }

    /** The collider's wireframe: green for solid, cyan for a trigger. */
    public void drawGizmo() {
        var poseStack = new PoseStack();
        var color = runtime().trigger.get() ? ColorPattern.BRIGHT_CYAN.color : ColorPattern.GREEN.color;
        var shape = config.getShape();
        if (shape == ColliderConfig.Shape.Sphere) {
            // spheres stay round under non-uniform scale, as the collision does
            var matrix = transform().localToWorldMatrix();
            var center = matrix.transformPosition(new Vector3f());
            float scale = Math.max(matrix.transformDirection(new Vector3f(1, 0, 0)).length(),
                    Math.max(matrix.transformDirection(new Vector3f(0, 1, 0)).length(),
                            matrix.transformDirection(new Vector3f(0, 0, 1)).length()));
            poseStack.translate(center.x, center.y, center.z);
            CollisionGizmos.draw(poseStack, CollisionGizmos.colliderLines(shape, config.size,
                    runtime().radius.get() * scale), color, 4);
            return;
        }
        poseStack.mulPose(transform().localToWorldMatrix());
        CollisionGizmos.draw(poseStack, CollisionGizmos.colliderLines(shape, config.size, runtime().radius.get()),
                color, 4);
    }
}
