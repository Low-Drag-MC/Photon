package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.math.Size;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import com.mojang.blaze3d.vertex.*;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MeshData implements IConfigurable, IPersistedSerializable {
    @Getter
    @Persisted
    private IModelSource source = new JsonModelSource();
    // runtime: sampling geometry derived from the source's mesh. derivedFrom tracks which
    // PhotonMesh instance the lists were built from — the shared cache hands out a fresh instance
    // after any invalidation (reload listener, reload button, file polling), so an identity
    // compare is all the staleness detection needed.
    @Nullable
    private volatile PhotonMesh derivedFrom = null;
    private final List<Vector3f> vertices = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Triangle> triangles = new ArrayList<>();
    @Getter
    private double edgeSumLength;
    @Getter
    private double triangleSumArea;

    public MeshData() {
    }

    public MeshData(CompoundTag nbt) {
        deserializeNBT(Platform.getFrozenRegistry(), nbt);
    }

    public MeshData(Identifier modelLocation) {
        this(new JsonModelSource(modelLocation));
    }

    public MeshData(IModelSource source) {
        this.source = source;
    }

    public void setSource(IModelSource source) {
        this.source = source;
        clearDerived();
    }

    private synchronized void clearDerived() {
        derivedFrom = null;
        vertices.clear();
        edges.clear();
        triangles.clear();
        edgeSumLength = 0;
        triangleSumArea = 0;
    }

    private void ensureLoaded() {
        var mesh = source.getMesh();
        if (mesh == derivedFrom) return;
        synchronized (this) {
            if (mesh == derivedFrom) return; // rebuilt by a parallel-sim worker meanwhile
            vertices.clear();
            edges.clear();
            triangles.clear();
            rebuildFrom(mesh);
            derivedFrom = mesh;
        }
    }

    private void rebuildFrom(PhotonMesh mesh) {
        double sumLength = 0;
        double sumArea = 0;
        var data = mesh.vertices();
        var points = new Vector3f[4];
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            // degenerate quads (corner 3 == corner 2) are real triangles: 3 vertices, 3 edges, 1
            // triangle — so duplicate corners/edges don't skew the weighted sampling
            boolean triangle = mesh.isTriangle(quad);
            int corners = triangle ? 3 : 4;
            for (int corner = 0; corner < corners; corner++) {
                int off = PhotonMesh.vertexOffset(quad, corner);
                points[corner] = new Vector3f(data[off], data[off + 1], data[off + 2]);
                this.vertices.add(points[corner]);
            }
            if (triangle) {
                sumLength += addEdge(points[0], points[1]);
                sumLength += addEdge(points[1], points[2]);
                sumLength += addEdge(points[2], points[0]);
                sumArea += addTriangle(points[0], points[1], points[2]);
            } else {
                sumLength += addEdge(points[0], points[1]);
                sumLength += addEdge(points[1], points[2]);
                sumLength += addEdge(points[2], points[3]);
                sumLength += addEdge(points[3], points[0]);
                sumLength += addEdge(points[1], points[3]);
                sumArea += addTriangle(points[0], points[1], points[2]);
                sumArea += addTriangle(points[2], points[3], points[0]);
            }
        }
        this.edgeSumLength = sumLength;
        this.triangleSumArea = sumArea;
    }

    public List<Vector3f> getVertices() {
        ensureLoaded();
        return vertices;
    }

    public List<Edge> getEdges() {
        ensureLoaded();
        return edges;
    }

    public List<Triangle> getTriangles() {
        ensureLoaded();
        return triangles;
    }

    @Nullable
    public Vector3f getRandomVertex(float t) {
        ensureLoaded();
        if (vertices.isEmpty()) return null;
        return vertices.get((int) (vertices.size() * t));
    }

    @Nullable
    public Edge getRandomEdge(float t) {
        ensureLoaded();
        if (edges.isEmpty()) return null;
        var l = t * edgeSumLength;
        var cl = 0d;
        for (Edge edge : edges) {
            if (l <= edge.length + cl) {
                return edge;
            }
            cl += edge.length;
        }
        return edges.getLast();
    }

    @Nullable
    public Triangle getRandomTriangle(float t) {
        ensureLoaded();
        if (triangles.isEmpty()) return null;
        var a = t * triangleSumArea;
        var ca = 0d;
        for (var triangle : triangles) {
            if (a <= triangle.area + ca) {
                return triangle;
            }
            ca += triangle.area;
        }
        return triangles.getLast();
    }

    private double addEdge(Vector3f a, Vector3f b) {
        var ab = new Edge(a, b);
        if (ab.length > 0) {
            edges.add(ab);
        }
        return ab.length;
    }

    private double addTriangle(Vector3f a, Vector3f b, Vector3f c) {
        var abc = new Triangle(a, b, c);
        if (abc.area > 0) {
            triangles.add(abc);
        }
        return abc.area;
    }

    @Override
    public void deserialize(@NotNull ValueInput input) {
        IPersistedSerializable.super.deserialize(input);
        if (input.child("source").isEmpty()) {
            // legacy (pre-v5) payloads store a bare json model id; editor resource files and pasted
            // NBT bypass the project datafixer, so keep these in-place fallbacks
            var modelLocation = input.getString("modelLocation");
            if (modelLocation.isPresent()) {
                source = new JsonModelSource(Identifier.parse(modelLocation.get()));
            } else if (input.getString("type").isPresent()) {
                // a bare IModelSource wrapper {type, data} (renderer payloads before MeshData wrapping)
                source = IModelSource.deserializeWrapper(toCompound(input));
            }
        }
        clearDerived();
    }

    private static CompoundTag toCompound(ValueInput input) {
        var tag = new CompoundTag();
        for (var key : input.keySet()) {
            input.read(key, ExtraCodecs.NBT).ifPresent(value -> tag.put(key, value));
        }
        return tag;
    }

    /** Tag-level bridge kept for editor resources and copy/paste payloads (see {@code MeshResource}). */
    public CompoundTag serializeNBT(HolderLookup.@NotNull Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, provider);
            serialize(output);
            return output.buildResult();
        }
    }

    /** Tag-level bridge kept for editor resources and copy/paste payloads (see {@code MeshResource}). */
    public void deserializeNBT(HolderLookup.@NotNull Provider provider, @NotNull CompoundTag nbt) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            deserialize(TagValueInput.create(reporter, provider, nbt));
        }
    }

    /**
     * Edge (px) of a tile preview's own FBO. One target per tile, so this is the whole memory story:
     * edge² × (4 B colour + 4 B depth), live for as long as the tile is in the panel.
     * <p>
     * 1.21 used 512 here, which was ~18× the linear size it is ever drawn at — the tile box is
     * {@code Resource#defaultUIWidth} = 30 GUI units, i.e. 120 physical px even at GUI scale 4. 128
     * covers that with no upscaling and costs 128 KB instead of 2 MB per tile; a panel of 20 meshes
     * is 2.5 MB rather than 40 MB.
     */
    private static final int TILE_FBO_SIZE = 128;

    /**
     * The preview as an inspector/inline slot expects it: an inset, bordered square, orbitable with
     * the mouse, drawn by the immediate renderer (there is only ever one or two on screen).
     */
    public Scene createInspectorPreview() {
        var scene = buildPreview(null);
        scene.layout(layout -> {
            layout.setAspectRatio(1.0f);
            layout.widthPercent(80);
            layout.alignSelf(AlignItems.CENTER);
            layout.paddingAll(3);
        });
        scene.style(style -> style.backgroundTexture(Sprites.BORDER1_RT1));
        scene.moveInlineAsDefault();
        scene.addClass("preview_bg");
        return scene;
    }

    /**
     * The resource panel's tile: its own fixed-size FBO rather than the immediate renderer, because a
     * panel holds dozens of these and rendering each at GUI resolution every frame is what 1.21
     * avoided by going through an FBO.
     * <p>
     * Not interactive: a tile has to stay draggable (that is how a mesh is dragged onto an emitter)
     * and clickable to select, but {@link Scene} otherwise claims MOUSE_DOWN/WHEEL/DRAG to orbit its
     * camera and swallows both. Orbiting is a feature of the inspector preview, not of a 30px tile.
     */
    public Scene createTilePreview() {
        var scene = buildPreview(Size.of(TILE_FBO_SIZE, TILE_FBO_SIZE));
        scene.setIntractable(false);
        return scene;
    }

    /** A wireframe preview of this mesh, auto-framed to whatever geometry the source yields.
     *  {@code fboSize} non-null selects the FBO renderer at that resolution. */
    private Scene buildPreview(@Nullable Size fboSize) {
        var scene = new Scene();
        var level = new TrackedDummyWorld();
        level.addBlock(BlockPos.ZERO, BlockInfo.fromBlock(Blocks.AIR));
        scene.setRenderFacing(false);
        scene.setRenderSelect(false);
        // the preview world is a single AIR block and the wireframe is drawn from MeshData, not from
        // world content — ticking it every tick, once per preview on screen, buys nothing
        scene.setTickWorld(false);
        scene.createScene(level, fboSize != null, fboSize);
        var renderer = scene.<WorldSceneRenderer>getRenderer();
        assert renderer != null;
        // createScene wires a hover callback, and a non-null one makes every frame ray-trace the
        // world. Nothing here consumes hover state (renderFacing/renderSelect/showHoverBlockTips are
        // all off), so drop it — 1.21 did this explicitly ("better performance") and the port lost it.
        renderer.setOnLookingAt(null);
        if (fboSize != null) {
            renderer.setFov(40); // 1.21's tile framing
        }
        scene.setRenderedCore(Collections.singleton(BlockPos.ZERO), null);
        // Re-frame whenever the underlying geometry changes (obj set/hot-reloaded, source switched):
        // the scene isn't rebuilt on every edit and drawLineFrames reads the live mesh each frame, so a
        // fixed build-time camera would leave edits off-screen until the panel is rebuilt. The shared
        // cache hands out a fresh PhotonMesh after any invalidation, so an identity compare is enough.
        var framed = new PhotonMesh[]{null};
        scene.setBeforeWorldRender(s -> {
            var mesh = source.getMesh();
            if (mesh == framed[0]) return;
            framed[0] = mesh;
            frame(s);
        });
        scene.setAfterWorldRender(s -> drawLineFrames(new PoseStack()));
        return scene;
    }

    /** Point the preview camera at the mesh's bounding box, so any model size fills the slot. */
    private void frame(Scene scene) {
        var min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        var max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        var vertices = getVertices();
        if (vertices.isEmpty()) {
            min.set(0, 0, 0);
            max.set(1, 1, 1);
        } else {
            for (var vertex : vertices) {
                min.min(vertex);
                max.max(vertex);
            }
        }
        // +0.5 for the same reason drawLineFrames adds it: mesh space is centered, the preview
        // block spans 0..1
        scene.setCenter(new Vector3f((min.x + max.x) / 2f + 0.5F,
                (min.y + max.y) / 2f + 0.5F,
                (min.z + max.z) / 2f + 0.5F));
        var extent = Math.max(Math.max(max.x - min.x, max.y - min.y), max.z - min.z) + 1;
        scene.setZoom((float) (3.5 * Math.sqrt(Math.max(extent, 1))));
        scene.setCameraYawAndPitch(-135, 25);
    }

    // 26.1: immediate Tesselator+BufferUploader draws are gone — batch through the shared buffer
    // source with the vanilla lines render type (blend/depth/width owned by the pipeline).
    public void drawLineFrames(PoseStack poseStack) {
        var edges = getEdges();
        if (edges.isEmpty()) return;
        var pose = poseStack.last();
        var mat = pose.pose();
        var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        var buffer = bufferSource.getBuffer(RenderTypes.lines());

        for (var edge : edges) {
            var a = edge.a;
            var b = edge.b;
            float f = b.x - a.x;
            float f1 = b.y - a.y;
            float f2 = b.z - a.z;
            float f3 = Mth.sqrt(f * f + f1 * f1 + f2 * f2);
            f /= f3;
            f1 /= f3;
            f2 /= f3;

            // +0.5: mesh space is centered, the preview block spans 0..1 (origin sits at block center)
            buffer.addVertex(mat, a.x + 0.5f, a.y + 0.5f, a.z + 0.5f).setColor(-1).setNormal(poseStack.last(), f, f1, f2).setLineWidth(10);
            buffer.addVertex(mat, b.x + 0.5f, b.y + 0.5f, b.z + 0.5f).setColor(-1).setNormal(poseStack.last(), f, f1, f2).setLineWidth(10);
        }

        bufferSource.endBatch();
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        father.addConfigurators(new Configurator("ldlib.gui.editor.group.preview").addChild(createInspectorPreview()));
        father.addConfigurator(new ConfiguratorSelectorConfigurator<>(
                "photon.model_source",
                () -> source.name(),
                name -> setSource(PhotonRegistries.MODEL_SOURCES.get(name).value().get()),
                "json_model",
                true,
                // resource_mesh is a live reference, not a first-class geometry source — it exists only
                // for drag/dialog picks, so a resource shouldn't be able to reference another resource
                PhotonRegistries.MODEL_SOURCES.keys().stream().filter(k -> !k.equals("resource_mesh")).toList(),
                s -> "photon.model_source." + s,
                (name, group) -> source.buildConfigurator(group)));
    }

    public static class Edge {

        public final Vector3f a, b;

        public final double length;

        public Edge(Vector3f a, Vector3f b) {
            this.a = a;
            this.b = b;
            length = new Vector3f(a).sub(b).length();
        }
    }

    public static class Triangle {

        public final Vector3f a, b, c;

        public final double area;

        public Triangle(Vector3f a, Vector3f b, Vector3f c) {
            this.a = a;
            this.b = b;
            this.c = c;
            var nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y);
            var ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
            var nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            area = 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MeshData meshData = (MeshData) o;
        return source.equals(meshData.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }
}
