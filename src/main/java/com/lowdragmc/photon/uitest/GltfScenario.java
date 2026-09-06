package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.GltfModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.PhotonMesh;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Loading glTF the way the editor does, which the parser's unit tests deliberately do not: through the
 * model-source registry, a {@link Identifier} resolved by the injected resource pack, and the
 * shared mesh cache. Every check here is about that chain — {@code GltfMeshParserTest} already pins how
 * the bytes are decoded.
 *
 * <p>The fixtures are written to the assets directory and deleted afterwards. They are hand-built, which
 * is this scenario's known limit: it proves the plumbing carries whatever the parser produces, not that
 * the parser agrees with a real exporter. Dropping a Blender/Substance export next to these and pointing
 * a source at it is the check that closes that gap.</p>
 */
@LDLRegisterClient(name = "gltf", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class GltfScenario implements UIScenario {

    private static final String DIR = "photon/models/";
    private static final String PREFIX = "uitest_gltf_";
    /** Written by {@link #write}, deleted by the teardown step whatever happens in between. */
    private final List<File> fixtures = new ArrayList<>();

    @Override
    public void define(ScenarioBuilder s) {
        s.step("the registry offers glTF as a model source", ctx -> {
            var keys = PhotonRegistries.MODEL_SOURCES.keys();
            boolean registered = keys.contains("gltf_model");
            ctx.check("gltf_model is registered", registered, "present", keys.toString());
            if (!registered) return;
            var entry = PhotonRegistries.MODEL_SOURCES.get("gltf_model");
            var created = entry == null ? null : entry.value().get();
            ctx.check("the registry builds a GltfModelSource", created instanceof GltfModelSource,
                    "GltfModelSource", created == null ? "null" : created.getClass().getSimpleName());
        })

        .step("a .gltf resolves through the resource manager", ctx -> {
            var location = write("embedded.gltf", triangleGltf(true).getBytes(StandardCharsets.UTF_8));
            var mesh = load(ctx, location, "embedded .gltf");
            if (mesh == null) return;
            ctx.check("one triangle arrived", mesh.quadCount() == 1, 1, mesh.quadCount());
            // -X with w = -1 is what the fixture says and what UV generation would never produce
            ctx.check("the file's tangent survived the whole chain", mesh.tangents()[0] == -1f,
                    -1f, mesh.tangents()[0]);
            ctx.check("the file's handedness survived", mesh.tangents()[3] == -1f, -1f, mesh.tangents()[3]);
        })

        // The binary container only ever ran through the parser from a byte[]; this is it going through
        // the resource manager, where a stray text decode or stream truncation would show up instead.
        .step("a .glb resolves through the resource manager", ctx -> {
            var location = write("binary.glb", glb(triangleGltf(true)));
            var mesh = load(ctx, location, "binary .glb");
            if (mesh == null) return;
            ctx.check("one triangle arrived from the glb", mesh.quadCount() == 1, 1, mesh.quadCount());
            ctx.check("the glb's tangent survived", mesh.tangents()[0] == -1f, -1f, mesh.tangents()[0]);
        })

        .step("a model without TANGENT falls back to generated tangents", ctx -> {
            var location = write("notangent.gltf", triangleGltf(false).getBytes(StandardCharsets.UTF_8));
            var mesh = load(ctx, location, "tangent-less .gltf");
            if (mesh == null) return;
            // the fixture's uv layout makes the derived tangent +X, right-handed
            ctx.check("a tangent was generated", mesh.tangents()[0] == 1f, 1f, mesh.tangents()[0]);
            ctx.check("generated handedness is right-handed", mesh.tangents()[3] == 1f, 1f, mesh.tangents()[3]);
        })

        // A broken model must degrade to an empty mesh, not throw into the render loop.
        .step("an external-buffer .gltf fails softly", ctx -> {
            var json = triangleGltf(true).replaceAll("\"uri\": \"data:[^\"]*\"", "\"uri\": \"sidecar.bin\"");
            var location = write("external.gltf", json.getBytes(StandardCharsets.UTF_8));
            var source = new GltfModelSource(location);
            source.invalidate();
            PhotonMesh mesh = null;
            Throwable thrown = null;
            try {
                mesh = source.getMesh();
            } catch (Throwable e) {
                thrown = e;
            }
            ctx.check("no exception escaped into the caller", thrown == null,
                    "handled", String.valueOf(thrown));
            ctx.check("an unloadable model is empty, not broken geometry",
                    mesh != null && mesh.isEmpty(), "empty mesh", String.valueOf(mesh));
        })

        // The editor's reload button and the file watcher both rely on this.
        .step("editing the file on disk and invalidating re-reads it", ctx -> {
            var location = write("hotreload.gltf", triangleGltf(false).getBytes(StandardCharsets.UTF_8));
            var source = new GltfModelSource(location);
            source.invalidate();
            var before = source.getMesh();
            ctx.check("the first read loaded", !before.isEmpty(), "loaded", "empty");

            var file = new File(LDLib2.getAssetsDir(), DIR + PREFIX + "hotreload.gltf");
            try {
                Files.writeString(file.toPath(), triangleGltf(true));
            } catch (IOException e) {
                ctx.check("the fixture could be rewritten", false, "written", String.valueOf(e.getMessage()));
                return;
            }
            var cached = source.getMesh();
            ctx.check("the cache holds the old mesh until invalidated", cached == before,
                    "same instance", "re-read early");

            source.invalidate();
            var after = source.getMesh();
            ctx.check("invalidating hands out a fresh instance", after != before,
                    "new instance", "same instance");
            // consumers detect staleness by identity, so a fresh instance must carry the NEW data
            ctx.check("the re-read picked up the edit", after.tangents()[0] == -1f, -1f, after.tangents()[0]);
        })

        .step("clean up the fixtures", ctx -> {
            int deleted = 0;
            for (var file : fixtures) {
                if (!file.exists() || file.delete()) deleted++;
            }
            ctx.check("every fixture was removed", deleted == fixtures.size(),
                    fixtures.size(), deleted);
        });
    }

    /** Load through a freshly-built source, reporting rather than throwing when it comes back empty. */
    private static PhotonMesh load(TestContext ctx, Identifier location, String what) {
        var source = new GltfModelSource(location);
        source.invalidate(); // a previous run may have left this key cached
        var mesh = source.getMesh();
        ctx.check("%s loaded".formatted(what), !mesh.isEmpty(), "non-empty", "empty mesh");
        return mesh.isEmpty() ? null : mesh;
    }

    /** Write a fixture into the injected assets pack and return the location that resolves to it. */
    private Identifier write(String name, byte[] bytes) {
        var file = new File(LDLib2.getAssetsDir(), DIR + PREFIX + name);
        fixtures.add(file);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("could not write the glTF fixture " + name, e);
        }
        return Photon.id("models/" + PREFIX + name);
    }

    /**
     * One +Z triangle with u along +X and v along +Y. With {@code tangent} it also carries a TANGENT of
     * -X / handedness -1 — deliberately the opposite of what deriving from these UVs produces, so a check
     * can tell the two apart.
     */
    private static String triangleGltf(boolean tangent) {
        float[] data = {
                0, 0, 0, 1, 0, 0, 0, 1, 0,   // positions
                0, 0, 1, 0, 0, 1, 0, 0, 1,   // normals
                0, 0, 1, 0, 0, 1,            // uvs
                -1, 0, 0, -1, -1, 0, 0, -1, -1, 0, 0, -1, // tangents
        };
        int floats = tangent ? data.length : data.length - 12;
        var bytes = ByteBuffer.allocate(floats * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floats; i++) bytes.putFloat(data[i]);
        return """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes":
                    {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2%s}}]}],
                  "accessors": [
                    {"bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 36, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 0, "byteOffset": 72, "componentType": 5126, "count": 3, "type": "VEC2"}%s
                  ],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": %d}],
                  "buffers": [{"byteLength": %d, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(
                tangent ? ", \"TANGENT\": 3" : "",
                tangent ? ", {\"bufferView\": 0, \"byteOffset\": 96, \"componentType\": 5126,"
                        + " \"count\": 3, \"type\": \"VEC4\"}" : "",
                floats * 4, floats * 4, Base64.getEncoder().encodeToString(bytes.array()));
    }

    /** Wrap JSON in a GLB container: 12-byte header, then a 4-byte-aligned JSON chunk. */
    private static byte[] glb(String json) {
        var text = json.getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        var out = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + padded);
        out.putInt(padded).putInt(0x4E4F534A).put(text);
        while (out.position() < out.capacity()) out.put((byte) ' ');
        return out.array();
    }
}
