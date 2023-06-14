package com.lowdragmc.photon.client.emitter.data.material;

import com.lowdragmc.lowdraglib.client.shader.Shaders;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ColorConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.NumberConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.utils.ColorUtils;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.photon.core.mixins.accessor.ShaderInstanceAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote CustomShaderMaterial
 */
@Environment(EnvType.CLIENT)
@ParametersAreNonnullByDefault
public class CustomShaderMaterial extends ShaderInstanceMaterial {
    private static final Map<ResourceLocation, ShaderInstance> COMPILED_SHADERS = new HashMap<>();

    @Configurable
    public ResourceLocation shader = new ResourceLocation("photon:circle");

    protected CompoundTag uniformTag = new CompoundTag();

    //runtime
    private String compiledErrorMessage = "";
    private Runnable uniformCache = null;

    public CustomShaderMaterial() {
        var uniforms = new CompoundTag();
        var list = new ListTag();
        list.add(FloatTag.valueOf(0.3f));
        uniforms.put("Radius", list);
        uniformTag.put("uniforms", uniforms);
    }

    public CustomShaderMaterial(ResourceLocation shader) {
        this.shader = shader;
    }

    @Override
    public CompoundTag serializeNBT(CompoundTag tag) {
        tag.putString("shader", shader.toString());
        tag.put("uniform", uniformTag);
        return tag;
    }

    @Override
    public IMaterial copy() {
        var mat = new CustomShaderMaterial(shader);
        mat.uniformTag = uniformTag.copy();
        return mat;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        uniformCache = null;
        shader = new ResourceLocation(nbt.getString("shader"));
        uniformTag = nbt.getCompound("uniform");
        compiledErrorMessage = "";
    }

    public boolean isCompiledError() {
        return getShader() == Shaders.getParticleShader();
    }

    public void recompile() {
        uniformTag = new CompoundTag();
        uniformCache = null;
        compiledErrorMessage = "";
        var removed = COMPILED_SHADERS.remove(this.shader);
        if (removed != null && removed != Shaders.getParticleShader()) {
            removed.close();
        }
    }

    @Override
    public ShaderInstance getShader() {
        return COMPILED_SHADERS.computeIfAbsent(shader, shader -> {
            try {
                return new ShaderInstance(Minecraft.getInstance().getResourceManager(), shader.toString(), DefaultVertexFormat.PARTICLE);
            } catch (Throwable e) {
                compiledErrorMessage = e.getMessage();
            }
            return Shaders.getParticleShader();
        });
    }

    private Runnable combineRunnable(Runnable a, Runnable b) {
        return () -> {
            a.run();
            b.run();
        };
    }

    @Override
    public void setupUniform() {
        if (!isCompiledError()) {
            if (uniformCache != null) {
                uniformCache.run();
            } else if (!uniformTag.isEmpty() && getShader() instanceof ShaderInstanceAccessor shaderInstance) {
                // compile
                uniformCache = () -> {};
                if (uniformTag.contains("samplers")) {
                    var samplers = uniformTag.getCompound("samplers");
                    var samplerNames = new HashSet<>(shaderInstance.getSamplerNames());
                    for (String key : samplers.getAllKeys()) {
                        var index = -1;
                        ResourceLocation texture = null;
                        try {
                            index = Integer.parseInt(key);
                            texture = new ResourceLocation(samplers.getString(key));
                        } catch (Exception ignored) {}
                        if (index >= 0 && texture != null && samplerNames.contains("Sampler" + index)) {
                            final int finalIndex = index;
                            final ResourceLocation finalTexture = texture;
                            uniformCache = combineRunnable(uniformCache, () -> RenderSystem.setShaderTexture(finalIndex, finalTexture));
                        }
                    }
                }
                if (uniformTag.contains("uniforms")) {
                    var uniforms = uniformTag.getCompound("uniforms");
                    var uniformMap = shaderInstance.getUniformMap();
                    for (String key : uniforms.getAllKeys()) {
                        var data = uniforms.getList(key, Tag.TAG_FLOAT);
                        if (uniformMap.containsKey(key) && !data.isEmpty()) {
                            var u = uniformMap.get(key);
                            if (u.getCount() == data.size()) {
                                var type = u.getType();
                                if (type == 4) { // UT_FLOAT1
                                    final float value = data.getFloat(0);
                                    uniformCache = combineRunnable(uniformCache, () -> getShader().safeGetUniform(key).set(value));
                                }
                                if (type == 5) { // UT_FLOAT2
                                    final float[] value = new float[] {data.getFloat(0), data.getFloat(1)};
                                    uniformCache = combineRunnable(uniformCache, () -> getShader().safeGetUniform(key).set(value[0], value[1]));
                                }
                                if (type == 6) { // UT_FLOAT3
                                    final float[] value = new float[] {data.getFloat(0), data.getFloat(1), data.getFloat(2)};
                                    uniformCache = combineRunnable(uniformCache, () -> getShader().safeGetUniform(key).set(value[0], value[1], value[2]));
                                }
                                if (type == 7) { // UT_FLOAT4
                                    final float[] value = new float[] {data.getFloat(0), data.getFloat(1), data.getFloat(2), data.getFloat(3)};
                                    uniformCache = combineRunnable(uniformCache, () -> getShader().safeGetUniform(key).set(value[0], value[1], value[2], value[3]));
                                }
                            }
                        }
                    }
                }
            } else {
                uniformCache = () -> {};
            }
        }
    }

