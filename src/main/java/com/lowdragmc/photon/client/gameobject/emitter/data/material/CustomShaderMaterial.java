package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderHolder;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.PhotonShaders;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.lang.ref.Cleaner;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "custom_shader", registry = "photon:material")
public class CustomShaderMaterial extends ShaderInstanceMaterial {
    public final static int MAX_SAMPLER = 128;
    public final static int MAX_SAMPLING = 128;

    @Getter
    @Persisted
    private ResourceLocation shaderLocation = Photon.id("circle");
    @Configurable(name = "SamplerCurve", subConfigurable = true)
    public final CurveTexture curveTexture = new CurveTexture(MAX_SAMPLING, MAX_SAMPLER);
    @Configurable(name = "SamplerGradient", subConfigurable = true)
    public final GradientTexture gradientTexture = new GradientTexture(MAX_SAMPLING, MAX_SAMPLER);
    @Nullable
    private LDShaderHolder shaderHolder;
    private Cleaner.Cleanable shaderCleanable;
    @Getter
    private String compiledErrorMessage = "";

    public CustomShaderMaterial() {
    }

    public CustomShaderMaterial(ResourceLocation shaderLocation) {
        this.shaderLocation = shaderLocation;
    }

    public void setShader(ResourceLocation shaderLocation) {
        this.shaderLocation = shaderLocation;
        recompile();
    }

    @Override
    public void setupUniform(MaterialContext context) {
        super.setupUniform(context);
    }

