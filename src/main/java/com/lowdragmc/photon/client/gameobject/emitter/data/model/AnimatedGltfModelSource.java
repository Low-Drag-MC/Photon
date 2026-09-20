package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.LDLib2;
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
import java.util.ArrayList;
import java.util.Objects;

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

    private PhotonMeshCache.GltfKey key() {
        return new PhotonMeshCache.GltfKey(modelLocation, flipV);
    }

    /** Shared with {@link GltfModelSource}: one parse serves both. */
    private SkinnedModel model() {
        return PhotonMeshCache.INSTANCE.getModel(key(), k -> load());
    }

    @Override
    public PhotonMesh getMesh() {
        return meshCache.resolve(this);
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
                && Objects.equals(modelLocation, that.modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelLocation, flipV, animation, speed, loop);
    }
}
