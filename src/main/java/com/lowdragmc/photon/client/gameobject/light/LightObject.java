package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.LightSink;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import oshi.util.tuples.Pair;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A dynamic light in an FX: point or spot (the cone points along local -Y), with colour, intensity and
 * range as curves over its lifetime, and flicker. Lit while alive, active and visible.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class LightObject extends FXObject implements LightProvider {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "light");

    @LDLRegisterClient(name = "light", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new LightObject();
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
            new RuntimeBinding("color", "LightConfig.color", ConfigValueType.COLOR,
                    o -> ((LightObject) o).runtime().color),
            new RuntimeBinding("intensity", "LightConfig.intensity", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((LightObject) o).runtime().intensity),
            new RuntimeBinding("range", "LightConfig.range", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((LightObject) o).runtime().range),
            new RuntimeBinding("innerAngle", "LightConfig.innerAngle", ConfigValueType.FLOAT,
                    o -> ((LightObject) o).runtime().innerAngle),
            new RuntimeBinding("outerAngle", "LightConfig.outerAngle", ConfigValueType.FLOAT,
                    o -> ((LightObject) o).runtime().outerAngle),
            new RuntimeBinding("sourceRadius", "LightConfig.sourceRadius", ConfigValueType.FLOAT,
                    o -> ((LightObject) o).runtime().sourceRadius),
            new RuntimeBinding("flicker", "LightConfig.flicker", ConfigValueType.FLOAT,
                    o -> ((LightObject) o).runtime().flicker),
            new RuntimeBinding("castShadows", "LightConfig.castShadows", ConfigValueType.BOOL,
                    o -> ((LightObject) o).runtime().castShadows));

    @Persisted(subPersisted = true)
    public final LightConfig config = new LightConfig();

    private LightConfig.Runtime runtime;
    /** Age in ticks since the start delay elapsed; {@code < 0} until the first tick. */
    private float age = -1;
    private float seed;
    private final Vector4f color = new Vector4f();
    @Nullable
    private Level registeredLevel;

    public LightConfig.Runtime runtime() {
        if (runtime == null) {
            runtime = new LightConfig.Runtime(config);
        }
        return runtime;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
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
        age = -1;
        if (runtime != null) {
            runtime.clear();
        }
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
        if (!config.isLooping() && age >= config.getLifetime()) {
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
        if (registeredLevel != null) DynamicLightManager.removeProvider(registeredLevel, this);
        if (level != null) DynamicLightManager.addProvider(level, this);
        registeredLevel = level;
    }

    /** Pending through the start delay, then running for its lifetime. */
    private boolean isRunning() {
        return !removed && isActive() && (age < 0 || config.isLooping() || age < config.getLifetime());
    }

    @Override
    public boolean isAlive() {
        return isRunning() || super.isAlive();
    }

    @Override
    public boolean isPlaying() {
        return isRunning() || super.isPlaying();
    }

    private float lifetimeT(float time) {
        int lifetime = Math.max(1, config.getLifetime());
        return config.isLooping() ? (time % lifetime) / lifetime : Math.min(time / lifetime, 1f);
    }

    @Override
    public void submitLights(LightSink sink, float partialTick) {
        if (age < 0 || !isRunning() || !isVisible() || isDiscarded()) return;
        var rt = runtime();
        float time = age + partialTick;
        float t = lifetimeT(time);
        Supplier<Float> random = () -> seed;
        float intensity = rt.getIntensity(t, random);
        float range = rt.getRange(t, random);
        if (intensity <= 0 || range <= 0) return;
        float flicker = Mth.clamp(rt.flicker.get(), 0f, 1f);
        if (flicker > 0) {
            float phase = time / 20f * config.getFlickerSpeed() + seed * 100f;
            float noise = 0.5f + 0.25f * Mth.sin(phase * 6.2832f) + 0.15f * Mth.sin(phase * 13.7f + 1.3f) + 0.1f * Mth.sin(phase * 29.3f + 4.1f);
            intensity *= 1f - flicker * Mth.clamp(noise, 0f, 1f);
        }
        rt.sampleColor(t, random, color);

        var matrix = transform().localToWorldMatrix();
        var position = matrix.transformPosition(new Vector3f());
        var light = sink.next()
                .at(position.x, position.y, position.z)
                .color(color.x, color.y, color.z)
                .intensity(intensity)
                .range(range)
                .sourceRadius(Math.max(0f, rt.sourceRadius.get()))
                .shadows(rt.castShadows.get());
        if (config.getType() == LightConfig.Type.Spot) {
            light.spot(rt.innerAngle.get(), rt.outerAngle.get());
            matrix.transformDirection(light.direction.set(0f, -1f, 0f));
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if (!scene.sceneView().isShapeVisible()) {
            return;
        }
        float range = runtime().getRange(lifetimeT(Math.max(age, 0) + partialTicks), () -> seed);
        if (range <= 0) {
            return;
        }
        var poseStack = new PoseStack();
        poseStack.mulPose(transform().localToWorldMatrix());
        var edges = config.getType() == LightConfig.Type.Spot
                ? coneLines(range, runtime().outerAngle.get())
                : sphereLines(range);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        RenderSystem.lineWidth(5);
        RenderBufferUtils.drawEdges(poseStack, buffer, edges, ColorPattern.YELLOW.color);
        var mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static List<Pair<Vector3f, Vector3f>> sphereLines(float radius) {
        var edges = new ArrayList<Pair<Vector3f, Vector3f>>();
        int segments = 32;
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < segments; i++) {
                edges.add(new Pair<>(circlePoint(axis, i, segments, radius), circlePoint(axis, i + 1, segments, radius)));
            }
        }
        return edges;
    }

    private static Vector3f circlePoint(int axis, int i, int segments, float radius) {
        float a = i * Mth.TWO_PI / segments;
        float c = Mth.cos(a) * radius, s = Mth.sin(a) * radius;
        return switch (axis) {
            case 0 -> new Vector3f(0, c, s);
            case 1 -> new Vector3f(c, 0, s);
            default -> new Vector3f(c, s, 0);
        };
    }

    /** Apex at the origin, opening along -Y. */
    private static List<Pair<Vector3f, Vector3f>> coneLines(float range, float outerAngle) {
        var edges = new ArrayList<Pair<Vector3f, Vector3f>>();
        float angle = (float) Math.toRadians(Mth.clamp(outerAngle, 0.1f, 89f));
        float radius = range * Mth.sin(angle);
        float depth = range * Mth.cos(angle);
        int segments = 32;
        for (int i = 0; i < segments; i++) {
            float a0 = i * Mth.TWO_PI / segments, a1 = (i + 1) * Mth.TWO_PI / segments;
            var p0 = new Vector3f(Mth.cos(a0) * radius, -depth, Mth.sin(a0) * radius);
            edges.add(new Pair<>(p0, new Vector3f(Mth.cos(a1) * radius, -depth, Mth.sin(a1) * radius)));
            if (i % 8 == 0) edges.add(new Pair<>(new Vector3f(), p0));
        }
        return edges;
    }
}
