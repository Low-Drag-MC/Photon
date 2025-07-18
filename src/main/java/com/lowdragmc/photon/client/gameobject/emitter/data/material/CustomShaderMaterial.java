package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.shader.LDProgramDefineManager;
import com.lowdragmc.lowdraglib2.client.shader.LDShaderInstance;
import com.lowdragmc.lowdraglib2.configurator.ConfiguratorParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.gui.texture.DynamicTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonShaders;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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
import org.appliedenergistics.yoga.YogaAlign;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "custom_shader", registry = "photon:material")
public class CustomShaderMaterial extends ShaderInstanceMaterial {
    // TODO cleaner
    public final static int MAX_SAMPLER = 128;
    public final static int MAX_SAMPLING = 128;

    @Getter
    @Persisted
    private ResourceLocation shaderLocation = Photon.id("circle");
    @Configurable(subConfigurable = true)
    public final CurveTexture curveTexture = new CurveTexture(MAX_SAMPLING, MAX_SAMPLER);
    //runtime
    private final Map<String, LDShaderInstance> shaders = new HashMap<>();
    @Nullable
    private LDShaderInstance shaderInstance;
    @Getter
    private String compiledErrorMessage = "";
    private boolean requireCurveTexture = false;

    public CustomShaderMaterial() {}

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
        if (requireCurveTexture && getShader(context) instanceof LDShaderInstance shader) {
            var texture = curveTexture.getCurveTexture();
            if (shader.getShaderInstanceAccessor().getSamplerMap().get("SamplerCurve") != texture) {
                shader.setSampler("SamplerCurve", texture);
            }
        }
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
        if (shaderInstance != null) {
            shaderData.put("shaderData", shaderInstance.serializeNBT(provider));
        }
        return shaderData;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        if (!(tag instanceof CompoundTag shaderData)) return;
        recompile();
        if (shaderInstance != null) {
            shaderInstance.deserializeNBT(provider, shaderData.getCompound("shaderData"));
        }
    }

    public boolean isCompiledError() {
        return !compiledErrorMessage.isEmpty();
    }

    public void recompile() {
        compiledErrorMessage = "";
        requireCurveTexture = false;

        if (shaderInstance != null && !isCompiledError()) {
            shaderInstance.close();
        }
        shaders.values().forEach(LDShaderInstance::close);
        shaders.clear();
        try {
            this.shaderInstance = loadShaderInstance(shaderLocation, null);
            var samplerNames = shaderInstance.getShaderInstanceAccessor().getSamplerNames();
            if (samplerNames.contains("SamplerCurve")) {
                requireCurveTexture = true;
            }
        } catch (Throwable e) {
            Photon.LOGGER.error("Failed to recompile shader", e);
            compiledErrorMessage = e.getMessage();
            shaderInstance = null;
        }
    }

    private LDShaderInstance loadShaderInstance(ResourceLocation shaderLocation, @Nullable String define) throws Throwable {
        if (define != null) {
            LDProgramDefineManager.addProgramDefine(define);
        }
        var shader = new LDShaderInstance(Minecraft.getInstance().getResourceManager(), shaderLocation, DefaultVertexFormat.BLOCK);
        var samplerNames = shader.getShaderInstanceAccessor().getSamplerNames();
        if (samplerNames.contains("SamplerBlockAtlas")) {
            var texture = Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
            shader.setSampler("SamplerBlockAtlas", texture);
        }
        if (define != null) {
            LDProgramDefineManager.removeProgramDefine(define);
        }
        return shader;
    }

    @Override
    public ShaderInstance getShader(MaterialContext context) {
        if (shaderInstance == null) {
            if (isCompiledError()) {
                return PhotonShaders.getHDRParticleShader();
            }
            recompile();
        }
        if (shaderInstance == null) {
            return PhotonShaders.getHDRParticleShader();
        }
        if (context.getShaderDefine().isEmpty()) {
            return shaderInstance;
        }
        return shaders.computeIfAbsent(context.getShaderDefine(), define -> {
            // remove cache
            Program.Type.FRAGMENT.getPrograms().remove(shaderInstance.getFragmentProgram().getName());
            Program.Type.VERTEX.getPrograms().remove(shaderInstance.getVertexProgram().getName());
            var data = shaderInstance.serializeNBT(Platform.getFrozenRegistry());
            try {
                var defineShader = loadShaderInstance(shaderLocation, define);
                defineShader.deserializeNBT(Platform.getFrozenRegistry(), data);
                return defineShader;
            } catch (Throwable e) {
                Photon.LOGGER.error("Failed to recompile shader", e);
            }
            return shaderInstance;
        });
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
        shaderConfigurator.addEventListener(Configurator.CHANGE_EVENT, e -> {
            shaders.values().forEach(LDShaderInstance::close);
            shaders.clear();
        });

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
                    if (shaderInstance != null) {
                        previousData = shaderInstance.serializeNBT(Platform.getFrozenRegistry());
                    }
                    recompile();
                    if (previousData != null && shaderInstance != null) {
                        shaderInstance.deserializeNBT(Platform.getFrozenRegistry(), previousData);
                    }
                    reloadShaderConfigurator(shaderConfigurator);
                }).setText("photon.reload_shader").layout(layout -> layout.setAlignSelf(YogaAlign.CENTER)));

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
                }).layout(layout -> layout.setAlignSelf(YogaAlign.CENTER)));

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
        if (shaderInstance != null) {
            shaderInstance.buildConfigurator(shaderConfigurator);
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
