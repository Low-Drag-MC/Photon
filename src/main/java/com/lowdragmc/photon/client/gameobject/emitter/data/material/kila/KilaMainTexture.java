package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Vector2f;

import java.util.function.BiConsumer;

/** The base colour and shape; its flipbook moves only this texture, not the masks or noise. */
@OnlyIn(Dist.CLIENT)
public class KilaMainTexture extends KilaModule {
    public enum FrameSource {
        /** frames per second, from the effect clock */
        TIME,
        /** the frame driver's 0..1 runs through the whole sheet */
        DRIVER
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.SOFT_CIRCLE);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetU = new KilaDriver(0);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetV = new KilaDriver(0);
    @Persisted
    public boolean flipbook;
    @Persisted
    public int columns = 4;
    @Persisted
    public int rows = 4;
    @Persisted
    public FrameSource frameSource = FrameSource.TIME;
    @Persisted
    public float fps = 12;
    @Persisted
    public float startFrame;
    @Persisted(subPersisted = true)
    public final KilaDriver frame = new KilaDriver(0);
    @Persisted
    public boolean frameBlend;
    /** each particle plays one row of the sheet, picked by its random number */
    @Persisted
    public boolean randomRow;
    @Persisted
    public boolean randomStart;
    /** blended frames pushed along the sheet's motion vectors to meet halfway */
    @Persisted
    public boolean motionVectors;
    @Persisted(subPersisted = true)
    public final KilaTexture motionTexture = new KilaTexture(KilaTextures.SMOKE_FLIP_MV);
    @Persisted
    public float motionStrength = 1;
    @Persisted
    public boolean dispersion;
    @Persisted(subPersisted = true)
    public final KilaDriver dispersionAmount = new KilaDriver(0.01f);
    /** split away from the centre instead of along {@link #dispersionDirection} */
    @Persisted
    public boolean dispersionRadial;
    @Persisted
    public Vector2f dispersionDirection = new Vector2f(1, 0);

    // not saved: the particle's random number, always
    private final KilaDriver seed = new KilaDriver(0).bind(KilaDriver.Source.RANDOM, 1);

    public KilaMainTexture() {
        setEnable(true);
    }

    private boolean randomised() {
        return flipbook && (randomRow || randomStart);
    }

    private boolean motion() {
        return flipbook && frameBlend && motionVectors;
    }

    @Override
    public String id() {
        return "main";
    }

    @Override
    public Category category() {
        return Category.TEXTURE;
    }

