package com.lowdragmc.photon.client.gameobject.forcefield;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * A Unity-style particle force field: affects the particles of every sibling emitter (same FX scene)
 * whose External Forces module is enabled. Supports a shaped influence volume with range falloff,
 * directional force, gravity toward a focus point, vortex rotation, and drag.
 */
@ParametersAreNonnullByDefault
public class ForceFieldObject extends FXObject {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "force_field");
    /** Matches the 0.05f authored-force scale of {@code ForceOverLifetimeSetting.Runtime#getForce}. */
    private static final float FORCE_SCALE = 0.05f;

    @LDLRegisterClient(name = "force_field", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new ForceFieldObject();
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
            new RuntimeBinding("startRange", "ForceFieldConfig.startRange", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().startRange),
            new RuntimeBinding("endRange", "ForceFieldConfig.endRange", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().endRange),
            new RuntimeBinding("directionX", "ForceFieldConfig.directionX", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionX),
            new RuntimeBinding("directionY", "ForceFieldConfig.directionY", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionY),
            new RuntimeBinding("directionZ", "ForceFieldConfig.directionZ", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionZ),
            new RuntimeBinding("gravity", "ForceFieldConfig.gravity", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().gravity),
            new RuntimeBinding("gravityFocus", "ForceFieldConfig.gravityFocus", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().gravityFocus),
            new RuntimeBinding("rotationSpeed", "ForceFieldConfig.rotationSpeed", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().rotationSpeed),
            new RuntimeBinding("rotationAttraction", "ForceFieldConfig.rotationAttraction", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().rotationAttraction),
            new RuntimeBinding("drag", "ForceFieldConfig.drag", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().drag));

    @Persisted(subPersisted = true)
    public final ForceFieldConfig config = new ForceFieldConfig();

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private ForceFieldConfig.Runtime runtime;

    // stable per-field keys for the per-particle vortex-axis randomness (memoized on the particle)
    private final Object rotationRandomKeyX = new Object();
    private final Object rotationRandomKeyZ = new Object();

    public ForceFieldConfig.Runtime runtime() {
        if (runtime == null) {
            runtime = new ForceFieldConfig.Runtime(config);
        }
        return runtime;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    /** Whether this field has been removed (leaf objects can't use the child-based {@code isAlive}). */
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
    }

    @Override
    protected void onTickBegin() {
        // Transform's lazy matrix caches are not synchronized; warm them on the game thread
        // before particles read them via parallelStream.
        transform().localToWorldMatrix();
        transform().worldToLocalMatrix();
        transform().rotation();
    }

    /**
     * Evaluate this field for one particle and fold the result into {@code worldVelocity} (mutated in
     * place: direction/gravity/vortex integrate as force * dt, drag damps). Thread-safe: read-only on
     * the field's state; per-particle randoms are memoized on the particle.
     *
     * @param worldPos      the particle's world position
     * @param worldVelocity the particle's stored velocity in world space (in/out)
     * @param particleSize  the particle's current size (max component)
     * @param dt            step in ticks
     * @param particle      the affected particle (lifetime t + memoized randoms)
     * @param multiplier    the emitter's external-forces multiplier for this particle
     */
    public void apply(Vector3f worldPos, Vector3f worldVelocity, float particleSize, float dt, IParticle particle, float multiplier) {
        var rt = runtime();
        // shape membership + falloff are tested in field-local space, so the field's transform
        // (including scale) shapes the influence volume
        var local = new Vector3f(worldPos).mulPosition(transform().worldToLocalMatrix());
        var endRange = rt.getEndRange();
        var startRange = Math.min(rt.getStartRange(), endRange);
        if (endRange <= 0) {
            return;
        }
        float r = switch (rt.getShape()) {
            case Sphere -> local.length();
            case Hemisphere -> local.y < 0 ? Float.MAX_VALUE : local.length();
            case Cylinder -> Math.max((float) Math.sqrt(local.x * local.x + local.z * local.z), Math.abs(local.y));
            case Box -> Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z)));
        };
        if (r > endRange) {
            return;
        }
        // influence contract: 1 inside startRange, linear falloff to 0 at endRange
        float influence = r <= startRange ? 1f : 1f - (r - startRange) / (endRange - startRange);
        float strength = influence * multiplier;
        if (strength == 0) {
            return;
        }

        var t = particle.getT();
        var localToWorld = transform().localToWorldMatrix();

        // directional force, authored along the field's local axes
        var direction = new Vector3f(
                rt.getDirectionX(particle, t),
                rt.getDirectionY(particle, t),
                rt.getDirectionZ(particle, t));
        if (direction.lengthSquared() > 0) {
            var magnitude = direction.length();
            localToWorld.transformDirection(direction).normalize(magnitude);
            worldVelocity.add(direction.mul(FORCE_SCALE * strength * dt));
        }

        // gravity: pull toward the focus point (0 = field center, 1 = a point at endRange toward the particle)
        var gravity = rt.getGravity(particle, t);
        if (gravity != 0 && local.lengthSquared() > 1e-8f) {
            var pull = new Vector3f(local).normalize(rt.getGravityFocus() * endRange).sub(local);
            if (pull.lengthSquared() > 1e-8f) {
                localToWorld.transformDirection(pull).normalize();
                worldVelocity.add(pull.mul(gravity * FORCE_SCALE * strength * dt));
            }
        }

        // vortex rotation around the field's local Y axis; randomness offsets the axis anchor per particle
        var rotationSpeed = rt.getRotationSpeed(particle, t);
        if (rotationSpeed != 0) {
            var anchorX = config.getRotationRandomnessX() * (particle.getMemRandom(rotationRandomKeyX, rand -> rand.nextFloat() * 2 - 1));
            var anchorZ = config.getRotationRandomnessZ() * (particle.getMemRandom(rotationRandomKeyZ, rand -> rand.nextFloat() * 2 - 1));
            var radialX = local.x - anchorX;
            var radialZ = local.z - anchorZ;
            if (radialX * radialX + radialZ * radialZ > 1e-8f) {
                // tangential direction = cross(localY, radial)
                var tangential = new Vector3f(-radialZ, 0, radialX);
                localToWorld.transformDirection(tangential).normalize(rotationSpeed * FORCE_SCALE);
                worldVelocity.add(new Vector3f(tangential).mul(strength * dt));
                // attraction drags the current velocity toward the pure vortex motion
                var attraction = Math.clamp(rt.getRotationAttraction() * strength * dt, 0f, 1f);
                if (attraction > 0) {
                    worldVelocity.lerp(tangential, attraction);
                }
            }
        }

        // drag: velocity damping, optionally scaled by particle size and speed
        var drag = rt.getDrag(particle, t);
        if (drag > 0) {
            float k = drag * strength;
            if (config.isMultiplyDragByParticleSize()) {
                k *= particleSize;
            }
            if (config.isMultiplyDragByParticleVelocity()) {
                k *= worldVelocity.length();
            }
            worldVelocity.mul(Math.max(0f, 1f - k * FORCE_SCALE * dt));
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if (!scene.sceneView().isShapeVisible()) {
            return;
        }
        var poseStack = new PoseStack();
        poseStack.mulPose(transform().localToWorldMatrix());
        var rt = runtime();
        var endRange = rt.getEndRange();
        var startRange = Math.min(rt.getStartRange(), endRange);
        drawRange(bufferSource, poseStack, ForceFieldGizmos.getGuideLines(config.getShape(), endRange), ColorPattern.YELLOW.color);
        if (startRange > 0) {
            drawRange(bufferSource, poseStack, ForceFieldGizmos.getGuideLines(config.getShape(), startRange), ColorPattern.GRAY.color);
        }
    }

    // 26.1: immediate Tesselator+BufferUploader draws are gone — route through the scene's
    // buffer source with the vanilla lines render type (blend/depth/width owned by the pipeline).
    private static void drawRange(MultiBufferSource bufferSource, PoseStack poseStack,
                                  List<oshi.util.tuples.Pair<Vector3f, Vector3f>> edges, int color) {
        if (edges.isEmpty()) {
            return;
        }
        var buffer = bufferSource.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.lines());
        RenderBufferUtils.drawEdges(poseStack, buffer, edges, color, 5);
    }
}
