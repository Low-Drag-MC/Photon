package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

/**
 * A glTF 2.0 reader for the geometry Photon actually renders — the same "small parser, no pipeline"
 * choice {@link ObjMeshParser} makes, for the same reason: the loaders that ship with Minecraft and
 * NeoForge are wired to the resource-pack/atlas bakery and cannot hand back raw runtime geometry.
 *
 * <p>Reads <b>.glb</b> (the single-file binary container) and <b>.gltf</b> whose buffers are inlined as
 * {@code data:} URIs. A {@code .gltf} pointing at a sibling {@code .bin} is rejected with a message
 * saying so, rather than silently producing an empty model.</p>
 *
 * <p>What it takes from the file: the default scene's node hierarchy (each node's {@code matrix} or
 * TRS, composed down the tree and baked into the vertices), every {@code TRIANGLES} primitive of every
 * mesh, and the {@code POSITION} / {@code NORMAL} / {@code TEXCOORD_0} / {@code TANGENT} attributes.
 * Materials, textures, cameras, skins, animations and morph targets are ignored — Photon has its own
 * material system, and the rest is not geometry.</p>
 *
 * <p><b>Tangents.</b> glTF is the first format Photon reads that can carry them, and its convention is
 * already ours: {@code TANGENT} is a {@code vec4}, {@code xyz} the unit tangent and {@code w} the
 * bitangent handedness. A primitive that has them keeps them (baked through the node transform); one
 * that does not falls back to {@link MeshTangents}, which is what the glTF spec asks implementations
 * to do anyway. Mixing the two inside one file makes the whole mesh generate — see
 * {@link PhotonMesh.Builder}.</p>
 *
 * <p>Positions are used <b>raw</b> (no centering or unit conversion), matching the OBJ reader, so the
 * model's size and origin are what the author exported. glTF and Minecraft share a Y-up right-handed
 * convention, so no axis swizzle is applied. glTF's UV origin is already top-left like Minecraft's, so
 * unlike OBJ no V flip is needed — {@code flipV} exists only to rescue an odd export.</p>
 */
public final class GltfMeshParser {

    private static final int GLB_MAGIC = 0x46546C67;      // "glTF", little-endian
    private static final int CHUNK_JSON = 0x4E4F534A;     // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;      // "BIN\0"
    /** Malformed files can describe a node cycle; glTF forbids it, so bail rather than recurse forever. */
    private static final int MAX_NODE_DEPTH = 64;

    private GltfMeshParser() {
    }

    public static PhotonMesh parse(InputStream in, boolean flipV) throws IOException {
        return parse(in.readAllBytes(), flipV);
    }