    @Override
    public IGuiTexture preview() {
        return isCompiledError() ? new TextTexture(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage, 0xffff0000) : preview;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        WidgetGroup preview = new WidgetGroup(0, 0, 100, 120);
        WidgetGroup shaderConfigurator = new WidgetGroup(0, 0, 200, 0);
        preview.addWidget(new ImageWidget(0, 0, 100, 100, () -> isCompiledError() ? new TextTexture(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage, 0xffff0000) : this.preview).setBorder(2, ColorPattern.T_WHITE.color));
        preview.addWidget(new ButtonWidget(0, 0, 100, 100, IGuiTexture.EMPTY, cd -> {
            if (Editor.INSTANCE == null) return;
            File path = new File(Editor.INSTANCE.getWorkSpace(), "assets/ldlib/shaders/core");
            DialogWidget.showFileDialog(Editor.INSTANCE, "select a shader config", path, true,
                    DialogWidget.suffixFilter(".json"), r -> {
                        if (r != null && r.isFile()) {
                            shader = new ResourceLocation("ldlib:" + r.getName().substring(0, r.getName().length() - 5));
                            uniformTag = new CompoundTag();
                            uniformCache= null;
                            updateShaderUniformConfigurator(shaderConfigurator);
                            father.computeLayout();
                        }
                    });
        }));
        preview.addWidget(new ButtonWidget(5, 110, 90, 10, new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("recompile")), cd -> {
            recompile();
            updateShaderUniformConfigurator(shaderConfigurator);
            father.computeLayout();
        }));
        updateShaderUniformConfigurator(shaderConfigurator);
        WrapperConfigurator base = new WrapperConfigurator("ldlib.gui.editor.group.shader", preview);
        base.setTips("ldlib.gui.editor.tips.click_select_shader");

