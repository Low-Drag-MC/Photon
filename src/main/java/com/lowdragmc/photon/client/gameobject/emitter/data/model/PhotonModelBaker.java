package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.Photon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.UnbakedModelParser;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bakes a Minecraft JSON model into geometry, without going through the model bakery.
 * <p>
 * The bakery is not usable here: {@code ModelBakery} keeps its {@code resolvedModels} private and,
 * more importantly, only ever contains models <i>reachable from a blockstate / item / standalone
 * root</i>. A Photon mesh model such as {@code ldlib2:block/tonado_body} is referenced by nothing,
 * so it would never be in there. NeoForge's standalone-model registry
 * ({@code ModelEvent.RegisterStandalone}) fires once per resource reload and therefore needs every
 * id up front — which cannot work for models picked in the editor, shipped inside an fxpack, or
 * named by an fx spawned at runtime.
 * <p>
 * So Photon owns the whole path: read {@code models/<path>.json} through the resource manager,
 * parse it with {@link UnbakedModelParser} (which honours custom loaders — Photon's own mesh models
 * are {@code neoforge:obj}), chain the {@code parent} references into a {@link ResolvedModel}, and
 * bake with a minimal {@link ModelBaker}. Nothing is registered and no resource reload is needed.
 * <p>
 * <b>Textures are not re-stitched</b>: sprites resolve against the already-built block atlas, so a
 * texture that is not in it comes back as the atlas' missing sprite (the same caveat the 1.21 path
 * documented). Harmless for Photon — the material system supplies the real texture, and
 * {@code useBlockUV=false} remaps atlas UVs back to 0..1 through {@link PhotonMesh#spriteBounds()}.
 * <p>
 * Results are not cached here; {@link PhotonMeshCache} owns caching and invalidation.
 */
final class PhotonModelBaker {
    private static final FileToIdConverter MODEL_LISTER = FileToIdConverter.json("models");
    /** A model whose parent chain is deeper than this is malformed; bail rather than walk forever. */
    private static final int MAX_PARENT_DEPTH = 64;
    /** Stands in for a model that could not be read, so one bad parent costs one slot, not the model. */
    private static final UnbakedModel EMPTY_MODEL = new UnbakedModel() {
    };

    private PhotonModelBaker() {
    }

    /**
     * The model's quads, per culling face. {@code null} means "cannot bake right now" — the atlas is
     * not ready — which {@link PhotonMeshCache} treats as retry-later rather than caching.
     */
    @Nullable
    static QuadCollection bake(Identifier modelId) {
        try {
            var baker = new Baker();
            var model = baker.getModel(modelId);
            return model.bakeTopGeometry(model.getTopTextureSlots(), baker, BlockModelRotation.IDENTITY);
        } catch (AtlasNotReadyException e) {
            return null;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to bake json model {}", modelId, e);
            return QuadCollection.EMPTY;
        }
    }

    /** Thrown out of the material baker before the atlases exist, so {@link #bake} can say "retry". */
    private static final class AtlasNotReadyException extends RuntimeException {
        AtlasNotReadyException(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    /**
     * Single-use per {@link #bake} call: it memoizes the resolved parent chain, which is only valid
     * for one bake, and it is never shared across threads.
     */
    private static final class Baker implements ModelBaker, ModelBaker.Interner, MaterialBaker {
        private final Map<Identifier, ResolvedModel> resolved = new HashMap<>();
        /** Ids currently on the resolve stack — how a cyclic {@code parent} is detected. */
        private final Set<Identifier> resolving = new HashSet<>();
        private final Map<SharedOperationKey<Object>, Object> operations = new HashMap<>();
        @Nullable
        private BlockStateModelPart missingPart;
        @Nullable
        private Material.Baked missingMaterial;

        // --- ModelBaker ---

        @Override
        public ResolvedModel getModel(Identifier location) {
            var model = resolve(location, 0);
            return model == null ? new Resolved(EMPTY_MODEL, location.toString()) : model;
        }

        /**
         * Read + parse {@code location}, then chain its {@code parent}. Memoized so a diamond of
         * shared parents is read once.
         * <p>
         * {@code null} = "do not extend the chain here": the id is already on the resolve stack (a
         * cyclic {@code parent}) or the chain is absurdly deep. That matters because the chain is
         * consumed by {@code while (current != null) current = current.parent()} walks in
         * {@link ResolvedModel} — a cycle there is an infinite loop, on the render thread, from a
         * hand-edited model file. Terminating the chain costs one bad model its parent's slots.
         */
        @Nullable
        private ResolvedModel resolve(Identifier location, int depth) {
            var cached = resolved.get(location);
            if (cached != null) {
                return cached;
            }
            if (depth > MAX_PARENT_DEPTH || !resolving.add(location)) {
                Photon.LOGGER.warn("Cyclic or too-deep json model parent chain at {}, stopping", location);
                return null;
            }
            try {
                var unbaked = read(location);
                var model = new Resolved(unbaked, location.toString());
                var parentId = unbaked.parent();
                model.parent = parentId == null ? null : resolve(parentId, depth + 1);
                resolved.put(location, model);
                return model;
            } finally {
                resolving.remove(location);
            }
        }

        private static UnbakedModel read(Identifier location) {
            var manager = Minecraft.getInstance().getResourceManager();
            var file = MODEL_LISTER.idToFile(location);
            var resource = manager.getResource(file).orElse(null);
            if (resource == null) {
                Photon.LOGGER.warn("Missing json model: {}", location);
                return EMPTY_MODEL;
            }
            try (var reader = resource.openAsReader()) {
                return UnbakedModelParser.parse(reader);
            } catch (Exception e) {
                Photon.LOGGER.warn("Failed to parse json model {}", location, e);
                return EMPTY_MODEL;
            }
        }

        @Override
        public BlockStateModelPart missingBlockModelPart() {
            if (missingPart == null) {
                missingPart = new SimpleModelWrapper(QuadCollection.EMPTY, true, missing());
            }
            return missingPart;
        }

        @Override
        public MaterialBaker materials() {
            return this;
        }

        @Override
        public Interner interner() {
            return this;
        }

        /** get/put rather than {@code computeIfAbsent}: a key's compute may ask for another key, and
         *  re-entering {@code HashMap.computeIfAbsent} throws. Worst case a key computes twice. */
        @Override
        @SuppressWarnings("unchecked")
        public <T> T compute(SharedOperationKey<T> key) {
            var existing = operations.get(key);
            if (existing != null) {
                return (T) existing;
            }
            var value = key.compute(this);
            operations.put((SharedOperationKey<Object>) key, value);
            return value;
        }

        // --- Interner: the mesh is decoded into flat float arrays immediately and the baked quads
        // are dropped, so deduplicating them would only cost a hash lookup. ---

        @Override
        public Vector3fc vector(Vector3fc vector) {
            return vector;
        }

        @Override
        public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
            return material;
        }

        // --- MaterialBaker: the live atlas, where ModelManager's own baker uses the reload-time
        // SpriteLoader.Preparations. Block atlas only — that is the one Photon's models live in. ---

        /**
         * Null-tolerant, unlike the {@link MaterialBaker} default. NeoForge's OBJ loader hands
         * {@code ObjMaterialLibrary.Material#diffuseColorMap} straight to {@code resolveSlot}, and
         * that field is null whenever the {@code .mtl} has no {@code map_Kd} — the default then walks
         * into {@code TextureSlots.getMaterial(null)} → {@code texture.charAt(0)} → NPE, which killed
         * the whole bake. Photon's mesh models are textured by the material system, so "no diffuse
         * map" is the <i>normal</i> case for them, not an error.
         */
        @Override
        public Material.Baked resolveSlot(TextureSlots slots, @Nullable String id, ModelDebugName name) {
            return id == null ? missing() : MaterialBaker.super.resolveSlot(slots, id, name);
        }

        @Override
        public Material.Baked get(Material material, ModelDebugName name) {
            return new Material.Baked(sprite(material.sprite()), material.forceTranslucent());
        }

        @Override
        public Material.Baked reportMissingReference(String reference, ModelDebugName name) {
            return missing();
        }

        /** Shared: an OBJ with several untextured materials asks for this once per material. */
        private Material.Baked missing() {
            if (missingMaterial == null) {
                missingMaterial = new Material.Baked(sprite(MissingTextureAtlasSprite.getLocation()), false);
            }
            return missingMaterial;
        }

        private static TextureAtlasSprite sprite(Identifier texture) {
            try {
                // BLOCKS_MAPPER.sheet() rather than the deprecated TextureAtlas.LOCATION_BLOCKS;
                // apply() is no use here — Material.sprite() is already a full texture id
                return Minecraft.getInstance().getAtlasManager().get(new SpriteId(Sheets.BLOCKS_MAPPER.sheet(), texture));
            } catch (Exception e) {
                // no atlases yet (first bake raced the reload) — the whole bake is worth retrying
                throw new AtlasNotReadyException(e);
            }
        }
    }

    /** A link in the parent chain; every other {@link ResolvedModel} member is a default. */
    private static final class Resolved implements ResolvedModel {
        private final UnbakedModel wrapped;
        private final String debugName;
        @Nullable
        private ResolvedModel parent;

        Resolved(UnbakedModel wrapped, String debugName) {
            this.wrapped = wrapped;
            this.debugName = debugName;
        }

        @Override
        public UnbakedModel wrapped() {
            return wrapped;
        }

        @Nullable
        @Override
        public ResolvedModel parent() {
            return parent;
        }

        @Override
        public String debugName() {
            return debugName;
        }
    }
}