    @Override
    public long structure() {
        return (flipbook ? 1 : 0) | (frameBlend ? 2 : 0) | (dispersion ? 4 : 0) | (randomised() ? 8 : 0)
                | (motion() ? 16 : 0);
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_MAIN").fragmentUvChain("KilaMain").texture("MAIN", texture)
                .params(KilaParam.MAIN_OFFSET_U, KilaParam.MAIN_OFFSET_V);
        if (flipbook) {
            layout.define("KILA_FLIPBOOK").vec4("KilaFlipbook", 4, 4, 12, 0).params(KilaParam.MAIN_FRAME);
            if (frameBlend) layout.define("KILA_FLIPBOOK_BLEND");
            if (randomised()) layout.define("KILA_FLIPBOOK_RANDOM").ivec4("KilaFlipbookOpts").params(KilaParam.FLIPBOOK_SEED);
            if (motion()) layout.define("KILA_FLIPBOOK_MOTION").vec4("KilaFlipbookMotion", 1, 0, 0, 0).texture("MOTION", motionTexture);
        }
        if (dispersion) layout.define("KILA_DISPERSION").vec4("KilaDispersion", 1, 0, 0, 0).params(KilaParam.DISPERSION_AMOUNT);
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        texture.uploadUv(shader, "KilaMain");
        shader.safeGetUniform("KilaMainOpts").set(texture.flags(), texture.colorMode.ordinal(),
                frameSource == FrameSource.DRIVER ? 1 : 0, texture.cellWrap());
        if (flipbook) {
            shader.safeGetUniform("KilaFlipbook").set((float) Math.max(columns, 1), (float) Math.max(rows, 1), fps, startFrame);
            if (randomised()) shader.safeGetUniform("KilaFlipbookOpts").set(randomRow ? 1 : 0, randomStart ? 1 : 0, 0, 0);
            if (motion()) shader.safeGetUniform("KilaFlipbookMotion").set(motionStrength, 0f, 0f, 0f);
        }
        if (dispersion) {
            shader.safeGetUniform("KilaDispersion").set(dispersionDirection.x, dispersionDirection.y,
                    dispersionRadial ? 1f : 0f, 0f);
        }
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
        if (motion()) out.accept(motionTexture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.MAIN_OFFSET_U, offsetU);
        out.accept(KilaParam.MAIN_OFFSET_V, offsetV);
        if (flipbook && frameSource == FrameSource.DRIVER) out.accept(KilaParam.MAIN_FRAME, frame);
        if (dispersion) out.accept(KilaParam.DISPERSION_AMOUNT, dispersionAmount);
        if (randomised()) out.accept(KilaParam.FLIPBOOK_SEED, seed);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.texture(group, "kila.main.texture", texture, KilaUI.TextureKind.COLOR, true);
        KilaUI.driver(group, "kila.main.offset_u", offsetU, -16, 16);
        KilaUI.driver(group, "kila.main.offset_v", offsetV, -16, 16);
        var sheet = KilaUI.subGroup(group, "kila.main.flipbook", true);
        KilaUI.bool(sheet, "kila.main.flipbook.enable", () -> flipbook, v -> flipbook = v);
        KilaUI.integer(sheet, "kila.main.flipbook.columns", () -> columns, v -> columns = v, 4, 1, 64);
        KilaUI.integer(sheet, "kila.main.flipbook.rows", () -> rows, v -> rows = v, 4, 1, 64);
        KilaUI.choice(sheet, "kila.main.flipbook.source", FrameSource.values(), () -> frameSource, v -> frameSource = v);
        KilaUI.number(sheet, "kila.main.flipbook.fps", () -> fps, v -> fps = v, 12, -240, 240);
        KilaUI.number(sheet, "kila.main.flipbook.start", () -> startFrame, v -> startFrame = v, 0, 0, 4096);
        KilaUI.driver(sheet, "kila.main.flipbook.frame", frame, 0, 1);
        KilaUI.bool(sheet, "kila.main.flipbook.blend", () -> frameBlend, v -> frameBlend = v);
        KilaUI.bool(sheet, "kila.main.flipbook.random_row", () -> randomRow, v -> randomRow = v);
        KilaUI.bool(sheet, "kila.main.flipbook.random_start", () -> randomStart, v -> randomStart = v);
        KilaUI.bool(sheet, "kila.main.flipbook.motion", () -> motionVectors, v -> motionVectors = v);
        // read through the frame's own cell: no uv chain of its own
        KilaUI.showWhen(sheet, KilaUI.add(sheet, new KilaTextureField("kila.main.flipbook.motion_texture", motionTexture,
                KilaUI.TextureKind.VECTOR, false), "kila.main.flipbook.motion_texture"), () -> motionVectors);
        KilaUI.showWhen(sheet, KilaUI.number(sheet, "kila.main.flipbook.motion_strength", () -> motionStrength,
                v -> motionStrength = v, 1, -4, 4), () -> motionVectors);
        var split = KilaUI.subGroup(group, "kila.main.dispersion", true);
        KilaUI.bool(split, "kila.main.dispersion.enable", () -> dispersion, v -> dispersion = v);
        KilaUI.driver(split, "kila.main.dispersion.amount", dispersionAmount, -0.2f, 0.2f);
        KilaUI.bool(split, "kila.main.dispersion.radial", () -> dispersionRadial, v -> dispersionRadial = v);
        KilaUI.vec2(split, "kila.main.dispersion.direction", () -> dispersionDirection, v -> dispersionDirection = v);
    }
}
