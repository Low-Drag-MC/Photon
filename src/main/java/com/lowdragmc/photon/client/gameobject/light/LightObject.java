package com.lowdragmc.photon.client.gameobject.light;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldGizmos;
import com.lowdragmc.photon.client.light.DynamicLightManager;
import com.lowdragmc.photon.client.light.LightProvider;
import com.lowdragmc.photon.client.light.LightSink;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;
import oshi.util.tuples.Pair;

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
public class LightObject extends LifetimeFXObject implements LightProvider {
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
                    o -> ((LightObject) o).runtime().castShadows),
            new RuntimeBinding("volumetric.enable", "enable", ConfigValueType.BOOL,
                    o -> ((LightObject) o).runtime().volumetricEnable),
            new RuntimeBinding("volumetric.strength", "VolumetricSetting.strength", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((LightObject) o).runtime().volumetricStrength));

    @Persisted(subPersisted = true)
    public final LightConfig config = new LightConfig();

    private LightConfig.Runtime runtime;
    private final Vector4f color = new Vector4f();

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
    protected int lifetime() {
        return config.getLifetime();
    }

    @Override
    protected boolean looping() {
        return config.isLooping();
    }

    @Override
    protected void attach(Level level) {
        DynamicLightManager.addProvider(level, this);
    }

    @Override
    protected void detach(Level level) {
        DynamicLightManager.removeProvider(level, this);
    }

    @Override
    protected void clearRuntime() {
        if (runtime != null) runtime.clear();
    }

    @Override
    public void submitLights(LightSink sink, float partialTick) {
        if (!isShowing()) return;
        var rt = runtime();
        float time = time(partialTick);
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
        if (rt.volumetricEnable.get()) {
            light.volumetric(Math.max(0f, rt.getVolumetricStrength(t, random)));
        }
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
        float range = runtime().getRange(lifetimeT(time(partialTicks)), () -> seed);
        if (range <= 0) {
            return;
        }
        drawGizmo(config.getType() == LightConfig.Type.Spot
                ? coneLines(range, runtime().outerAngle.get())
                : ForceFieldGizmos.getGuideLines(ForceFieldConfig.Shape.Sphere, range), ColorPattern.YELLOW.color);
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
