package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.client.bakedpipeline.IQuadTransformer;
import com.lowdragmc.lowdraglib.client.model.ModelFactory;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.Vector3;
import lombok.Getter;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/4
 * @implNote MeshData
 */
public class MeshData implements ITagSerializable<CompoundTag>, IConfigurable {
    public String meshName = "";
    public final List<Vector3> vertices = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Triangle> triangles = new ArrayList<>();
    @Getter
    protected double edgeSumLength;
    @Getter
    protected double triangleSumArea;

    public MeshData() {

    }

    public MeshData(ResourceLocation modelLocation) {
        loadFromModel(modelLocation);
    }

    public void clear() {
        vertices.clear();
        edges.clear();
        triangles.clear();
        edgeSumLength = 0;
        triangleSumArea = 0;
        meshName = "";
    }

    public void loadFromModel(ResourceLocation modelLocation) {
        var random = RandomSource.create();
        var bakedModel = ModelFactory.getUnBakedModel(modelLocation).bake(
                ModelFactory.getModeBakery(),
                Material::sprite,
                BlockModelRotation.X0_Y0,
                modelLocation);
        var quads = new ArrayList<>(bakedModel.getQuads(null, null, random));
        for (var side : Direction.values()) {
            quads.addAll(bakedModel.getQuads(null, side, random));
        }
        loadFromQuads(quads);
    }

    public void loadFromQuads(List<BakedQuad> quads) {
        clear();
        double sumLength = 0;
        double sumArea = 0;
        for (var quad : quads) {
            var vertices = quad.getVertices();
            Vector3[] points = new Vector3[4];
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                int offset = vertexIndex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
                points[vertexIndex] = new Vector3(Float.intBitsToFloat(vertices[offset]) - 0.5,
                        Float.intBitsToFloat(vertices[offset + 1]) - 0.5,
                        Float.intBitsToFloat(vertices[offset + 2]) - 0.5);
                // add vertexes
                this.vertices.add(points[vertexIndex]);
            }
            // add edges
            sumLength += addEdge(points[0], points[1]);
            sumLength += addEdge(points[1], points[2]);
            sumLength += addEdge(points[2], points[3]);
            sumLength += addEdge(points[3], points[0]);
            sumLength += addEdge(points[1], points[3]);
            // add triangles
            sumArea += addTriangle(points[0], points[1], points[2]);
            sumArea += addTriangle(points[2], points[3], points[0]);
        }
        this.edgeSumLength = sumLength;
        this.triangleSumArea = sumArea;
    }

    @Nullable
    public Vector3 getRandomVertex(float t) {
        if (vertices.isEmpty()) return null;
        return vertices.get((int) (vertices.size() * t));
    }

    @Nullable
    public Edge getRandomEdge(float t) {
        if (edges.isEmpty()) return null;
        var l = t * edgeSumLength;
        var cl = 0d;
        for (Edge edge : edges) {
            if (l <= edge.length + cl) {
                return edge;
            }
            cl += edge.length;
        }
        return edges.get(edges.size() - 1);
    }

    @Nullable
    public Triangle getRandomTriangle(float t) {
        if (triangles.isEmpty()) return null;
        var a = t * triangleSumArea;
        var ca = 0d;
        for (var triangle : triangles) {
            if (a <= triangle.area + ca) {
                return triangle;
            }
            ca += triangle.area;
        }
        return triangles.get(triangles.size() - 1);
    }

    private double addEdge(Vector3 a, Vector3 b) {
        var ab = new Edge(a, b);
        if (ab.length > 0) {
            edges.add(ab);
        }
        return ab.length;
    }

    private double addTriangle(Vector3 a, Vector3 b, Vector3 c) {
        var abc = new Triangle(a, b, c);
        if (abc.area > 0) {
            triangles.add(abc);
        }
        return abc.area;
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var vertex : vertices) {
            saveVector3(list, vertex);
        }
        tag.put("vertices", list);

        list = new ListTag();
        for (var edge : edges) {
            saveVector3(list, edge.a);
            saveVector3(list, edge.b);
        }
        tag.put("edges", list);

        list = new ListTag();
        for (var triangle : triangles) {
            saveVector3(list, triangle.a);
            saveVector3(list, triangle.b);
            saveVector3(list, triangle.c);
        }
        tag.put("triangles", list);

        tag.putFloat("sl", (float) edgeSumLength);
        tag.putFloat("sa", (float) triangleSumArea);
        tag.putString("meshName", meshName);
        return tag;
    }

    private void saveVector3(ListTag list, Vector3 vec) {
        list.add(FloatTag.valueOf((float) vec.x));
        list.add(FloatTag.valueOf((float) vec.y));
        list.add(FloatTag.valueOf((float) vec.z));
    }

    private Vector3 loadVector3(ListTag list, int index) {
        return new Vector3(list.getFloat(index), list.getFloat(index + 1), list.getFloat(index + 2));
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        clear();
        var list = tag.getList("vertices", Tag.TAG_FLOAT);
        for (int i = 0; i < list.size(); i += 3) {
            this.vertices.add(loadVector3(list, i));
        }

        list = tag.getList("edges", Tag.TAG_FLOAT);
        for (int i = 0; i < list.size(); i += 6) {
            this.edges.add(new Edge(loadVector3(list, i), loadVector3(list, i + 3)));
        }

        list = tag.getList("triangles", Tag.TAG_FLOAT);
        for (int i = 0; i < list.size(); i += 9) {
            this.triangles.add(new Triangle(loadVector3(list, i), loadVector3(list, i + 3), loadVector3(list, i + 6)));
        }

        edgeSumLength = tag.getFloat("sl");
        triangleSumArea = tag.getFloat("sa");
        meshName = tag.getString("meshName");
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        var wrapper = new WrapperConfigurator("", new ButtonWidget(0, 0, 200, 10, new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture().setRadius(5), new TextTexture("meshName").setType(TextTexture.TextType.ROLL_ALWAYS).setWidth(200)),cd -> {
            File path = new File(Editor.INSTANCE.getWorkSpace(), "assets/ldlib/models");
            DialogWidget.showFileDialog(Editor.INSTANCE, "select a model", path, true,
                    DialogWidget.suffixFilter(".json"), r -> {
                        if (r != null && r.isFile()) {
                            var lastName = meshName;
                            var modelLocation = new ResourceLocation("ldlib:" + r.getPath().replace(path.getPath(), "").substring(1).replace(".json", "").replace('\\', '/'));
                            loadFromModel(modelLocation);
                            meshName = lastName;
                        }
                    });
        }));
        wrapper.setTips("click to select a minecraft model file.");
        father.addConfigurators(wrapper);
    }

    public static class Edge {

        public final Vector3 a, b;

        public final double length;

        public Edge(Vector3 a, Vector3 b) {
            this.a = a;
            this.b = b;
            length = a.copy().subtract(b).mag();
        }
    }

    public static class Triangle {

        public final Vector3 a, b, c;

        public final double area;

        public Triangle(Vector3 a, Vector3 b, Vector3 c) {
            this.a = a;
            this.b = b;
            this.c = c;
            var nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y);
            var ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
            var nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            area = 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
    }

}
