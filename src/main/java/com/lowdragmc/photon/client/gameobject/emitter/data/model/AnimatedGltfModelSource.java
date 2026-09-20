package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.AnimationClip;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.skin.SkinnedModel;
import dev.vfyjxf.taffy.style.AlignItems;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

/**
 * A glTF model that <b>plays one of its own animations</b>: the skinned counterpart of
 * {@link GltfModelSource}, and the first source whose geometry changes while it is drawn.
 *
 * <p>It is an ordinary authored, saved model source — pick a {@code .glb}, pick a clip — and it is also an
 * {@link IDynamicMesh}, which is how the changing geometry reaches the render backend and the emission
 * shape. Nothing else in Photon has to know the difference.</p>
 *
 * <h2>The clock</h2>
 *
 * <p>In the editor the pose follows the <b>timeline</b>, so scrubbing the playhead scrubs the animation and
 * a paused scene holds its pose. In the world it follows the level's clock. Either way every emitter using
 * the same file and clip reads the same instant, which is what lets them share one deformation
 * ({@link AnimatedPose}) and one render pass.</p>
 *
 * <p>⚠️ Which also means <b>every instance is in lockstep</b>. A swarm of these all flap together, because
 * there is one clock and not one per particle. A per-particle phase is a different mechanism — the pose
 * would have to exist at many instants at once, which for a thousand particles is not something a CPU
 * deformation or a shared buffer can do.</p>
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
    /**
     * Which of the file's animations to play, by name; empty means the first one. A name rather than an
     * index because an index silently plays the wrong clip after the artist reorders an export, and a
     * missing name is diagnosable where a missing index is not.
     */
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.animation",
            tips = "photon.model_source.animated_gltf_model.animation.tips")
    private String animation = "";
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.speed",
            tips = "photon.model_source.animated_gltf_model.speed.tips")
    private float speed = 1f;
    @Getter
    @Configurable(name = "AnimatedGltfModelSource.loop",
            tips = "photon.model_source.animated_gltf_model.loop.tips")
    private boolean loop = true;

    /** Hands back the identical mesh until the pose changes; see {@link DynamicMeshCache}. */
    private final DynamicMeshCache meshCache = new DynamicMeshCache();

    /**
     * Pins the clock, so a test can ask for a named instant instead of whatever the world is at.
     *
     * <p>A <b>test seam</b>, and it exists for the reason the pose-skipping does: whether the deformation
     * ran this frame or was reused is invisible in the picture, so the only way to assert it is to hold the
     * clock still and watch the revision not move. {@code null} restores the real clock.</p>
     */
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

    private PhotonMeshCache.GltfKey key() {
        return new PhotonMeshCache.GltfKey(modelLocation, flipV);
    }

    /** The parsed file, shared with {@link GltfModelSource} — one parse serves both sources. */
    private SkinnedModel model() {
        return PhotonMeshCache.INSTANCE.getModel(key(), k -> load());
    }

    @Override
    public PhotonMesh getMesh() {
        return meshCache.resolve(this);
    }

    /**
     * Only a model that can actually be posed is dynamic. A file with no skin, or one that failed to load,
     * behaves as an ordinary static mesh from here on rather than as a dynamic one that never changes.
     */
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
        // speed and loop go into the slot because they decide the instant; see AnimatedPose.Key
        return AnimatedPose.of(model, clip, clipTime(clip), speed, loop);
    }

    /**
     * Where in the clip we are, in seconds.
     *
     * <p>A non-looping clip holds its last frame, which is what the sampler does anyway when asked past
     * the end — the clamp here is so the cache key stops changing once it has, and the deformation stops
     * being redone for a pose that cannot change again.</p>
     */
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
        meshCache.invalidate();
    }

    @Override
    public IModelSource copy() {
        var copy = new AnimatedGltfModelSource(modelLocation);
        copy.flipV = flipV;
        copy.animation = animation;
        copy.speed = speed;
        copy.loop = loop;
        return copy;
    }

    /** {@code null} = "can't load right now, don't cache" (retry next call); see {@link PhotonMeshCache#get}. */
    @Nullable
    private SkinnedModel load() {
        if (Minecraft.getInstance().getOverlay() instanceof LoadingOverlay) {
            return null;
        }
        try (var in = Minecraft.getInstance().getResourceManager().open(modelLocation)) {
            var model = GltfMeshParser.parseModel(in, flipV);
            var file = new File(LDLib2.getAssetsDir(), modelLocation.getNamespace() + "/" + modelLocation.getPath());
            if (file.isFile()) {
                PhotonMeshCache.INSTANCE.trackFile(key(), file);
            }
            return model;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to load animated glTF model {}", modelLocation, e);
            return SkinnedModel.staticModel(PhotonMesh.EMPTY);
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void buildConfigurator(ConfiguratorGroup father) {
        IModelSource.super.buildConfigurator(father);
        var buttonConfigurator = new Configurator();
        buttonConfigurator.addInlineChild(new Button().setText("photon.gui.editor.tips.select_gltf").setOnClick(e -> {
            var mui = e.currentElement.getModularUI();
            if (mui == null) return;
            Dialog.showFileDialog("photon.gui.editor.tips.select_gltf", LDLib2.getAssetsDir(), true,
                    node -> {
                        if (!node.getKey().isFile()) return true; // allow directories
                        var name = node.getKey().getName().toLowerCase();
                        return name.endsWith(".glb") || name.endsWith(".gltf");
                    }, r -> {
                        if (r != null && r.isFile()) {
                            var location = IModelSource.getAssetLocationFromFile(r);
                            if (location == null || location.equals(modelLocation)) return;
                            setModelLocation(location);
                            buttonConfigurator.notifyChanges();
                        }
                    }).show(mui.ui.rootElement);
        }).layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        // The file's clip names, so the animation field does not have to be typed from memory. Read from
        // the parsed model, so it is empty until the model loads — which is also the answer to "why is
        // nothing listed", i.e. the file has no animations.
        var clips = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    var names = model().clipNames();
                    Photon.LOGGER.info("{} animations in {}: {}", names.size(), modelLocation, names);
                }).setText("photon.model_source.animated_gltf_model.list_animations")
                .layout(layout -> layout.alignSelf(AlignItems.CENTER)));

        var reloadButton = new Configurator().addInlineChild(new Button()
                .setOnClick(event -> {
                    invalidate();
                    buttonConfigurator.notifyChanges();
                }).setText("photon.reload_mesh").layout(layout -> layout.alignSelf(AlignItems.CENTER)));
        father.addConfigurators(buttonConfigurator, clips, reloadButton);
    }

    /**
     * ⚠️ Equality covers every field that changes the <b>geometry</b>, which includes the clock's inputs:
     * two emitters differing only in {@code speed} are at different poses and must not share a render pass.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        var that = (AnimatedGltfModelSource) o;
        return flipV == that.flipV && loop == that.loop
                && Float.compare(speed, that.speed) == 0
                && animation.equals(that.animation)
                && Objects.equals(modelLocation, that.modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV, animation, speed, loop);
    }
}