        // shader configurator
        father.addConfigurators(base);
        super.buildConfigurator(father);
        father.addConfigurators(new WrapperConfigurator("uniform settings", shaderConfigurator));
    }

    public void updateShaderUniformConfigurator(WidgetGroup group) {
        group.clearAllWidgets();
        if (isCompiledError()) {
            var box = new TextBoxWidget(0, 0, 200, List.of(compiledErrorMessage.isEmpty() ? "error" : compiledErrorMessage)).setFontColor(-1);
            group.addWidget(box);
            group.setSize(new Size(200, box.getSize().height));
        } else {
            int height = 5;
            if (getShader() instanceof ShaderInstanceAccessor shaderAccessor) {
                var samplerNames = shaderAccessor.getSamplerNames();
                for (String samplerName : samplerNames) {
                    if (samplerName.startsWith("Sampler")) {
                        var index = -1;
                        try {
                            index = Integer.parseInt(samplerName.replaceAll("Sampler", ""));
                        } catch (Throwable ignored) {}
                        if (index >= 0 && index != 2) {
                            WidgetGroup preview = new WidgetGroup(50, height + 10, 100, 100);
                            int finalIndex = index;
                            preview.addWidget(new ImageWidget(0, 0, 100, 100, () -> {
                                if (!uniformTag.getCompound("samplers").contains(String.valueOf(finalIndex))) {
                                    return IGuiTexture.EMPTY;
                                } else {
                                    return new ResourceTexture(uniformTag.getCompound("samplers").getString(String.valueOf(finalIndex)));
                                }
                            }).setBorder(2, ColorPattern.T_WHITE.color));
                            preview.addWidget(new ButtonWidget(0, 0, 100, 100, IGuiTexture.EMPTY, cd -> {
                                if (Editor.INSTANCE == null) return;
                                File path = new File(Editor.INSTANCE.getWorkSpace(), "assets/ldlib/textures");
                                DialogWidget.showFileDialog(Editor.INSTANCE, "ldlib.gui.editor.tips.select_image", path, true,
                                        DialogWidget.suffixFilter(".png"), r -> {
                                            if (r != null && r.isFile()) {
                                                var texture = "ldlib:" + r.getPath().replace(path.getPath(), "textures").replace('\\', '/');
                                                uniformCache = null;
                                                var tag = uniformTag.getCompound("samplers");
                                                tag.putString(String.valueOf(finalIndex), texture);
                                                uniformTag.put("samplers", tag);
                                            }
                                        });
                            }));
                            group.addWidget(new LabelWidget(10, height, samplerName));
                            group.addWidget(preview);
                            height += 115;
                        }
                    }
                }
                var uniformMap = shaderAccessor.getUniformMap();
                for (var entry : uniformMap.entrySet()) {
                    var uniformName = entry.getKey();
                    var uniform = entry.getValue();
                    var type = uniform.getType();
                    if (uniformName.equals("GameTime") ||
                            uniformName.equals("FogEnd") ||
                            uniformName.equals("ColorModulator") ||
                            uniformName.equals("FogStart") ||
                            uniformName.equals("FogColor")) continue;
                    if (type == 4) {
                        height = addUniformConfigurator(uniformName, group, 0, height);
                    } else if (type == 5) {
                        height = addUniformConfigurator(uniformName+".x", group, 0, height);
                        height = addUniformConfigurator(uniformName+".y", group, 1, height);
                    } else if (type == 6) {
                        height = addUniformConfigurator(uniformName+".x", group, 0, height);
                        height = addUniformConfigurator(uniformName+".y", group, 1, height);
                        height = addUniformConfigurator(uniformName+".z", group, 2, height);
                    } else if (type == 7) {
                        if (uniformName.toLowerCase().contains("color")) {
                            height = addColorUniformConfigurator(uniformName, group, height);
                        } else {
                            height = addUniformConfigurator(uniformName+".x", group, 0, height);
                            height = addUniformConfigurator(uniformName+".y", group, 1, height);
                            height = addUniformConfigurator(uniformName+".z", group, 2, height);
                            height = addUniformConfigurator(uniformName+".w", group, 3, height);
                        }
                    }
                    height += 5;
                }
            }
            group.setSize(new Size(200, height == 5 ? 0 : height));

        }
    }

    private int addUniformConfigurator(String uniformName, WidgetGroup group, int index, int height) {
        var configurator = new NumberConfigurator(uniformName, () -> {
            if (uniformTag.getCompound("uniforms").getList(uniformName, Tag.TAG_FLOAT).size() < index) {
                return 0;
            } else {
                return uniformTag.getCompound("uniforms").getList(uniformName, Tag.TAG_FLOAT).getFloat(index);
            }
        }, number -> {
            var list = uniformTag.getCompound("uniforms").getList(uniformName, Tag.TAG_FLOAT);
            while (list.size() < index + 1) {
                list.add(FloatTag.valueOf(0));
            }
            list.set(index, FloatTag.valueOf(number.floatValue()));
            var tag = uniformTag.getCompound("uniforms");
            tag.put(uniformName, list);
            uniformCache = null;
            uniformTag.put("uniforms", tag);
        }, 0, true);
        configurator.setRange(-Float.MAX_VALUE, Float.MAX_VALUE);
        configurator.init(200);
        configurator.addSelfPosition(0, height);
        group.addWidget(configurator);
        height += 15;
        return height;
    }

    private int addColorUniformConfigurator(String uniformName, WidgetGroup group, int height) {
        var configurator = new ColorConfigurator(uniformName, () -> {
            var list = uniformTag.getCompound("uniforms").getList(uniformName, Tag.TAG_FLOAT);
            if (list.size() < 3) {
                return 0;
            } else {
                return ColorUtils.color(list.getFloat(3), list.getFloat(0), list.getFloat(1), list.getFloat(2));
            }
        }, number -> {
            var list = uniformTag.getCompound("uniforms").getList(uniformName, Tag.TAG_FLOAT);
            while (list.size() < 4) {
                list.add(FloatTag.valueOf(0));
            }
            var color = number.intValue();
            list.set(0, FloatTag.valueOf(ColorUtils.red(color)));
            list.set(1, FloatTag.valueOf(ColorUtils.green(color)));
            list.set(2, FloatTag.valueOf(ColorUtils.blue(color)));
            list.set(3, FloatTag.valueOf(ColorUtils.alpha(color)));

            var tag = uniformTag.getCompound("uniforms");
            tag.put(uniformName, list);
            uniformCache = null;
            uniformTag.put("uniforms", tag);
        }, 0, true);
        configurator.init(200);
        configurator.addSelfPosition(0, height);
        group.addWidget(configurator);
        height += 15;
        return height;
    }
}