    @Override
    public IMaterial copy() {
        var copied = new CustomShaderMaterial(shaderLocation);
        var data = serializeAdditionalNBT(Platform.getFrozenRegistry());
        copied.deserializeAdditionalNBT(data, Platform.getFrozenRegistry());
        return copied;
    }

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        var shaderData = new CompoundTag();
        if (shaderHolder != null) {
            shaderData.put("shaderData", shaderHolder.serializeNBT(provider));
        }
        return shaderData;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        if (!(tag instanceof CompoundTag shaderData)) return;
        recompile();
        if (shaderHolder != null) {
            shaderHolder.deserializeNBT(provider, shaderData.getCompound("shaderData"));
            attachDynamicSamplers(shaderHolder);
            attachDynamicUniforms(shaderHolder);
        }
    }

    public boolean isCompiledError() {
        return !compiledErrorMessage.isEmpty();
    }

    public void recompile() {
        compiledErrorMessage = "";

        if (shaderCleanable != null) {
            shaderCleanable.clean();
            shaderCleanable = null;
        }
        if (shaderHolder != null) {
            this.shaderHolder = null;
        }

        try {
            this.shaderHolder = loadShaderHolder(shaderLocation);
            this.shaderCleanable = AutoCloseCleaner.registerRenderThread(this, this.shaderHolder);
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to recompile shader", e);
            // Never blank, and never null: a blank message reads as "no error", and this flag is the
            // only thing stopping getShader() from re-running the whole compile on every frame it
            // draws — which would build (and strand) a GL program per frame per material.
            // Throwable#getMessage() is null for plenty of exceptions, NPE among them.
            this.compiledErrorMessage = describeFailure(e);
            this.shaderCleanable = null;
        }
    }

    private static String describeFailure(Throwable e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private LDShaderHolder loadShaderHolder(ResourceLocation shaderLocation) throws Throwable {
        var shaderHolder = LDShaderHolder.create(shaderLocation, DefaultVertexFormat.BLOCK);
        if (shaderHolder == null) throw new IllegalStateException("Failed to find shader " + shaderLocation);
        try {
            var shader = shaderHolder.baseInstance;
            var samplerNames = shader.getShaderInstanceAccessor().getSamplerNames();
            if (samplerNames.contains("SamplerBlockAtlas")) {
                var texture = Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
                shader.setSampler("SamplerBlockAtlas", texture);
            }
            attachDynamicSamplers(shaderHolder);
            attachDynamicUniforms(shaderHolder);
            return shaderHolder;
        } catch (Throwable e) {
            // the holder owns a linked GL program by this point, and the caller only ever sees the
            // exception — dropping it here would strand the program for the life of the process
            shaderHolder.close();
            throw e;
        }
    }

    private void attachDynamicSamplers(LDShaderHolder shaderHolder) {
        var shader = shaderHolder.baseInstance;
        var samplerNames = shader.getShaderInstanceAccessor().getSamplerNames();
        if (samplerNames.contains("SamplerCurve")) {
            shaderHolder.addDynamicSampler("SamplerCurve", curveTexture::getCurveTexture);
        }
        if (samplerNames.contains("SamplerGradient")) {
            shaderHolder.addDynamicSampler("SamplerGradient", gradientTexture::getGradientTexture);
        }
        if (samplerNames.contains("SamplerSceneColor")) {
            shaderHolder.addDynamicSampler("SamplerSceneColor", () -> Optional.ofNullable(RenderPassPipeline.getCurrent())
                    .map(pipeline -> pipeline.getSceneSamplers().colorTexture()).orElse(-1));
        }
        if (samplerNames.contains("SamplerSceneDepth")) {
            shaderHolder.addDynamicSampler("SamplerSceneDepth", () -> Optional.ofNullable(RenderPassPipeline.getCurrent())
                    .map(pipeline -> pipeline.getSceneSamplers().depthTexture()).orElse(-1));
        }
    }

    private void attachDynamicUniforms(LDShaderHolder shaderHolder) {
        var shader = shaderHolder.baseInstance;
        var uniformNames = shader.getShaderInstanceAccessor().getUniformMap().keySet();
        if (uniformNames.contains("U_CameraPosition")) {
            shaderHolder.addDynamicUniform("U_CameraPosition", uniform -> {
                if (RenderPassPipeline.getCurrent() != null) {
                    var camera = RenderPassPipeline.getCurrent().getCamera();
                    if (camera != null) {
                        var pos = camera.getPosition();
                        uniform.set((float) pos.x, (float) pos.y, (float) pos.z);
                    }
                }
            });
        }
        if (uniformNames.contains("U_InverseProjectionMatrix")) {
            shaderHolder.addDynamicUniform("U_InverseProjectionMatrix", uniform -> {
                uniform.set(RenderSystem.getProjectionMatrix().invert(new Matrix4f()));
            });
        }
        if (uniformNames.contains("U_InverseViewMatrix")) {
            shaderHolder.addDynamicUniform("U_InverseViewMatrix", uniform -> {
                uniform.set(RenderSystem.getModelViewMatrix().invert(new Matrix4f()));
            });
        }
        if (uniformNames.contains("U_ViewPort")) {
            shaderHolder.addDynamicUniform("U_ViewPort", uniform -> {
                uniform.set(new Vector4f(
                        GlStateManager.Viewport.x(), GlStateManager.Viewport.y(),
                        GlStateManager.Viewport.width(), GlStateManager.Viewport.height()
                ));
            });
        }
    }

    @Override
    public ShaderInstance getShader(MaterialContext context) {
        if (shaderHolder == null) {
            if (isCompiledError()) {
                return PhotonShaders.getHDRParticleShader();
            }
            recompile();
        }
        if (shaderHolder == null) {
            return PhotonShaders.getHDRParticleShader();
        }
        if (context.getShaderDefine().isEmpty()) {
            return shaderHolder.getShaderInstance();
        }
        return shaderHolder.getShaderInstance(Set.of(context.getShaderDefine()));
    }

    @Override
    public IGuiTexture preview() {
        return DynamicTexture.of(() -> isCompiledError() ?
                new TextTexture(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage, 0xffff0000) :
                preview);
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        createPreview(father);

        var configurator = new Configurator();
        var shaderConfigurator = new ConfiguratorGroup("photon.shader.settings");
        shaderConfigurator.setCollapse(false);
        shaderConfigurator.setCanCollapse(false);

        var shaderLocationField = new StringConfigurator("photon.shader",
                () -> shaderLocation.toString(),
                s -> {
                    setShader(ResourceLocation.parse(s));
                    reloadShaderConfigurator(shaderConfigurator);
                    configurator.notifyChanges();
                },
                shaderLocation.toString(),
                true).setResourceLocation(true);

        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    CompoundTag previousData = null;
                    if (shaderHolder != null) {
                        previousData = shaderHolder.serializeNBT(Platform.getFrozenRegistry());
                    }
                    recompile();
                    if (previousData != null && shaderHolder != null) {
                        shaderHolder.deserializeNBT(Platform.getFrozenRegistry(), previousData);
                        attachDynamicSamplers(shaderHolder);
                        attachDynamicUniforms(shaderHolder);
                    }
                    reloadShaderConfigurator(shaderConfigurator);
                }).setText("photon.reload_shader").layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        configurator.inlineContainer.addChild( // button to select shader
                new Button().setText("photon.select_shader").setOnClick(e -> {
                    var mui = e.currentElement.getModularUI();
                    if (mui == null) return;
                    Dialog.showFileDialog("photon.select_shader", LDLib2.getAssetsDir(), true, Dialog.suffixFilter(".json"), r -> {
                        if (r != null && r.isFile()) {
                            var location = getShaderFromFile(r);
                            if (location == null) return;
                            setShader(location);
                            reloadShaderConfigurator(shaderConfigurator);
                            configurator.notifyChanges();
                        }
                    }).show(mui.ui.rootElement);
                }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        reloadShaderConfigurator(shaderConfigurator);

        father.addConfigurators(
                configurator,
                shaderLocationField,
                reloadButton,
                shaderConfigurator
        );
        ConfiguratorParser.createConfigurators(father, this);
    }

    private void reloadShaderConfigurator(ConfiguratorGroup shaderConfigurator) {
        shaderConfigurator.removeAllConfigurators();
        if (shaderHolder != null) {
            shaderHolder.buildConfigurator(shaderConfigurator);
        }
    }

    @Nullable
    public static ResourceLocation getShaderFromFile(File filePath) {
        String fullPath = filePath.getPath().replace('\\', '/');

        // find the "assets/" directory in the path
        var assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) {
            return null;
        }

        var relativePath = fullPath.substring(assetsIndex + "assets/".length());

        // find mod_id
        var slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) {
            return null;
        }

        var modId = relativePath.substring(0, slashIndex);
        var subPath = relativePath.substring(slashIndex + 1);

        // find shader location
        var shaderIndex = subPath.indexOf("shaders/core/");
        if (shaderIndex == -1) {
            return null;
        }

        var shaderPath = subPath.substring(shaderIndex + "shaders/core/".length());
        if (!shaderPath.endsWith(".json")) {
            return null;
        }

        var location = modId + ":" + shaderPath.substring(0, shaderPath.length() - 5); // remove ".json" suffix

        if (LDLib2.isValidResourceLocation(location)) {
            return ResourceLocation.parse(location);
        }
        return null;
    }
}
