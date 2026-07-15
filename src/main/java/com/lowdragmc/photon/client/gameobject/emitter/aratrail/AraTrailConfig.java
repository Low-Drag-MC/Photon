package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.*;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.TransformRefConfigurator;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.lowdragmc.photon.client.gameobject.particle.renderer.AraTrailParticleRenderer;
import com.lowdragmc.photon.gui.editor.view.FXHierarchyView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.opengl.GL30.glBindVertexArray;

/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class AraTrailConfig implements IConfigurable, IPersistedSerializable {

    public enum TrailAlignment {
        View,
        Velocity,
        Local
    }

    public enum TrailSpace
    {
        World,
        Local,
        Custom
    }

    public enum TrailSorting {
        OlderOnTop,
        NewerOnTop
    }

    public enum Timescale {
        Normal,
        Unscaled
    }

    public enum TextureMode {
        Stretch,
        Tile,
        WorldTile
    }
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.duration", tips = "photon.emitter.config.duration")
    @ConfigNumber(range = {1, Integer.MAX_VALUE})
    protected int duration = 100;
    @Setter
    @Getter
    @Configurable(name = "ParticleConfig.looping", tips = "photon.emitter.config.looping")
    protected boolean looping = true;
    @Configurable(name = "AraTrails.section", subConfigurable = true, tips = "AraTrails.section.tips")
    public final TrailSection section = new TrailSection();
    @Configurable(name = "AraTrails.space", tips = "AraTrails.space.tips")
    @ConfigSelector(subConfiguratorBuilder = "createSpaceConfigurator")
    public TrailSpace space = TrailSpace.World;
    @Persisted
    public final TransformRef customSpace = new TransformRef();
    @Configurable(name = "AraTrails.alignment", tips = "AraTrails.alignment.tips")
    public TrailAlignment alignment = TrailAlignment.View;
    @Configurable(name = "AraTrails.sorting", tips = "AraTrails.sorting.tips")
    public TrailSorting sorting = TrailSorting.OlderOnTop;
    @Configurable(name = "AraTrails.thickness", tips = "AraTrails.thickness.tips")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float thickness = 0.2f;
    @Configurable(name = "AraTrails.smoothness", tips = "AraTrails.smoothness.tips")
    @ConfigNumber(range = {1, 8})
    public int smoothness = 1;
    @Configurable(name = "AraTrails.smoothingDistance")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float smoothingDistance = 0.05f;
    @Configurable(name = "AraTrails.highQualityCorners", tips = "AraTrails.highQualityCorners.tips")
    public boolean highQualityCorners = false;
    @Configurable(name = "AraTrails.cornerRoundness")
    @ConfigNumber(range = {0, 12})
    public int cornerRoundness = 5;

    @ConfigHeader("AraTrails.Length")
    @Configurable(name = "AraTrails.thicknessOverLength", tips = "AraTrails.thicknessOverLength.tips")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverLength = NumberFunction.constant(1f);    /**< maps trail length to thickness.*/
    @Configurable(name = "AraTrails.colorOverLength", tips = "AraTrails.colorOverLength.tips")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverLength = NumberFunction.color(-1);

    @ConfigHeader("AraTrails.Time")
    @Configurable(name = "AraTrails.thicknessOverTime", tips = "AraTrails.thicknessOverTime.tips")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverTime = NumberFunction.constant(1f);  /**< maps trail lifetime to thickness.*/
    @Configurable(name = "AraTrails.thicknessOverSegmentTime", tips = "AraTrails.thicknessOverSegmentTime.tips")
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverSegmentTime = NumberFunction.constant(1f);  /**< maps segment lifetime to thickness.*/
    @Configurable(name = "AraTrails.colorOverTime", tips = "AraTrails.colorOverTime.tips")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverTime = NumberFunction.color(-1);
    @Configurable(name = "AraTrails.colorOverSegmentTime", tips = "AraTrails.colorOverSegmentTime.tips")
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverSegmentTime = NumberFunction.color(-1);


    @ConfigHeader("AraTrails.Emission")
    @Configurable(name = "AraTrails.emit")
    public boolean emit = true;
    @Configurable(name = "AraTrails.initialThickness", tips = "AraTrails.initialThickness.tips")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float initialThickness = 1;
    @Configurable(name = "AraTrails.initialColor", tips = "AraTrails.initialColor.tips")
    @ConfigColor
    public int initialColor = -1;
    @Configurable(name = "AraTrails.initialVelocity", tips = "AraTrails.initialVelocity.tips")
    public Vector3f initialVelocity = new Vector3f(0, 0, 0);
    @Configurable(name = "AraTrails.minSpawnTime", tips = "AraTrails.minSpawnTime.tips")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float timeInterval = 0.05f;
    @Configurable(name = "AraTrails.minDistance", tips = "AraTrails.minDistance.tips")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float minDistance = 0.025f;
    @Configurable(name = "AraTrails.time", tips = "AraTrails.time.tips")
    @ConfigNumber(range = {0, Float.MAX_VALUE})
    public float time = 1f;

    @ConfigHeader("AraTrails.Physics")
    @Configurable(name = "AraTrails.physicsSetting", subConfigurable = true, tips = "AraTrails.physicsSetting.tips")
    public final AraPhysicsSetting physicsSetting = new AraPhysicsSetting();

    @ConfigHeader("AraTrails.Rendering")
    @Configurable(name = "AraTrails.textureMode", tips = "AraTrails.textureMode.tips")
    public TextureMode textureMode = TextureMode.Stretch;
    @Configurable(name = "AraTrails.uvFactor", tips = "AraTrails.uvFactor.tips")
    public float uvFactor = 1;
    @Configurable(name = "AraTrails.uvWidthFactor", tips = "AraTrails.uvWidthFactor.tips")
    public float uvWidthFactor = 1;
    @Configurable(name = "AraTrails.tileAnchor", tips = "AraTrails.tileAnchor.tips")
    @ConfigNumber(range = {0, 1})
    public float tileAnchor = 1;
    @Getter
    @Configurable(name = "ParticleConfig.renderer", subConfigurable = true, tips = "photon.emitter.config.renderer")
    public final InstancedRendererSetting renderer = new InstancedRendererSetting();
    @Configurable(name = "ParticleConfig.additionalGPUDataSetting", subConfigurable = true, tips = "photon.emitter.config.additional_gpu_data")
    public final AraTrailAdditionalGPUDataSetting additionalGPUDataSetting = new AraTrailAdditionalGPUDataSetting(this);

    // runtime
    public final InstancedRendererSetting.Runtime defaultRenderRuntime = renderer.createRuntime();
    public final PhotonFXRenderPass particleRenderType = createRenderPass(defaultRenderRuntime);

    /**
     * Build an ara-trail render pass bound to {@code renderRuntime} (slot-or-config): the shared pass uses
     * {@link #defaultRenderRuntime}; a per-emitter override uses that emitter's runtime (see
     * {@code AraTrailRuntime.renderer}). Equal effective values produce equal passes → one draw.
     */
    public PhotonFXRenderPass createRenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        return new RenderPass(renderRuntime);
    }

    public AraTrailConfig() {
        renderer.getMaterials().add(new MaterialSetting());
    }

    private class RenderPass extends PhotonFXRenderPass {
        private final InstancedRendererSetting.Runtime renderRuntime;
        // stateful (mesh scratch buffers) -> per-config instance; NOT part of equals/hashCode
        private final AraTrailParticleRenderer trailRenderer;

        public RenderPass(InstancedRendererSetting.Runtime renderRuntime) {
            super(renderRuntime, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            this.renderRuntime = renderRuntime;
            this.trailRenderer = new AraTrailParticleRenderer(AraTrailConfig.this);
        }

        @Override
        public void clearInstance() {
            trailRenderer.dispose();
        }

        @Override
        protected void renderQueue(VertexConsumer buffer, Collection<IParticle> particles, Camera camera, float partialTicks) {
            trailRenderer.renderQueue(buffer, particles, camera, partialTicks);
        }

        @Override
        protected boolean useInstancing() {
            // high-quality corners emit a data-dependent fan topology (flat mode only) -> CPU path
            return renderRuntime.isUseGPUInstance()
                    && (section.isEnable() || !(highQualityCorners && alignment != TrailAlignment.Local));
        }

        @Override
        protected boolean drawInstanced(List<MaterialSetting> materials, RenderPassPipeline pipeline, Collection<IParticle> particles, Camera camera, float partialTicks) {
            // auto-enable whatever channels the shadergraph materials read; rebuild the layout /
            // baked geometry (mode, section polygon, tube uvWidthFactor) on change
            additionalGPUDataSetting.setMaterialMask(shaderGraphChannelMask(materials));
            if (additionalGPUDataSetting.attribRelayoutNeeded() || trailRenderer.geometryStale()) {
                clearInstance();
            }

            var drew = false;
            // upload to vbo + point texture
            if (trailRenderer.uploadInstances(particles, camera, partialTicks)) {
                var context = section.isEnable() ? MaterialContext.ARA_TRAIL_TUBE_INSTANCE
                        : MaterialContext.ARA_TRAIL_INSTANCE;
                for (MaterialSetting materialSetting : materials) {
                    materialSetting.pre();
                    renderInstanceWithMaterial(materialSetting.getMaterial(), context);
                    materialSetting.post();
                }
                drew = true;
            }

            // invalidate cache
            glBindVertexArray(0);
            BufferUploader.invalidate();
            return drew;
        }

        private void renderInstanceWithMaterial(IMaterial material, MaterialContext context) {
            var shader = material.begin(context);
            RenderSystem.setShader(() -> shader);
            trailRenderer.drawInstanced(shader);
            material.end(context);
        }

        private AraTrailConfig owner() {
            return AraTrailConfig.this;
        }

        /**
         * The instanced base mesh bakes the section geometry (flat/tube, polygon, uvWidthFactor)
         * per pass — configs that differ in it must NOT batch, or the pass owner's mesh would be
         * applied to every batched trail. Mirrors how the tile pass keys on renderMode/model.
         */
        @Override
        public boolean equals(@Nonnull Object o) {
            if (!(o instanceof RenderPass other) || !super.equals(o)) return false;
            var theirs = other.owner();
            if (section.isEnable() != theirs.section.isEnable()) return false;
            if (!section.isEnable()) return true;
            if (uvWidthFactor != theirs.uvWidthFactor) return false;
            var mine = section.vertices;
            var others = theirs.section.vertices;
            if (mine.size() != others.size()) return false;
            for (int i = 0; i < mine.size(); i++) {
                if (!mine.get(i).equals(others.get(i))) return false;
            }
            return true;
        }

        /**
         * Must discriminate whatever {@link #equals} discriminates: the pipeline's pass comparator
         * tie-breaks unequal passes by hashCode, and equal hashes would merge them in the TreeMap.
         */
        @Override
        public int hashCode() {
            int hash = super.hashCode();
            hash = hash * 31 + Boolean.hashCode(section.isEnable());
            if (section.isEnable()) {
                hash = hash * 31 + Float.hashCode(uvWidthFactor);
                for (var vertex : section.vertices) {
                    hash = hash * 31 + Float.hashCode(vertex.x);
                    hash = hash * 31 + Float.hashCode(vertex.y);
                }
            }
            return hash;
        }
    }

    private void createSpaceConfigurator(TrailSpace space, ConfiguratorGroup group) {
        if (space == TrailSpace.Custom) {
            group.addConfigurator(new TransformRefConfigurator("AraTrails.customSpace",
                    () -> this.customSpace,
                    transformRef -> this.customSpace.setTransformId(transformRef.getTransformId()),new TransformRef(), true ) {
                @Override
                protected boolean canDropObject(@NotNull Object object) {
                    return object instanceof FXHierarchyView.DraggingNode || super.canDropObject(object);
                }

                @Override
                protected void onDropObject(@NotNull Object object) {
                    if (object instanceof FXHierarchyView.DraggingNode(var draggedNode)) {
                        onValueUpdatePassively(new TransformRef(draggedNode.key.transform()));
                        updateValue();
                    } else {
                        super.onDropObject(object);
                    }
                }
            }.setTips("AraTrails.customSpace.tips"));
        }
    }
}
