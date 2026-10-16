package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * How the particle takes light: a mesh or sphere normal, bumped by a height or normal map, under a key light or six-way
 * lightmaps (Unity's layout: the main texture holds right, top, back; {@link #sixWayNegative} left, bottom, front).
 */
@OnlyIn(Dist.CLIENT)
public class KilaShading extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaShadingOpts.x). */
    public enum Normal {
        MESH, SPHERE
    }

    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaShadingOpts.y). */
    public enum Bump {
        NONE, HEIGHT, NORMAL_MAP
    }

    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaShadingOpts.z). */
    public enum Model {
        LAMBERT, SIX_WAY
    }

    @Persisted
    public Normal normal = Normal.SPHERE;
    /** 0 is the flat quad, 1 a full half sphere */
    @Persisted
    public float bulge = 1;
    @Persisted
    public Bump bump = Bump.NONE;
    /** height: tenths of a block; normal map: how far it tilts */
    @Persisted
    public float bumpStrength = 1;
    /** DirectX-style normal maps have green pointing down */
    @Persisted
    public boolean flipGreen;
    @Persisted(subPersisted = true)
    public final KilaTexture height = new KilaTexture(KilaTextures.NOISE_CLOUD).noise(KilaTexture.Noise.GRADIENT, 8);
    @Persisted(subPersisted = true)
    public final KilaTexture normalMap = new KilaTexture(KilaTextures.NORMAL_NOISE);
    @Persisted
    public Model model = Model.LAMBERT;
    @Persisted(subPersisted = true)
    public final KilaTexture sixWayNegative = new KilaTexture(KilaTextures.SMOKE6_NEG);
    /** in the world, towards the light */
    @Persisted
    public Vector3f lightDirection = new Vector3f(0.5f, 1f, 0.35f);
    @Persisted
    public HDRColor lightColor = HDRColor.white();
    /** how much light reaches the side turned away */
    @Persisted
    public float ambient = 0.35f;
    /** above 0 the light wraps further round the dark side */
    @Persisted
    public float wrap = 0.3f;

    private final Vector3f viewLight = new Vector3f();

    @Override
    public String id() {
        return "shading";
    }

    @Override
    public Category category() {
        return Category.LIGHTING;
    }

    @Override
    public long structure() {
        return bump.ordinal() | (model == Model.SIX_WAY ? 4 : 0);
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_SHADING").ivec4("KilaShadingOpts").vec4("KilaShadingParams", 1, 1, 0.3f, 0.35f)
                .vec4("KilaKeyLight", 0, 1, 0, 0).vec4("KilaKeyColor", 1, 1, 1, 1);
        if (bump == Bump.HEIGHT) {
            layout.define("KILA_BUMP").define("KILA_BUMP_HEIGHT").fragmentUvChain("KilaBump")
                    .source("BUMP", "KilaBump", height, true);
        } else if (bump == Bump.NORMAL_MAP) {
            layout.define("KILA_BUMP").fragmentUvChain("KilaBump").texture("BUMP", normalMap);
        }
        if (model == Model.SIX_WAY) layout.define("KILA_SIXWAY").texture("SIXWAY", sixWayNegative);
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        shader.safeGetUniform("KilaShadingOpts").set(normal.ordinal(), bump.ordinal(), model.ordinal(), flipGreen ? 1 : 0);
        shader.safeGetUniform("KilaShadingParams").set(bulge, bumpStrength, wrap, ambient);
        // the shader's normals are in view space
        RenderSystem.getModelViewMatrix().transformDirection(lightDirection, viewLight);
        if (viewLight.lengthSquared() > 1e-8f) viewLight.normalize();
        shader.safeGetUniform("KilaKeyLight").set(viewLight.x, viewLight.y, viewLight.z, 0f);
        var key = lightColor.toVector4fOpaque();
        shader.safeGetUniform("KilaKeyColor").set(key.x, key.y, key.z, 1f);
        if (bump == Bump.HEIGHT) {
            height.uploadUv(shader, "KilaBump");
            shader.safeGetUniform("KilaBumpOpts").set(height.flags(), 0, 0, 0);
            height.uploadSource(shader, "KilaBump", scratch);
        } else if (bump == Bump.NORMAL_MAP) {
            normalMap.uploadUv(shader, "KilaBump");
            shader.safeGetUniform("KilaBumpOpts").set(normalMap.flags(), 0, 0, 0);
        }
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        if (bump == Bump.HEIGHT) out.accept(height);
        if (bump == Bump.NORMAL_MAP) out.accept(normalMap);
        if (model == Model.SIX_WAY) out.accept(sixWayNegative);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.choice(group, "kila.shading.normal", Normal.values(), () -> normal, v -> normal = v);
        KilaUI.showWhen(group, KilaUI.number(group, "kila.shading.bulge", () -> bulge, v -> bulge = v, 1, 0, 1),
                () -> normal == Normal.SPHERE);
        KilaUI.choice(group, "kila.shading.bump", Bump.values(), () -> bump, v -> bump = v);
        KilaUI.showWhen(group, KilaUI.number(group, "kila.shading.bump_strength", () -> bumpStrength,
                v -> bumpStrength = v, 1, 0, 16), () -> bump != Bump.NONE);
        var heightGroup = KilaUI.subGroup(group, "kila.shading.height", false);
        KilaUI.texture(heightGroup, "kila.shading.height_texture", height, KilaUI.TextureKind.SCALAR, true, true);
        KilaUI.showWhen(group, heightGroup, () -> bump == Bump.HEIGHT);
        var mapGroup = KilaUI.subGroup(group, "kila.shading.normal_map", false);
        KilaUI.texture(mapGroup, "kila.shading.normal_map_texture", normalMap, KilaUI.TextureKind.VECTOR, true);
        KilaUI.bool(mapGroup, "kila.shading.flip_green", () -> flipGreen, v -> flipGreen = v);
        KilaUI.showWhen(group, mapGroup, () -> bump == Bump.NORMAL_MAP);

        KilaUI.choice(group, "kila.shading.model", Model.values(), () -> model, v -> model = v);
        // read through the main texture's uv and flipbook: no chain of its own
        KilaUI.showWhen(group, KilaUI.add(group, new KilaTextureField("kila.shading.six_way_negative", sixWayNegative,
                KilaUI.TextureKind.COLOR, false), "kila.shading.six_way_negative"), () -> model == Model.SIX_WAY);
        KilaUI.vec3(group, "kila.shading.light_direction", () -> lightDirection, v -> lightDirection = v);
        KilaUI.hdr(group, "kila.shading.light_color", () -> lightColor, v -> lightColor = v, false);
        KilaUI.number(group, "kila.shading.ambient", () -> ambient, v -> ambient = v, 0.35f, 0, 1);
        KilaUI.showWhen(group, KilaUI.number(group, "kila.shading.wrap", () -> wrap, v -> wrap = v, 0.3f, 0, 1),
                () -> model == Model.LAMBERT);
    }
}
