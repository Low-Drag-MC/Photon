package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib2.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib2.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import com.lowdragmc.photon.gui.editor.MeshesResource;
import com.lowdragmc.photon.gui.editor.FXProject;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Mesh
 */
@LDLRegister(name = "mesh", group = "shape")
public class Mesh implements IShape {
    public enum Type {
        Vertex,
        Edge,
        Triangle
    }

    @Getter
    @Setter
    @Configurable(tips = "photon.emitter.config.shape.mesh.type")
    private Type type = Type.Triangle;

    @Getter
    private final MeshData meshData = new MeshData();

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        Vector3f pos = null;
        var random = particle.getRandomSource();
        var t = random.nextFloat();
        if (type == Type.Vertex) {
            pos = meshData.getRandomVertex(t);
            if (pos != null) {
                pos = new Vector3f(pos);
            }
        } else if (type == Type.Edge) {
            var edge = meshData.getRandomEdge(t);
            if (edge != null) {
                pos = new Vector3f(edge.b).sub(edge.a).mul(random.nextFloat()).add(edge.a);
            }
        } else if (type == Type.Triangle) {
            var triangle = meshData.getRandomTriangle(t);
            if (triangle != null) {
                var sqrtR = (float) Math.sqrt(random.nextFloat());
                var A = (1 - sqrtR);
                var r2 = random.nextFloat();
                var B = (sqrtR * (1 - r2));
                var C = (sqrtR * r2);
                var x = A * triangle.a.x + B * triangle.b.x + C * triangle.c.x;
                var y = A * triangle.a.y + B * triangle.b.y + C * triangle.c.y;
                var z = A * triangle.a.z + B * triangle.b.z + C * triangle.c.z;
                pos = new Vector3f(x, y, z);
            }
        }
        if (pos != null) {
            pos.mul(scale);
            particle.setLocalPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getLocalPos()), true);
            particle.setInternalVelocity(new Vector3f(0, 0, 0));
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IShape.super.buildConfigurator(father);
        if (Editor.INSTANCE != null && Editor.INSTANCE.getCurrentProject() instanceof FXProject project &&
                project.getResources().resources.get("mesh") instanceof MeshesResource meshesResource) {
            var selector = new SelectorConfigurator<>("mesh", () -> meshData.meshName, name -> {
                var mesh = meshesResource.getBuiltinResource(name);
                if (mesh == null) {
                    mesh = meshesResource.getStaticResource(meshesResource.getStaticResourceFile(name));
                }
                if (mesh != null) {
                    meshData.deserializeNBT(mesh.serializeNBT());
                }
            }, meshData.meshName, true, meshesResource.allResources().map(Map.Entry::getKey).map(meshesResource::getResourceName).toList(), String::toString);
            selector.setDraggingConsumer(
                    o -> o instanceof MeshData,
                    o -> selector.getSelector().setButtonBackground(ColorPattern.GREEN.rectTexture().setRadius(5)),
                    o -> selector.getSelector().setButtonBackground(ColorPattern.T_GRAY.rectTexture().setRadius(5)),
                    o -> {
                        if (o instanceof MeshData m) {
                            this.meshData.deserializeNBT(m.serializeNBT());
                        }
                        selector.getSelector().setButtonBackground(ColorPattern.T_GRAY.rectTexture().setRadius(5));
                    });
            selector.setTips("photon.emitter.config.shape.mesh.mesh");
            father.addConfigurators(selector);
        } else {
            ImageWidget imageWidget;
            IGuiTexture texture;
            var wrapper = new WrapperConfigurator("", imageWidget = new ImageWidget(0, 0, 200, 10, new GuiTextureGroup(texture = ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("meshName").setType(TextTexture.TextType.ROLL_ALWAYS).setWidth(200))));
            wrapper.setTips("drag a mesh.");
            imageWidget.setDraggingConsumer(
                    o -> o instanceof MeshData,
                    o -> texture.setColor(ColorPattern.GREEN.color),
                    o -> texture.setColor(ColorPattern.T_GRAY.color),
                    o -> {
                        if (o instanceof MeshData m) {
                            this.meshData.deserializeNBT(m.serializeNBT());
                        }
                        texture.setColor(ColorPattern.T_GRAY.color);
                    });
            father.addConfigurators(wrapper);
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = IShape.super.serializeNBT();
        tag.put("mesh", meshData.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IShape.super.deserializeNBT(tag);
        meshData.deserializeNBT(tag.getCompound("mesh"));
    }

}
