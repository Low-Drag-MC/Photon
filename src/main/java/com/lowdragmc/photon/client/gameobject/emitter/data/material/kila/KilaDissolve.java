package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/** Eats the particle away along a noise, with a burning edge; progress 0 is whole and 1 is gone. */
@OnlyIn(Dist.CLIENT)
public class KilaDissolve extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila_dissolve}. */
    public enum Direction {
        NONE, U, V, RADIAL, TEXTURE
    }

    public enum Edge {
        ADD, REPLACE
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.NOISE_CLOUD);
    @Persisted(subPersisted = true)
    public final KilaDriver progress = new KilaDriver(0.35f);
    @Persisted
    public float softness = 0.05f;
    @Persisted
    public float sharpen = 1;
    @Persisted(subPersisted = true)
    public final KilaDriver edgeWidth = new KilaDriver(0.08f);
    @Persisted
    public HDRColor edgeColor = new HDRColor(1f, 0.45f, 0.12f, 1f, 3f);
    @Persisted
    public Edge edge = Edge.ADD;
    @Persisted
    public boolean edgeTint;
    /** colours the edge across its width; the edge colour tints and brightens it */
    @Persisted
    public boolean edgeGradient;
    /** left = where it burns through, right = where it meets what is left; its alpha fades the band into the rest */
    @Persisted(subPersisted = true)
    public final GradientColor edgeGradientColors = new GradientColor(0xFFFFF4C8, 0xFFFFA030, 0xFFB02800, 0x001A0600);
    @Persisted
    public Direction direction = Direction.NONE;
    @Persisted
    public float directionWeight = 0.5f;
    @Persisted
    public boolean directionInvert;
    @Persisted(subPersisted = true)
    public final KilaTexture directionTexture = new KilaTexture(KilaTextures.GRADIENT_LINEAR);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetU = new KilaDriver(0);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetV = new KilaDriver(0);

    private final KilaBakedGradient bakedEdge = new KilaBakedGradient(edgeGradientColors);

    @Override
    public String id() {
        return "dissolve";
    }

    @Override
    public Category category() {
        return Category.ALPHA;
    }

    @Override
    public long structure() {
        return (direction == Direction.TEXTURE ? 1 : 0) | (edgeGradient ? 2 : 0);
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_DISSOLVE").fragmentUvChain("KilaDissolve").vec4("KilaDissolveParams")
                .vec4("KilaDissolveEdgeColor").source("DISSOLVE", "KilaDissolve", texture, true)
                .params(KilaParam.DISSOLVE_PROGRESS, KilaParam.DISSOLVE_OFFSET_U, KilaParam.DISSOLVE_OFFSET_V,
                        KilaParam.DISSOLVE_EDGE);
        if (edgeGradient) layout.define("KILA_DISSOLVE_RAMP").dynamicTexture("DISSOLVE_RAMP", bakedEdge::textureId);
        if (direction == Direction.TEXTURE) {
            layout.define("KILA_DISSOLVE_DIRTEX").vec4("KilaDissolveDirST", 1, 1, 0, 0)
                    .vec4("KilaDissolveDirChannel", 1, 0, 0, 0).texture("DISSOLVE_DIR", directionTexture);
        }
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        texture.uploadUv(shader, "KilaDissolve");
        shader.safeGetUniform("KilaDissolveOpts").set(texture.flags(), 0, direction.ordinal(), edge.ordinal());
        texture.uploadSource(shader, "KilaDissolve", scratch);
        shader.safeGetUniform("KilaDissolveParams").set(softness, sharpen, directionWeight, directionInvert ? 1f : 0f);
        var color = edgeColor.toVector4fOpaque();
        shader.safeGetUniform("KilaDissolveEdgeColor").set(color.x, color.y, color.z, edgeTint ? 1f : 0f);
        if (direction == Direction.TEXTURE) {
            var dir = directionTexture;
            shader.safeGetUniform("KilaDissolveDirST").set(dir.tiling.x, dir.tiling.y, dir.offset.x, dir.offset.y);
            dir.uploadChannel(shader, "KilaDissolveDirChannel", scratch);
        }
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
        out.accept(directionTexture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.DISSOLVE_PROGRESS, progress);
        out.accept(KilaParam.DISSOLVE_OFFSET_U, offsetU);
        out.accept(KilaParam.DISSOLVE_OFFSET_V, offsetV);
        out.accept(KilaParam.DISSOLVE_EDGE, edgeWidth);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.driver(group, "kila.dissolve.progress", progress, 0, 1);
        KilaUI.texture(group, "kila.dissolve.texture", texture, KilaUI.TextureKind.SCALAR, true, true);
        KilaUI.number(group, "kila.dissolve.softness", () -> softness, v -> softness = v, 0.05f, 0, 1);
        KilaUI.number(group, "kila.dissolve.sharpen", () -> sharpen, v -> sharpen = v, 1, 0.05f, 16);
        KilaUI.driver(group, "kila.dissolve.edge_width", edgeWidth, 0, 1);
        KilaUI.hdr(group, "kila.dissolve.edge_color", () -> edgeColor, v -> edgeColor = v, false);
        KilaUI.choice(group, "kila.dissolve.edge", Edge.values(), () -> edge, v -> edge = v);
        KilaUI.bool(group, "kila.dissolve.edge_tint", () -> edgeTint, v -> edgeTint = v);
        KilaUI.bool(group, "kila.dissolve.edge_gradient", () -> edgeGradient, v -> edgeGradient = v);
        KilaUI.showWhen(group, KilaUI.gradient(group, "kila.dissolve.edge_gradient_colors", edgeGradientColors::copy,
                v -> KilaBakedGradient.copy(v, edgeGradientColors)), () -> edgeGradient);
        var dir = KilaUI.subGroup(group, "kila.dissolve.direction", true);
        KilaUI.choice(dir, "kila.dissolve.direction.mode", Direction.values(), () -> direction, v -> direction = v);
        KilaUI.number(dir, "kila.dissolve.direction.weight", () -> directionWeight, v -> directionWeight = v, 0.5f, 0, 1);
        KilaUI.bool(dir, "kila.dissolve.direction.invert", () -> directionInvert, v -> directionInvert = v);
        var textured = new ConfiguratorGroup().hideTitle().setCollapse(false);
        KilaUI.texture(textured, "kila.dissolve.direction.texture", directionTexture, KilaUI.TextureKind.SCALAR, false);
        KilaUI.vec2(textured, "kila.texture.tiling", () -> directionTexture.tiling, v -> directionTexture.tiling = v);
        KilaUI.vec2(textured, "kila.texture.offset", () -> directionTexture.offset, v -> directionTexture.offset = v);
        dir.addConfigurator(textured);
        KilaUI.showWhen(dir, textured, () -> direction == Direction.TEXTURE);
        KilaUI.driver(group, "kila.dissolve.offset_u", offsetU, -16, 16);
        KilaUI.driver(group, "kila.dissolve.offset_v", offsetV, -16, 16);
    }
}
