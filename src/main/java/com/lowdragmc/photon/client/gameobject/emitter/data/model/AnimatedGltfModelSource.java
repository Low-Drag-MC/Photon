package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.ClipRetarget;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.VertexAnimationBake;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A glTF model playing one of its own animations: an ordinary authored model source that is also an
 * {@link IDynamicMesh}.
 *
 * <p>The pose follows the <b>timeline</b> in the editor (so scrubbing scrubs the animation) and the
 * level's clock in the world. Every emitter on the same file and clip reads the same instant, so they
 * share one deformation ({@link AnimatedPose}) and one render pass — which also means every particle is
 * in lockstep. {@link #perParticlePhase} trades that for a baked table the shader indexes per instance.</p>
 */
@OnlyIn(Dist.CLIENT)
@LDLRegisterClient(name = "animated_gltf_model", registry = "photon:model_source")
public class AnimatedGltfModelSource implements IModelSource, IDynamicMesh {

    @Getter
    @Configurable(name = "GltfModelSource.modelLocation")
    private ResourceLocation modelLocation = Photon.id("models/missing.glb");
    /** glTF's UV origin is already top-left like Minecraft's, so unlike OBJ this defaults to off. */
    @Getter
    @Configurable(name = "GltfModelSource.flipV", tips = "photon.model_source.gltf_model.flipV.tips")
    private boolean flipV = false;
    /** By name, empty meaning the first; an index would silently follow a reordered export.
     *  Shown as a selector built from the loaded file's clips, not as a @Configurable text field. */
    @Getter
    @Persisted
    private String animation = "";
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.speed",
            tips = "photon.model_source.animated_gltf_model.speed.tips")
    private float speed = 1f;
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.loop",
            tips = "photon.model_source.animated_gltf_model.loop.tips")
    private boolean loop = true;

    /**
     * Extra glb files whose clips are retargeted onto this model's skeleton by joint name — the
     * one-character-file-plus-many-animation-files layout every exporter offers.
     *
     * <p>Serialized by hand: a {@code List<ResourceLocation>} is not something the persisted parser
     * round-trips.</p>
     */
    @Getter
    @ConfigList(configuratorMethod = "createAnimationFileConfigurator",
            addDefaultMethod = "addDefaultAnimationFile")
    @Configurable(name = "AnimatedGltfModelSource.animationFiles",
            tips = "photon.model_source.animated_gltf_model.animationFiles.tips")
    private List<ResourceLocation> animationFiles = new ArrayList<>();

    /**
     * Give every particle its own frame of the animation instead of posing them all alike.
     *
     * <p>The clip is baked into a table once and the vertex shader reads it, so a swarm costs one
     * texture and no per-frame work — deforming a thousand copies is not something a CPU can do.
     * ⚠️ Tangents are not baked, so a normal-mapped model drawn this way keeps the rest pose's frame.</p>
     */
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.perParticlePhase",
            tips = "photon.model_source.animated_gltf_model.perParticlePhase.tips")
    private boolean perParticlePhase = false;
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.frames",
            tips = "photon.model_source.animated_gltf_model.frames.tips")
    @ConfigNumber(range = {2, 240})
    private int frames = 30;
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.phaseSource",
            tips = "photon.model_source.animated_gltf_model.phaseSource.tips")
    private PhaseSource phaseSource = PhaseSource.Random;

    /** Where a particle's place in the clip comes from. */
    public enum PhaseSource {
        /** A stable per-particle offset from the shared clock — a flock, each at its own point. */
        Random,
        /** The particle's own life, so the clip plays exactly once from spawn to death. */
        Lifetime
    }

    private final DynamicMeshCache meshCache = new DynamicMeshCache();
    /** Baked on demand and thrown away with the model; see {@link #vertexAnimation()}. */
    @Nullable
    private VertexAnimation vertexAnimation;
    /** What {@link #vertexAnimation} was baked from, as fields rather than a key object — this is
     *  compared once a frame on the render path. */
    @Nullable
    private SkinnedModel bakedModel;
    private String bakedAnimation = "";
    private int bakedFrames;

    /** Test seam: whether a deformation ran is invisible in the picture, so asserting reuse means
     *  holding the clock still and watching the revision not move. Null restores the real clock. */
    @Nullable
    private static volatile Float pinnedClock;

    public static void pinClock(@Nullable Float seconds) {
        pinnedClock = seconds;
    }

    public AnimatedGltfModelSource() {
    }

    public AnimatedGltfModelSource(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "modelLocation")
    public void setModelLocation(ResourceLocation modelLocation) {
        invalidate(); // drop the old key's entry before it changes
        this.modelLocation = modelLocation;
    }

    @ConfigSetter(field = "flipV")
    public void setFlipV(boolean flipV) {
        invalidate();
        this.flipV = flipV;
    }

    @ConfigSetter(field = "animation")
    public void setAnimation(String animation) {
        this.animation = animation;
        meshCache.invalidate();
    }

    @ConfigSetter(field = "speed")
    public void setSpeed(float speed) {
        this.speed = speed;
    }

    @ConfigSetter(field = "loop")
    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    @ConfigSetter(field = "perParticlePhase")
    public void setPerParticlePhase(boolean perParticlePhase) {
        this.perParticlePhase = perParticlePhase;
        dropBake();
    }

    @ConfigSetter(field = "frames")
    public void setFrames(int frames) {
        this.frames = frames;
        dropBake();
    }

    @ConfigSetter(field = "phaseSource")
    public void setPhaseSource(PhaseSource phaseSource) {
        this.phaseSource = phaseSource;
    }

    private void dropBake() {
        if (vertexAnimation != null) {
            vertexAnimation.dispose();
            vertexAnimation = null;
        }
        bakedModel = null;
    }

    /**
     * The baked pose table, or null when this source poses on the CPU instead.
     *
     * <p>Baking walks the clip once per frame, so it is done when the model or the frame count changes
     * and not again.</p>
     */
    @Override
    @Nullable
    public VertexAnimation vertexAnimation() {
        if (!perParticlePhase) return null;
        var model = model();
        if (!model.isAnimated()) return null;
        if (vertexAnimation != null && bakedModel == model
                && bakedAnimation.equals(animation) && bakedFrames == frames) {
            refreshPhase(model); // the clock moved even though the table did not
            return vertexAnimation;
        }
        dropBake();
        var clip = animation.isEmpty() ? model.clipAt(0) : model.clip(animation);
        var table = VertexAnimationBake.bake(model, clip, frames);
        if (table == null) {
            Photon.LOGGER.warn("could not bake {} frames of {} — too large, or nothing to pose",
                    frames, modelLocation);
            return null;
        }
        vertexAnimation = new VertexAnimation(table, model.mesh().vertexCount(), frames);
        bakedModel = model;
        bakedAnimation = animation;
        bakedFrames = frames;
        refreshPhase(model);
        return vertexAnimation;
    }

    private void refreshPhase(SkinnedModel model) {
        if (vertexAnimation == null) return;
        if (phaseSource == PhaseSource.Lifetime) {
            vertexAnimation.setPhase(0f, 0f, 1f);
            return;
        }
        var clip = animation.isEmpty() ? model.clipAt(0) : model.clip(animation);
        float duration = clip == null ? 0f : clip.duration();
        vertexAnimation.setPhase(duration <= 0f ? 0f : clipTime(clip) / duration, 1f, 0f);
    }

    /** The base file's own key, shared with {@link GltfModelSource}: one parse serves both. */
    private PhotonMeshCache.GltfKey key() {
        return new PhotonMeshCache.GltfKey(modelLocation, flipV);
    }

    /** The model plus whatever the animation files added to it. */
    private record CombinedKey(ResourceLocation model, boolean flipV, List<ResourceLocation> animations) {
    }

    private SkinnedModel model() {
        if (animationFiles.isEmpty()) {
            return PhotonMeshCache.INSTANCE.getModel(key(), k -> load(modelLocation));
        }
        return PhotonMeshCache.INSTANCE.getModel(
                new CombinedKey(modelLocation, flipV, List.copyOf(animationFiles)), k -> combine());
    }

    /**
     * The base model with every animation file's clips retargeted onto its skeleton.
     * {@code null} = cannot load right now; see {@link #load}.
     */
    @Nullable
    private SkinnedModel combine() {
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        var base = PhotonMeshCache.INSTANCE.getModel(key(), k -> load(modelLocation));
        var skeleton = base.skeleton();
        if (skeleton == null) {
            // nothing to retarget onto; the files are silently unusable, so say why once
            if (!animationFiles.isEmpty()) {
                Photon.LOGGER.warn("{} has no skeleton, so its {} animation file(s) cannot be used",
                        modelLocation, animationFiles.size());
            }
            return base;
        }
        var clips = new ArrayList<>(base.clips());
        for (var file : animationFiles) {
            if (file.equals(modelLocation)) continue; // its clips are already in base.clips()
            var source = PhotonMeshCache.INSTANCE.getModel(
                    new PhotonMeshCache.GltfKey(file, flipV), k -> load(file));
            if (source.skeleton() == null || source.clips().isEmpty()) {
                Photon.LOGGER.warn("animation file {} has no clips to take", file);
                continue;
            }
            var result = ClipRetarget.onto(skeleton, source.skeleton(), source.clips(), baseName(file));
            if (result.clips().isEmpty()) {
                Photon.LOGGER.warn("none of {}'s channels match {}'s joints — a different rig?",
                        file, modelLocation);
            } else if (result.droppedChannels() > 0) {
                Photon.LOGGER.warn("{} of {}'s channels name joints {} does not have",
                        result.droppedChannels(), file, modelLocation);
            }
            clips.addAll(result.clips());
        }
        return new SkinnedModel(base.mesh(), base.skin(), skeleton, List.copyOf(clips));
    }

    /** The file name without its directory or extension, which is what an animation file is called. */
    private static String baseName(ResourceLocation file) {
        var path = file.getPath();
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }

    @Override
    public PhotonMesh getMesh() {
        // with a baked table the rest pose IS the mesh; the shader replaces the position per vertex
        return perParticlePhase ? topology() : meshCache.resolve(this);
    }

    /**
     * Whether the file has a skeleton AND something to play on it — what the importer decides between
     * this source and the static one on.
     */
    public boolean hasAnimation() {
        var model = model();
        return model.isAnimated() && !model.clips().isEmpty();
    }

    /** Every clip this source can play: the model's own plus whatever the animation files added. */
    public List<String> getClipNames() {
        return model().clipNames();
    }

    /**
     * Only a model that can be posed is dynamic; an unskinned or failed one is an ordinary mesh.
     *
     * <p>⚠️ And a per-particle one is not dynamic either: nothing is deformed on this side at all, the
     * mesh stays the rest pose and the shader does the posing.</p>
     */
    @Override
    @Nullable
    public IDynamicMesh asDynamic() {
        return !perParticlePhase && model().isAnimated() ? this : null;
    }

    // ---- IDynamicMesh ----------------------------------------------------------------------------

    @Override
    public PhotonMesh topology() {
        return model().mesh();
    }

    @Override
    public long revision() {
        var pose = pose();
        return pose == null ? 0L : pose.revision();
    }

    @Override
    @Nullable
    public float[] geometry() {
        var pose = pose();
        return pose == null ? null : pose.geometry();
    }

    /** Asked for only when a pass actually draws with tangents; see {@link PhotonMesh#tangents()}. */
    @Override
    @Nullable
    public float[] tangents() {
        var model = model();
        var pose = pose();
        if (pose == null) return null;
        return pose.tangents(model, animation.isEmpty() ? model.clipAt(0) : model.clip(animation));
    }

    @Nullable
    private AnimatedPose pose() {
        var model = model();
        if (!model.isAnimated()) return null;
        var clip = animation.isEmpty() ? model.clipAt(0) : model.clip(animation);
        return AnimatedPose.of(model, clip, clipTime(clip), speed, loop);
    }

    /** Where in the clip we are, in seconds. Clamping a non-looping clip stops the pose being redone. */
    private float clipTime(@Nullable AnimationClip clip) {
        float editor = pinnedClock != null ? pinnedClock : PhotonParticleManager.editorAnimationSeconds();
        float seconds;
        if (editor >= 0f) {
            seconds = editor;
        } else {
            var level = Minecraft.getInstance().level;
            seconds = level == null ? 0f
                    : (level.getGameTime() + Minecraft.getInstance().getTimer()
                    .getGameTimeDeltaPartialTick(false)) / 20f;
        }
        seconds *= speed;
        // an infinite speed makes the modulo NaN, which never equals the last pose's time and so
        // re-deforms the model into nothing, every frame, forever
        if (clip == null || !Float.isFinite(seconds)) return 0f;
        float duration = clip.duration();
        if (duration <= 0f) return 0f;
        if (!loop) {
            return Math.max(0f, Math.min(seconds, duration));
        }
        // floorMod on floats: a negative speed has to wrap forward, not mirror
        float wrapped = seconds % duration;
        return wrapped < 0f ? wrapped + duration : wrapped;
    }

    // ---- IModelSource ----------------------------------------------------------------------------

    @Override
    public void invalidate() {
        dropBake();
        PhotonMeshCache.INSTANCE.invalidate(key());
        PhotonMeshCache.INSTANCE.invalidate(new CombinedKey(modelLocation, flipV, List.copyOf(animationFiles)));
        for (var file : animationFiles) {
            PhotonMeshCache.INSTANCE.invalidate(new PhotonMeshCache.GltfKey(file, flipV));
        }
        meshCache.invalidate();
    }

    @Override
    public IModelSource copy() {
        var copy = new AnimatedGltfModelSource(modelLocation);
        copy.flipV = flipV;
        copy.animation = animation;
        copy.speed = speed;
        copy.loop = loop;
        copy.animationFiles = new ArrayList<>(animationFiles);
        copy.perParticlePhase = perParticlePhase;
        copy.frames = frames;
        copy.phaseSource = phaseSource;
        return copy;
    }

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        if (animationFiles.isEmpty()) return IModelSource.super.serializeAdditionalNBT(provider);
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var file : animationFiles) {
            list.add(StringTag.valueOf(file.toString()));
        }
        tag.put("animationFiles", list);
        return tag;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        animationFiles.clear();
        if (!(tag instanceof CompoundTag compound)) return;
        for (var element : compound.getList("animationFiles", Tag.TAG_STRING)) {
            if (LDLib2.isValidResourceLocation(element.getAsString())) {
                animationFiles.add(ResourceLocation.parse(element.getAsString()));
            }
        }
    }

    /** A new row starts on the model's own file: inert (see {@link #combine}) rather than a path that
     *  does not resolve and logs a failed load the moment the row appears. */
    private ResourceLocation addDefaultAnimationFile() {
        return modelLocation;
    }

    private Configurator createAnimationFileConfigurator(Supplier<ResourceLocation> getter,
                                                         Consumer<ResourceLocation> setter) {
        var configurator = new Configurator();
        configurator.addInlineChild(new Button().setText(getter.get().getPath()).setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            showGlbDialog(mui.ui.rootElement, file -> {
                setter.accept(file);
                invalidate();
                configurator.notifyChanges();
            });
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        return configurator;
    }

    /** {@code null} = "can't load right now, don't cache" (retry next call); see {@link PhotonMeshCache#get}. */
    @Nullable
    private SkinnedModel load(ResourceLocation location) {
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        try (var in = Minecraft.getInstance().getResourceManager().open(location)) {
            var model = GltfMeshParser.parseModel(in, flipV);
            var file = new File(LDLib2.getAssetsDir(), location.getNamespace() + "/" + location.getPath());
            if (file.isFile()) {
                PhotonMeshCache.INSTANCE.trackFile(new PhotonMeshCache.GltfKey(location, flipV), file);
            }
            return model;
        } catch (FileNotFoundException e) {
            // a source whose file has not been picked yet; a stack trace says nothing extra
            Photon.LOGGER.warn("glTF {} does not exist", location);
            return SkinnedModel.EMPTY;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load glTF {}", location, e);
            return SkinnedModel.EMPTY;
        }
    }

    /** The glb/gltf picker, shared by the model field and every animation-file row. */
    private static void showGlbDialog(UIElement root, Consumer<ResourceLocation> onPicked) {
        Dialog.showFileDialog("photon.gui.editor.tips.select_gltf", LDLib2.getAssetsDir(), true,
                node -> {
                    if (!node.getKey().isFile()) return true; // allow directories
                    var name = node.getKey().getName().toLowerCase();
                    return name.endsWith(".glb") || name.endsWith(".gltf");
                }, r -> {
                    if (r != null && r.isFile()) {
                        var location = IModelSource.getAssetLocationFromFile(r);
                        if (location != null) onPicked.accept(location);
                    }
                }).show(root);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("photon.gui.editor.tips.select_gltf").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            showGlbDialog(mui.ui.rootElement, location -> {
                if (location.equals(modelLocation)) return;
                setModelLocation(location);
                buttonConfigurator.notifyChanges();
            });
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        // the file's own clip names, so the animation field is picked rather than typed. Empty until
        // the model loads, and "" stays in the list as "the first one".
        var names = new ArrayList<String>();
        names.add("");
        names.addAll(model().clipNames());
        var clips = new SelectorConfigurator<>("AnimatedGltfModelSource.animation",
                this::getAnimation, this::setAnimation, "", true, names,
                name -> name.isEmpty() ? "photon.model_source.animated_gltf_model.first_animation" : name);

        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    invalidate();
                    buttonConfigurator.notifyChanges();
                }).setText("photon.reload_mesh").layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        father.addConfigurators(buttonConfigurator, clips, reloadButton);
    }

    /** ⚠️ Covers the clock's inputs too: two sources differing only in speed are at different poses. */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        var that = (AnimatedGltfModelSource) o;
        return flipV == that.flipV && loop == that.loop
                && Float.compare(speed, that.speed) == 0
                && animation.equals(that.animation)
                && Objects.equals(modelLocation, that.modelLocation)
                && animationFiles.equals(that.animationFiles)
                && perParticlePhase == that.perParticlePhase && frames == that.frames
                && phaseSource == that.phaseSource;
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV, animation, speed, loop, animationFiles,
                perParticlePhase, frames, phaseSource);
    }
}
