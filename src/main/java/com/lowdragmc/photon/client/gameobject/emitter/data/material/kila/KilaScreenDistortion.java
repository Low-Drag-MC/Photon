package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * Shows the scene behind the particle, pushed around by a noise; the colour tints it and the alpha blends it in.
 * The scene is copied once per pass, so hazes drawn in the same pass do not see each other.
 */
@OnlyIn(Dist.CLIENT)
public class KilaScreenDistortion extends KilaModule {
    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.NOISE_FLOW).scroll(0.05f, 0.2f);
    /** screen uv per unit of noise */
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(0.02f);
    /** what stands in front of the haze is not pulled into it; costs the scene depth copy */
    @Persisted
    public boolean keepForeground = true;
    /** colour fringe: red pushed further and blue less far than green */
    @Persisted
    public float chromatic;
    @Persisted
    public Source source = Source.TEXTURE;
    /** adds its push to the other stacked distortions; the frame is bent once by the sum */
    @Persisted
    public boolean stack;
    /** whether this draw stacks: asked of the pipeline per draw, not saved */
    public boolean stacking;

    public enum Source {
        /** the texture's red and green, both ways */
        TEXTURE,
        /** the surface's normal, curved by Shading: glass and water */
        NORMAL
    }

    @Override
    public String id() {
        return "screen_distortion";
    }

    @Override
    public Category category() {
        return Category.COLOR;
    }

    @Override
    public long structure() {
        if (stacking) return 8 | (source == Source.NORMAL ? 4 : 0);
        return (keepForeground ? 1 : 0) | (chromatic != 0 ? 2 : 0) | (source == Source.NORMAL ? 4 : 0);
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_SCREEN_DISTORT").vec4("KilaScreenParams").params(KilaParam.SCREEN_STRENGTH);
        if (source == Source.NORMAL) {
            layout.define("KILA_SCREEN_NORMAL");
        } else {
            layout.fragmentUvChain("KilaScreen").source("SCREEN", "KilaScreen", texture, false);
        }
        // stacked, the push is all it writes: the scene is read once, by the bend
        if (stacking) {
            layout.define("KILA_SCREEN_STACK");
            return;
        }
        layout.sampler(PhotonShaderCompiler.SCENE_COLOR);
        if (chromatic != 0) layout.define("KILA_SCREEN_CHROMA");
        if (keepForeground) {
            layout.define("KILA_SCREEN_DEPTH").mat4("U_InverseProjectionMatrix").sampler(PhotonShaderCompiler.SCENE_DEPTH);
        }
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        if (source == Source.TEXTURE) {
            texture.uploadUv(shader, "KilaScreen");
            if (texture.isProcedural()) texture.uploadSource(shader, "KilaScreen", scratch);
            shader.safeGetUniform("KilaScreenOpts").set(texture.flags(), 0, 0, 0);
        }
        if (stacking) {
            // as when it shows the scene: the tint is not lit or fogged
            shader.safeGetUniform("KilaScreenParams").set(1f, 0f, 0f, 0f);
            return;
        }
        int scene = 0;
        if (!context.isRenderingPreview()) {
            var pipeline = RenderPassPipeline.getCurrent();
            if (pipeline != null) scene = pipeline.getSceneSamplers().colorTexture();
        }
        boolean depth = keepForeground && scene > 0 && KilaDepth.bindSceneDepth(shader, context);
        if (scene > 0) {
            shader.setSampler(PhotonShaderCompiler.SCENE_COLOR, scene);
        } else {
            // null is what ShaderInstance.apply skips
            //noinspection DataFlowIssue
            shader.setSampler(PhotonShaderCompiler.SCENE_COLOR, null);
        }
        shader.safeGetUniform("KilaScreenParams").set(scene > 0 ? 1f : 0f, depth ? 1f : 0f, chromatic, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        if (source == Source.TEXTURE) out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.SCREEN_STRENGTH, strength);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.driver(group, "kila.screen_distortion.strength", strength, -0.5f, 0.5f);
        KilaUI.bool(group, "kila.screen_distortion.stack", () -> stack, v -> stack = v);
        KilaUI.bool(group, "kila.screen_distortion.keep_foreground", () -> keepForeground, v -> keepForeground = v);
        KilaUI.number(group, "kila.screen_distortion.chromatic", () -> chromatic, v -> chromatic = v, 0, -1, 1);
        KilaUI.choice(group, "kila.screen_distortion.source", Source.values(), () -> source, v -> source = v);
        var noise = KilaUI.subGroup(group, "kila.screen_distortion.texture_group", false);
        KilaUI.texture(noise, "kila.screen_distortion.texture", texture, KilaUI.TextureKind.VECTOR, true, true);
        KilaUI.showWhen(group, noise, () -> source == Source.TEXTURE);
    }
}