    /** Package-visible for tests. */
    static PhotonMesh parse(byte[] bytes, boolean flipV) throws IOException {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        JsonObject root;
        byte[] glbBin = null;
        if (bytes.length >= 12 && buffer.getInt(0) == GLB_MAGIC) {
            var chunks = readGlbChunks(buffer, bytes.length);
            root = JsonParser.parseString(new String(chunks.json(), StandardCharsets.UTF_8)).getAsJsonObject();
            glbBin = chunks.bin();
        } else {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        return new Reader(root, glbBin, flipV).read();
    }

    private record GlbChunks(byte[] json, byte[] bin) {
    }

    /**
     * GLB is a 12-byte header (magic / version / total length) followed by length-prefixed chunks. Only
     * the first JSON chunk and the first BIN chunk are meaningful; anything else is an extension chunk
     * and is skipped, which is exactly what the spec tells readers to do.
     */
    private static GlbChunks readGlbChunks(ByteBuffer buffer, int length) throws IOException {
        int version = buffer.getInt(4);
        if (version != 2) {
            throw new IOException("unsupported glb version " + version + " (only glTF 2.0)");
        }
        byte[] json = null;
        byte[] bin = null;
        int offset = 12;
        while (offset + 8 <= length) {
            int chunkLength = buffer.getInt(offset);
            int chunkType = buffer.getInt(offset + 4);
            int dataStart = offset + 8;
            // `chunkLength > length - dataStart`, not `dataStart + chunkLength > length`: the latter
            // wraps negative for a huge chunkLength, letting a corrupt file through to a
            // `new byte[chunkLength]` OutOfMemoryError — an Error, which the loader's catch(Exception)
            // would not contain.
            if (chunkLength < 0 || chunkLength > length - dataStart) {
                throw new IOException("truncated glb chunk at offset " + offset);
            }
            if (chunkType == CHUNK_JSON && json == null) {
                json = new byte[chunkLength];
                buffer.get(dataStart, json);
            } else if (chunkType == CHUNK_BIN && bin == null) {
                bin = new byte[chunkLength];
                buffer.get(dataStart, bin);
            }
            offset = dataStart + chunkLength;
        }
        if (json == null) {
            throw new IOException("glb has no JSON chunk");
        }
        return new GlbChunks(json, bin);
    }

    /** One parse. Holds the decoded buffers so accessors can be read lazily as primitives need them. */
    private static final class Reader {
        private final JsonObject root;
        private final byte[] glbBin;
        private final boolean flipV;
        private final PhotonMesh.Builder builder = new PhotonMesh.Builder();
        private final List<byte[]> buffers = new ArrayList<>();

        // reused per triangle corner so a large mesh doesn't allocate per vertex
        private final float[][] corner = new float[3][PhotonMesh.FLOATS_PER_VERTEX];
        private final float[][] cornerTangent = new float[3][PhotonMesh.FLOATS_PER_TANGENT];
        private final Vector3f scratch = new Vector3f();

        Reader(JsonObject root, byte[] glbBin, boolean flipV) {
            this.root = root;
            this.glbBin = glbBin;
            this.flipV = flipV;
        }

        PhotonMesh read() throws IOException {
            decodeBuffers();
            var nodes = array("nodes");
            var scenes = array("scenes");
            var roots = new ArrayList<Integer>();
            int sceneIndex = root.has("scene") ? root.get("scene").getAsInt() : 0;
            if (sceneIndex >= 0 && sceneIndex < scenes.size()) {
                var sceneNodes = scenes.get(sceneIndex).getAsJsonObject().getAsJsonArray("nodes");
                if (sceneNodes != null) sceneNodes.forEach(n -> roots.add(n.getAsInt()));
            }
            if (roots.isEmpty()) {
                // No scene graph, an empty scene, or a `scene` index pointing past the array — all of
                // which used to yield an invisible model with nothing in the log. Fall back to every node
                // that nobody lists as a child, so the hierarchy is still walked exactly once.
                var children = new HashSet<Integer>();
                for (var node : nodes) {
                    var kids = node.getAsJsonObject().getAsJsonArray("children");
                    if (kids != null) kids.forEach(k -> children.add(k.getAsInt()));
                }
                for (int i = 0; i < nodes.size(); i++) {
                    if (!children.contains(i)) roots.add(i);
                }
            }
            for (int nodeIndex : roots) {
                visitNode(nodes, nodeIndex, new Matrix4f(), 0);
            }
            return builder.build();
        }

        /** Walk the hierarchy, composing transforms so each primitive is emitted in scene space. */
        private void visitNode(JsonArray nodes, int index, Matrix4f parent, int depth) {
            if (depth > MAX_NODE_DEPTH || index < 0 || index >= nodes.size()) return;
            var node = nodes.get(index).getAsJsonObject();
            var world = new Matrix4f(parent).mul(localTransform(node));
            if (node.has("mesh")) {
                readMesh(node.get("mesh").getAsInt(), world);
            }
            var children = node.getAsJsonArray("children");
            if (children != null) {
                for (var child : children) {
                    visitNode(nodes, child.getAsInt(), world, depth + 1);
                }
            }
        }

        /** A node is either a full column-major {@code matrix} or a translation/rotation/scale triple. */
        private static Matrix4f localTransform(JsonObject node) {
            var m = node.has("matrix") ? node.getAsJsonArray("matrix") : null;
            if (m != null && m.size() >= 16) {
                var values = new float[16];
                for (int i = 0; i < 16; i++) values[i] = m.get(i).getAsFloat();
                return new Matrix4f().set(values);
            }
            // A short matrix array would zero-pad into a singular transform that collapses the node's
            // geometry to the origin. Identity leaves the model diagnosable instead.
            if (m != null) {
                return new Matrix4f();
            }
            var matrix = new Matrix4f();
            var t = node.getAsJsonArray("translation");
            if (t != null) {
                matrix.translate(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
            }
            var r = node.getAsJsonArray("rotation"); // glTF stores the quaternion as (x, y, z, w)
            if (r != null) {
                matrix.rotate(new Quaternionf(r.get(0).getAsFloat(), r.get(1).getAsFloat(),
                        r.get(2).getAsFloat(), r.get(3).getAsFloat()));
            }
            var s = node.getAsJsonArray("scale");
            if (s != null) {
                matrix.scale(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
            }
            return matrix;
        }

        private void readMesh(int meshIndex, Matrix4f world) {
            var meshes = array("meshes");
            if (meshIndex < 0 || meshIndex >= meshes.size()) return;
            float determinant = world.determinant3x3();
            // Normals need the inverse transpose (non-uniform scale skews them); tangents are plain
            // directions and use the matrix itself, per the glTF spec. A singular transform — a zero
            // scale axis, which is how exporters hide a node — makes the inverse transpose Inf/NaN, so
            // pass the file's own normals through untransformed rather than let NaN reach the VBO.
            var normalMatrix = Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-12f
                    ? world.normal(new Matrix3f())
                    : new Matrix3f();
            // A negative determinant mirrors the node: it flips which way the bitangent points AND
            // reverses the triangle winding (glTF 3.7.2.1). Skipping the winding leaves a mirrored
            // instance back-facing, i.e. culled away entirely — looks like a failed load, not a bug.
            boolean mirrored = determinant < 0f;
            var primitives = meshes.get(meshIndex).getAsJsonObject().getAsJsonArray("primitives");
            if (primitives == null) return;
            for (var element : primitives) {
                readPrimitive(element.getAsJsonObject(), world, normalMatrix, mirrored);
            }
        }

        private void readPrimitive(JsonObject primitive, Matrix4f world, Matrix3f normalMatrix,
                                   boolean mirrored) {
            int mode = primitive.has("mode") ? primitive.get("mode").getAsInt() : 4;
            if (mode != 4) {
                // 4 = TRIANGLES. Strips/fans/points/lines are legal glTF but essentially never exported
                // for meshes; skipping is better than guessing a winding.
                return;
            }
            var attributes = primitive.getAsJsonObject("attributes");
            if (attributes == null || !attributes.has("POSITION")) return;

            float[] positions = readAccessor(attributes.get("POSITION").getAsInt(), 3);
            if (positions == null) return;
            int vertexCount = positions.length / 3;
            float[] normals = attributeOf(attributes, "NORMAL", 3, vertexCount);
            float[] uvs = attributeOf(attributes, "TEXCOORD_0", 2, vertexCount);
            float[] tangents = attributeOf(attributes, "TANGENT", 4, vertexCount);

            int[] indices = primitive.has("indices")
                    ? readIndices(primitive.get("indices").getAsInt())
                    : sequence(vertexCount);
            if (indices == null) return;

            float handedness = mirrored ? -1f : 1f;
            for (int i = 0; i + 2 < indices.length; i += 3) {
                boolean ok = true;
                for (int k = 0; k < 3; k++) {
                    // corners 1 and 2 swap on a mirrored node, restoring the front face
                    int src = (mirrored && k > 0) ? 3 - k : k;
                    ok &= fillCorner(k, indices[i + src], vertexCount, positions, normals, uvs, tangents,
                            world, normalMatrix, handedness);
                }
                if (!ok) continue;
                if (normals == null) {
                    faceNormal();
                }
                if (tangents == null) {
                    builder.triangle(corner[0], corner[1], corner[2]);
                } else {
                    builder.triangle(corner[0], corner[1], corner[2],
                            cornerTangent[0], cornerTangent[1], cornerTangent[2]);
                }
            }
        }

        /** Decode one indexed vertex into {@link #corner}/{@link #cornerTangent}, in scene space. */
        private boolean fillCorner(int slot, int vertex, int vertexCount, float[] positions,
                                   float[] normals, float[] uvs, float[] tangents,
                                   Matrix4f world, Matrix3f normalMatrix, float handedness) {
            if (vertex < 0 || vertex >= vertexCount) return false;
            var out = corner[slot];
            world.transformPosition(scratch.set(positions[vertex * 3], positions[vertex * 3 + 1],
                    positions[vertex * 3 + 2]));
            out[0] = scratch.x;
            out[1] = scratch.y;
            out[2] = scratch.z;

            float u = uvs == null ? 0f : uvs[vertex * 2];
            float v = uvs == null ? 0f : uvs[vertex * 2 + 1];
            out[3] = u;
            out[4] = flipV ? 1f - v : v;

            if (normals != null) {
                scratch.set(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2])
                        .mul(normalMatrix);
                if (isDegenerate(scratch)) scratch.set(0f, 1f, 0f);
                else scratch.normalize();
                out[5] = scratch.x;
                out[6] = scratch.y;
                out[7] = scratch.z;
            }

            if (tangents != null) {
                var t = cornerTangent[slot];
                world.transformDirection(scratch.set(tangents[vertex * 4], tangents[vertex * 4 + 1],
                        tangents[vertex * 4 + 2]));
                if (isDegenerate(scratch)) scratch.set(1f, 0f, 0f);
                else scratch.normalize();
                t[0] = scratch.x;
                t[1] = scratch.y;
                t[2] = scratch.z;
                t[3] = (tangents[vertex * 4 + 3] < 0f ? -1f : 1f) * handedness;
            }
            return true;
        }

        /**
         * True when v cannot be normalized — zero length, or non-finite. The finite test is the load-bearing
         * half: NaN fails a bare {@code > epsilon} check, so a magnitude test alone would skip normalize()
         * and let the NaN through into the vertex buffer, the weld keys and the shader.
         */
        private static boolean isDegenerate(Vector3f v) {
            float len2 = v.lengthSquared();
            return !Float.isFinite(len2) || len2 <= 1.0e-20f;
        }

        /** Newell's normal of the current triangle, for a primitive that shipped without NORMAL. */
        private void faceNormal() {
            float nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < 3; i++) {
                var cur = corner[i];
                var next = corner[(i + 1) % 3];
                nx += (cur[1] - next[1]) * (cur[2] + next[2]);
                ny += (cur[2] - next[2]) * (cur[0] + next[0]);
                nz += (cur[0] - next[0]) * (cur[1] + next[1]);
            }
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0e-6f) {
                nx = 0;
                ny = 0;
                nz = 1;
                len = 1;
            }
            for (var c : corner) {
                c[5] = nx / len;
                c[6] = ny / len;
                c[7] = nz / len;
            }
        }

        private float[] attributeOf(JsonObject attributes, String name, int components, int vertexCount) {
            if (!attributes.has(name)) return null;
            float[] values = readAccessor(attributes.get(name).getAsInt(), components);
            // An attribute shorter than POSITION is a malformed file; dropping it beats reading past it.
            return values != null && values.length >= vertexCount * components ? values : null;
        }

        private static int[] sequence(int count) {
            var out = new int[count];
            for (int i = 0; i < count; i++) out[i] = i;
            return out;
        }

        // ---- buffers / accessors -------------------------------------------------------------

        private void decodeBuffers() throws IOException {
            var declared = array("buffers");
            for (int i = 0; i < declared.size(); i++) {
                var buffer = declared.get(i).getAsJsonObject();
                if (!buffer.has("uri")) {
                    // No uri = the GLB binary chunk, which by spec can only be buffer 0.
                    if (i == 0 && glbBin != null) {
                        buffers.add(glbBin);
                        continue;
                    }
                    throw new IOException("buffer " + i + " has no uri and there is no glb binary chunk");
                }
                String uri = buffer.get("uri").getAsString();
                if (!uri.startsWith("data:")) {
                    throw new IOException("buffer " + i + " points at the external file '" + uri
                            + "'. Photon reads self-contained models — re-export as .glb, or as .gltf with"
                            + " embedded buffers.");
                }
                int comma = uri.indexOf(',');
                if (comma < 0 || uri.lastIndexOf("base64", comma) < 0) {
                    throw new IOException("buffer " + i + " uses a non-base64 data uri");
                }
                buffers.add(Base64.getDecoder().decode(uri.substring(comma + 1)));
            }
        }

        /** An accessor's values as floats, {@code components} per element, or null if unreadable. */
        private float[] readAccessor(int index, int components) {
            var accessors = array("accessors");
            if (index < 0 || index >= accessors.size()) return null;
            var accessor = accessors.get(index).getAsJsonObject();
            if (accessor.has("sparse")) return null; // rare, and silently wrong if ignored
            int count = accessor.get("count").getAsInt();
            int componentType = accessor.get("componentType").getAsInt();
            int declared = componentsOf(accessor.get("type").getAsString());
            if (declared != components || count <= 0) return null;
            boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
            int componentSize = componentSize(componentType);
            if (componentSize == 0) return null;

            var view = bufferView(accessor);
            if (view == null) {
                return new float[count * components]; // no bufferView = all zeroes, per spec
            }
            int stride = view.stride() > 0 ? view.stride() : components * componentSize;
            int base = view.offset() + (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
            var data = view.data();
            var out = new float[count * components];
            for (int i = 0; i < count; i++) {
                int element = base + i * stride;
                for (int c = 0; c < components; c++) {
                    int at = element + c * componentSize;
                    if (at + componentSize > data.limit()) return null;
                    out[i * components + c] = readComponent(data, at, componentType, normalized);
                }
            }
            return out;
        }

        private int[] readIndices(int index) {
            var accessors = array("accessors");
            if (index < 0 || index >= accessors.size()) return null;
            var accessor = accessors.get(index).getAsJsonObject();
            if (accessor.has("sparse")) return null;
            int count = accessor.get("count").getAsInt();
            int componentType = accessor.get("componentType").getAsInt();
            int componentSize = componentSize(componentType);
            if (componentSize == 0 || count <= 0) return null;
            var view = bufferView(accessor);
            if (view == null) return null;
            int stride = view.stride() > 0 ? view.stride() : componentSize;
            int base = view.offset() + (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
            var data = view.data();
            var out = new int[count];
            for (int i = 0; i < count; i++) {
                int at = base + i * stride;
                if (at + componentSize > data.limit()) return null;
                out[i] = switch (componentType) {
                    case 5121 -> data.get(at) & 0xFF;
                    case 5123 -> data.getShort(at) & 0xFFFF;
                    case 5125 -> data.getInt(at);
                    default -> -1;
                };
            }
            return out;
        }

        private record View(ByteBuffer data, int offset, int stride) {
        }

        private View bufferView(JsonObject accessor) {
            if (!accessor.has("bufferView")) return null;
            var views = array("bufferViews");
            int index = accessor.get("bufferView").getAsInt();
            if (index < 0 || index >= views.size()) return null;
            var view = views.get(index).getAsJsonObject();
            int bufferIndex = view.get("buffer").getAsInt();
            if (bufferIndex < 0 || bufferIndex >= buffers.size()) return null;
            var data = ByteBuffer.wrap(buffers.get(bufferIndex)).order(ByteOrder.LITTLE_ENDIAN);
            int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
            int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : 0;
            return new View(data, offset, stride);
        }

        private static float readComponent(ByteBuffer data, int at, int componentType, boolean normalized) {
            return switch (componentType) {
                case 5120 -> normalized ? Math.max(data.get(at) / 127f, -1f) : data.get(at);
                case 5121 -> normalized ? (data.get(at) & 0xFF) / 255f : (data.get(at) & 0xFF);
                case 5122 -> normalized ? Math.max(data.getShort(at) / 32767f, -1f) : data.getShort(at);
                case 5123 -> normalized ? (data.getShort(at) & 0xFFFF) / 65535f : (data.getShort(at) & 0xFFFF);
                case 5125 -> data.getInt(at);
                case 5126 -> data.getFloat(at);
                default -> 0f;
            };
        }

        private static int componentSize(int componentType) {
            return switch (componentType) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                case 5125, 5126 -> 4;
                default -> 0;
            };
        }

        private static int componentsOf(String type) {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                default -> -1;
            };
        }

        private JsonArray array(String name) {
            JsonElement element = root.get(name);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        }
    }
}
