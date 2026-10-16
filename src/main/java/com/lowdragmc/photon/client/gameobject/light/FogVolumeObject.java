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
import com.lowdragmc.photon.client.light.FogProvider;
import com.lowdragmc.photon.client.light.FogSink;
import com.lowdragmc.photon.client.light.FogVolume;
import com.lowdragmc.photon.gui.editor.view.scene.SceneView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.function.Supplier;

/**
 * A box or sphere of fog the dynamic lights scatter in, sized and turned by the object's transform: a unit shape
 * at scale 1. Density, colour and emission are curves over its lifetime.
 */
@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
public class FogVolumeObject extends LifetimeFXObject implements FogProvider {
    public static final IGuiTexture ICON = Icons.icon(Photon.MOD_ID, "fog_volume");

    @LDLRegisterClient(name = "fog_volume", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new FogVolumeObject();
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
            new RuntimeBinding("density", "FogVolumeConfig.density", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((FogVolumeObject) o).runtime().density),
            new RuntimeBinding("color", "FogVolumeConfig.color", ConfigValueType.COLOR,
                    o -> ((FogVolumeObject) o).runtime().color),
            new RuntimeBinding("emission", "FogVolumeConfig.emission", ConfigValueType.COLOR,
                    o -> ((FogVolumeObject) o).runtime().emission),
            new RuntimeBinding("absorption", "FogVolumeConfig.absorption", ConfigValueType.FLOAT,
                    o -> ((FogVolumeObject) o).runtime().absorption),
            new RuntimeBinding("edgeFalloff", "FogVolumeConfig.edgeFalloff", ConfigValueType.FLOAT,
                    o -> ((FogVolumeObject) o).runtime().edgeFalloff));

    @Persisted(subPersisted = true)
    public final FogVolumeConfig config = new FogVolumeConfig();

    private FogVolumeConfig.Runtime runtime;
    private final Vector4f sample = new Vector4f();

    public FogVolumeConfig.Runtime runtime() {
        if (runtime == null) {
            runtime = new FogVolumeConfig.Runtime(config);
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
        DynamicLightManager.addFogProvider(level, this);
    }

    @Override
    protected void detach(Level level) {
        DynamicLightManager.removeFogProvider(level, this);
    }

    @Override
    protected void clearRuntime() {
        if (runtime != null) runtime.clear();
    }

    @Override
    public void submitFog(FogSink sink, float partialTick) {
        if (!isShowing()) return;
        var rt = runtime();
        float time = time(partialTick);
        float t = lifetimeT(time);
        Supplier<Float> random = () -> seed;
        float density = rt.getDensity(t, random);
        if (!(density > 0)) return;
        var matrix = transform().localToWorldMatrix();
        var center = matrix.transformPosition(new Vector3f());
        var fog = sink.next().at(center.x, center.y, center.z).density(density)
                .shape(config.getShape() == FogVolumeConfig.Shape.Sphere ? FogVolume.Shape.SPHERE : FogVolume.Shape.BOX)
                .absorption(Mth.clamp(rt.absorption.get(), 0f, 1f))
                .edgeFalloff(Mth.clamp(rt.edgeFalloff.get(), 0f, 1f));
        matrix.get3x3(fog.axes);
        rt.sampleColor(t, random, sample);
        fog.color(sample.x, sample.y, sample.z);
        rt.sampleEmission(t, random, sample);
        fog.emission(sample.x, sample.y, sample.z);
        var noise = config.getNoise();
        if (noise.isEnable()) {
            fog.noise(noise.getStrength(), noise.getScale());
            // the wind blows in blocks per second
            fog.noiseOffset.set(noise.getWind()).mul(-time / 20f);
        }
    }

    @Override
    public void drawEditorAfterWorld(SceneView.ParticleSceneEditor scene, MultiBufferSource bufferSource, float partialTicks) {
        if (!scene.sceneView().isShapeVisible()) {
            return;
        }
        var shape = config.getShape() == FogVolumeConfig.Shape.Sphere ? ForceFieldConfig.Shape.Sphere : ForceFieldConfig.Shape.Box;
        drawGizmo(ForceFieldGizmos.getGuideLines(shape, 0.5f), ColorPattern.BRIGHT_CYAN.color);
    }
}
