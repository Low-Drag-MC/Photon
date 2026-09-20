package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigList;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.ClipRetarget;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
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
import org.jetbrains.annotations.NotNull;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.File;
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
 * share one deformation ({@link AnimatedPose}) and one render pass.</p>
 *
 * <p>⚠️ Which means every instance is in lockstep — one clock, not one per particle. A swarm all flap
 * together; per-particle phase needs the pose to exist at many instants at once.</p>
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

    private final DynamicMeshCache meshCache = new DynamicMeshCache();

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
        return meshCache.resolve(this);
    }

    /** Every clip this source can play: the model's own plus whatever the animation files added. */
    public List<String> getClipNames() {
        return model().clipNames();
    }

    /** Only a model that can be posed is dynamic; an unskinned or failed one is an ordinary mesh. */
    @Override
    @Nullable
    public IDynamicMesh asDynamic() {
        return model().isAnimated() ? this : null;
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
        if (clip == null) return 0f;
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

    private ResourceLocation addDefaultAnimationFile() {
        return Photon.id("models/missing.glb");
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
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load glTF {}", location, e);
            return SkinnedModel.staticModel(PhotonMesh.EMPTY);
        }
    }

    /** The glb/gltf picker, shared by the model field and every animation-file row. */
    private static void showGlbDialog(Object root, Consumer<ResourceLocation> onPicked) {
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
                }).show((com.lowdragmc.lowdraglib2.gui.ui.UIElement) root);
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
                && animationFiles.equals(that.animationFiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV, animation, speed, loop, animationFiles);
    }
}
