package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.editor.ClipboardManager;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.math.Transform;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ElementBounds;
import com.lowdragmc.lowdraglib2.uitest.ElementRef;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.client.fx.timeline.ActivatorTrack;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.ControlTrack;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.TrackGroup;
import com.lowdragmc.photon.client.fx.timeline.property.PositionPropertyType;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomData;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXObjectClipboard;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.FXObjectTreeNode;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Copy, paste and duplicate of FX objects through the real chords, from the hierarchy, the scene view and the
 * context menu: fresh ids, references and bound tracks following the copies, where the copies land, what ends up
 * selected, the clipboard left alone by a duplicate, and undo/redo.
 */
@LDLRegisterClient(name = "fx_object_clipboard", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxObjectClipboardScenario implements UIScenario {

    @Override
    public void define(ScenarioBuilder s) {
        s.step("custom data fits its channels to what it declares", FxObjectClipboardScenario::checkCustomData)
        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
        .step("load a parent with two children and one more object", FxObjectClipboardScenario::loadProject)
        .frames(10);

        s.group("Ctrl+D in the hierarchy", g -> {
            clickRow(g, "Parent");
            g.step("the hierarchy has the focus",
                    ctx -> ctx.check("hierarchy focused", editor(ctx).hierarchyView.isFocused()));
            chord(g, GLFW.GLFW_KEY_D);
            g.step("the subtree was duplicated", FxObjectClipboardScenario::checkDuplicate);
            chord(g, GLFW.GLFW_KEY_D);
            g.step("a second Ctrl+D duplicates the duplicate", ctx -> {
                var added = added(ctx);
                ctx.check("six objects added", added.size() == 6, 6, added.size());
                var copy = ctx.<IFXObject>get("firstCopy");
                var second = added.stream().filter(o -> o != copy && "Parent".equals(o.getName())).toList();
                ctx.check("one more parent, under the root", second.size() == 1
                        && second.getFirst().transform().parent() == root(ctx), 1, second.size());
            });
            chord(g, GLFW.GLFW_KEY_Z);
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("two undos take both away", ctx -> checkOriginal(ctx, "after undo"));
            chord(g, GLFW.GLFW_KEY_Y);
            g.step("redo brings the first back", ctx -> {
                ctx.check("three objects back", added(ctx).size() == 3, 3, added(ctx).size());
                ctx.check("three tracks back", addedTracks(ctx).size() == 3, 3, addedTracks(ctx).size());
                var copy = ctx.<IFXObject>get("firstCopy");
                ctx.check("the same copy, linked as before",
                        copy.transform().parent() == root(ctx) && copy.children().size() == 2);
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("and undo again", ctx -> checkOriginal(ctx, "after the last undo"));
        });

        s.group("Ctrl+C then Ctrl+V", g -> {
            clickRow(g, "Parent");
            chord(g, GLFW.GLFW_KEY_C);
            g.step("copying adds nothing", ctx -> {
                ctx.check("an FX object clipboard",
                        ClipboardManager.INSTANCE.getClipboardType() == FXObjectClipboard.class);
                ctx.check("no objects added", added(ctx).isEmpty(), 0, added(ctx).size());
                object(ctx, "Parent").setName("Renamed");
                var tree = editor(ctx).hierarchyView.treeList;
                tree.expandNode(nodeOf(ctx, object(ctx, "Renamed")));
            });
            g.frames(2);
            clickRow(g, "Child2");
            chord(g, GLFW.GLFW_KEY_V);
            g.step("the snapshot is pasted next to the selection", ctx -> {
                var added = added(ctx);
                ctx.check("three objects added", added.size() == 3, 3, added.size());
                var pasted = named(added, "Parent");
                ctx.check("the copy-time state, not the later rename", pasted != null);
                if (pasted == null) return;
                ctx.check("a sibling of the selected Child2",
                        pasted.transform().parent() == object(ctx, "Renamed").transform());
                ctx.check("fresh ids", freshIds(ctx, added));
                ctx.check("the paste is selected", selectedKeys(ctx).equals(Set.of(pasted)), pasted, selectedKeys(ctx));
                ctx.put("firstPaste", pasted);
            });
            chord(g, GLFW.GLFW_KEY_V);
            g.step("a second paste gets ids of its own", ctx -> {
                var added = added(ctx);
                ctx.check("six objects added", added.size() == 6, 6, added.size());
                var ids = new HashSet<UUID>();
                added.forEach(o -> ids.add(o.id()));
                ctx.check("all six ids distinct", ids.size() == 6, 6, ids.size());
                ctx.check("the second lands next to the first",
                        added.stream().filter(o -> "Parent".equals(o.getName()))
                                .allMatch(o -> o.transform().parent() == object(ctx, "Renamed").transform()));
            });
            chord(g, GLFW.GLFW_KEY_Z);
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("both pastes undone", ctx -> {
                object(ctx, "Renamed").setName("Parent");
                checkOriginal(ctx, "after undoing the pastes");
            });
        });

        s.group("Ctrl+D on two siblings", g -> {
            g.step("select both children", ctx -> editor(ctx).hierarchyView.treeList.setSelected(
                    Set.of(nodeOf(ctx, object(ctx, "Child")), nodeOf(ctx, object(ctx, "Child2"))), true));
            chord(g, GLFW.GLFW_KEY_D);
            g.step("each copy lands next to its original", ctx -> {
                var added = added(ctx);
                ctx.check("two objects added", added.size() == 2, 2, added.size());
                var parent = object(ctx, "Parent").transform();
                ctx.check("both under Parent, not the root",
                        added.stream().allMatch(o -> o.transform().parent() == parent));
                ctx.check("both selected", selectedKeys(ctx).equals(Set.copyOf(added)), added, selectedKeys(ctx));
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undone", ctx -> checkOriginal(ctx, "after undoing the sibling duplicate"));
        });

        s.group("chords in the scene view", g -> {
            clickScene(g);
            g.step("the scene view has the focus", ctx -> {
                ctx.check("scene view focused", editor(ctx).sceneView.isFocused());
                editor(ctx).hierarchyView.treeList.setSelected(Set.of(nodeOf(ctx, object(ctx, "Other"))), true);
                // Other's position is animated; the preview showing its last frame is when a copy used to freeze there
                seek(ctx, 30);
            });
            chord(g, GLFW.GLFW_KEY_D);
            g.step("Ctrl+D in the scene duplicated the selection", ctx -> {
                var added = added(ctx);
                ctx.check("one object added", added.size() == 1, 1, added.size());
                ctx.check("a copy of Other under the root", !added.isEmpty()
                        && "Other".equals(added.getFirst().getName()) && added.getFirst().transform().parent() == root(ctx));
            });
            g.step("the copy is animated like Other, not frozen on the frame it was copied at", ctx -> {
                var other = object(ctx, "Other");
                var copy = added(ctx).getFirst();
                ctx.check("the copy has an animation track of its own", addedTracks(ctx).stream()
                        .anyMatch(t -> t instanceof AnimationTrack && copy.id().equals(t.targetId())));
                seek(ctx, 0);
                var start = new float[]{x(other), x(copy)};
                seek(ctx, 10);
                var middle = new float[]{x(other), x(copy)};
                seek(ctx, 30);
                var end = new float[]{x(other), x(copy)};
                ctx.check("both start at x=0", Math.abs(start[0]) < 1e-3f && Math.abs(start[1]) < 1e-3f,
                        "0, 0", start[0] + ", " + start[1]);
                ctx.check("both move together mid-way", middle[0] > 0.5f && middle[0] < 9.5f && middle[0] == middle[1],
                        "equal, inside (0, 10)", middle[0] + ", " + middle[1]);
                ctx.check("both end at x=10", Math.abs(end[0] - 10) < 1e-3f && Math.abs(end[1] - 10) < 1e-3f,
                        "10, 10", end[0] + ", " + end[1]);
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("Ctrl+Z in the scene undid it", ctx -> checkOriginal(ctx, "after undo in the scene"));
        });

        s.group("the context menu", g -> {
            clickRow(g, "Parent");
            rightClickRow(g, "Parent");
            g.waitUntil("the menu offers Duplicate and Paste",
                    ctx -> menuEntry(ctx, "Duplicate") != null && menuEntry(ctx, "Paste") != null);
            clickOn(g, "Duplicate in the menu", Keys.MOUSE_LEFT,
                    ctx -> centreOf(Objects.requireNonNull(menuEntry(ctx, "Duplicate"))));
            g.frames(2);
            g.step("the menu duplicated the subtree and gave the keyboard back", ctx -> {
                ctx.check("three objects added", added(ctx).size() == 3, 3, added(ctx).size());
                ctx.check("the hierarchy has the focus again", editor(ctx).hierarchyView.isFocused());
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("so Ctrl+Z right after undoes it", ctx -> checkOriginal(ctx, "after undoing the menu duplicate"));
            clickRow(g, "Parent");
            rightClickRow(g, "Parent");
            g.waitUntil("the menu offers Copy", ctx -> menuEntry(ctx, "Copy") != null);
            clickOn(g, "Copy in the menu", Keys.MOUSE_LEFT, ctx -> centreOf(Objects.requireNonNull(menuEntry(ctx, "Copy"))));
            g.frames(2);
            chord(g, GLFW.GLFW_KEY_V);
            g.step("Ctrl+V right after the menu's Copy pastes", ctx ->
                    ctx.check("three objects added", added(ctx).size() == 3, 3, added(ctx).size()));
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undone", ctx -> checkOriginal(ctx, "after undoing the paste"));
        });

        s.group("Remove takes the subtree with it", g -> {
            clickRow(g, "Parent");
            rightClickRow(g, "Parent");
            g.waitUntil("the menu offers Remove", ctx -> menuEntry(ctx, "Remove") != null);
            clickOn(g, "Remove in the menu", Keys.MOUSE_LEFT,
                    ctx -> centreOf(Objects.requireNonNull(menuEntry(ctx, "Remove"))));
            g.frames(2);
            g.step("Parent and both children left the project", ctx -> {
                var objects = editor(ctx).runtime.fxData.objects();
                ctx.check("only Other is saved", objects.equals(List.of(object(ctx, "Other"))), "[Other]", objects);
                ctx.check("the runtime holds the root and Other", editor(ctx).runtime.objects.size() == 2,
                        2, editor(ctx).runtime.objects.size());
            });
            g.step("undo puts the subtree back", ctx -> {
                editor(ctx).historyView.undo();
                var objects = editor(ctx).runtime.fxData.objects();
                ctx.check("all four objects saved again", objects.size() == 4
                        && Set.copyOf(objects).equals(Set.copyOf(ctx.<List<IFXObject>>get("originalObjects"))), 4, objects);
                ctx.check("Parent keeps its children",
                        object(ctx, "Parent").children().equals(List.of(object(ctx, "Child"), object(ctx, "Child2"))));
                ctx.check("the runtime holds them all", editor(ctx).runtime.objects.size() == 5,
                        5, editor(ctx).runtime.objects.size());
            });
            g.step("redo, save to a file and open it again", ctx -> {
                var editor = editor(ctx);
                editor.historyView.redo();
                try {
                    var file = Files.createTempFile("photon_fx_object_clipboard", ".fxproj").toFile();
                    file.deleteOnExit();
                    FXProject.TYPE.saveProjectToFile((FXProject) editor.getCurrentProject(), file);
                    editor.closeCurrentProject(false, null);
                    editor.loadProject(FXProject.TYPE.loadProjectFromFile(file), file);
                } catch (Exception e) {
                    throw new IllegalStateException("could not round-trip the project", e);
                }
            });
            g.frames(2);
            g.step("the children did not come back as top-level objects", ctx -> {
                var runtime = editor(ctx).runtime;
                var saved = runtime.fxData.objects().stream().map(IFXObject::getName).toList();
                var top = runtime.root.children().stream().map(child -> ((IFXObject) child).getName()).toList();
                ctx.check("the file holds only Other", saved.equals(List.of("Other")), "[Other]", saved);
                ctx.check("and only Other sits under the root", top.equals(List.of("Other")), "[Other]", top);
            });
        });

        s.teardown("close the project", ctx -> {
            ClipboardManager.INSTANCE.clear();
            FXEditor editor = ctx.get("editor");
            if (editor != null) editor.closeCurrentProject(false, null);
        });
    }

    private static void loadProject(TestContext ctx) {
        var parent = new EmptyFXObject();
        parent.setName("Parent");
        parent.transform().localPosition(new Vector3f(2, 0, 0));
        var child = new ParticleEmitter();
        child.setName("Child");
        var child2 = new ParticleEmitter();
        child2.setName("Child2");
        var other = new EmptyFXObject();
        other.setName("Other");
        child.transform().parent(parent.transform(), false);
        child2.transform().parent(parent.transform(), false);
        child.config.customSpace.setTransformId(parent.id());
        child2.config.customSpace.setTransformId(other.id());

        var activator = PhotonTrackTypes.ACTIVATOR.create().targetId(child.id());
        activator.clips().add(new Clip(0, 20, 1));
        var control = PhotonTrackTypes.CONTROL.create();
        control.clips().add(new Clip(0, 10, 1).targetId(child.id()));
        control.clips().add(new Clip(10, 10, 1).targetId(other.id()));
        var group = (TrackGroup) PhotonTrackTypes.GROUP.create();
        group.children().add(PhotonTrackTypes.ACTIVATOR.create().targetId(child2.id()));
        group.children().add(PhotonTrackTypes.ACTIVATOR.create().targetId(other.id()));
        var unrelated = PhotonTrackTypes.ACTIVATOR.create().targetId(other.id());
        var animation = (AnimationTrack) PhotonTrackTypes.ANIMATION.create().targetId(other.id());
        var position = PositionPropertyType.INSTANCE.create(other);
        position.putKey(0, 0, 0);
        position.putKey(0, 20, 10);
        animation.properties().add(position);

        var project = new FXProject();
        var data = project.getFx().getFxData();
        data.objects().addAll(List.of(parent, child, child2, other));
        data.timeline().tracks().addAll(List.of(activator, control, group, unrelated, animation));
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
        ctx.put("originalObjects", List.copyOf(data.objects()));
        ctx.put("originalTracks", List.copyOf(data.timeline().tracks()));
        ctx.put("activator", activator);
        ctx.put("control", control);
        ctx.put("group", group);
        ClipboardManager.INSTANCE.clear();
        var tree = editor.hierarchyView.treeList;
        tree.expandNode(Objects.requireNonNull(editor.hierarchyView.getRootNode()));
    }

    private static void checkDuplicate(TestContext ctx) {
        var added = added(ctx);
        ctx.check("three objects added", added.size() == 3, 3, added.size());
        var copy = named(added, "Parent");
        var child = (ParticleEmitter) named(added, "Child");
        var child2 = (ParticleEmitter) named(added, "Child2");
        if (copy == null || child == null || child2 == null) {
            ctx.check("Parent, Child and Child2 were copied", false, "all three", added);
            return;
        }
        ctx.put("firstCopy", copy);
        ctx.check("every copy has a fresh id", freshIds(ctx, added));
        ctx.check("the copy sits where the original does", copy.transform().parent() == root(ctx));
        ctx.check("children keep their order", copy.children().equals(List.of(child, child2)),
                "[Child, Child2]", copy.children());
        ctx.check("the copy keeps its local position",
                copy.transform().localPosition().distance(2, 0, 0) < 1e-4f, "(2, 0, 0)", copy.transform().localPosition());
        ctx.check("a reference inside the copy follows it",
                copy.id().equals(child.config.customSpace.getTransformId()), copy.id(), child.config.customSpace.getTransformId());
        ctx.check("a reference outside still points out",
                object(ctx, "Other").id().equals(child2.config.customSpace.getTransformId()));

        var tracks = addedTracks(ctx);
        ctx.check("three tracks follow the copies", tracks.size() == 3, 3, tracks.size());
        var activator = tracks.stream().filter(ActivatorTrack.class::isInstance).findFirst().orElse(null);
        ctx.check("the activator drives the copied child",
                activator != null && child.id().equals(activator.targetId()));
        var control = tracks.stream().filter(ControlTrack.class::isInstance).findFirst().orElse(null);
        ctx.check("the control track keeps only the copied child's clip", control != null
                && control.clips().size() == 1 && child.id().equals(control.clips().getFirst().targetId()));
        var group = tracks.stream().filter(TrackGroup.class::isInstance).map(TrackGroup.class::cast).findFirst().orElse(null);
        ctx.check("the group keeps only the copied child's track", group != null && group.children().size() == 1
                && child2.id().equals(group.children().getFirst().targetId()));

        var original = object(ctx, "Parent");
        ctx.check("the original keeps its children",
                original.children().equals(List.of(object(ctx, "Child"), object(ctx, "Child2"))));
        ctx.check("the original's tracks are untouched",
                object(ctx, "Child").id().equals(ctx.<Track>get("activator").targetId())
                        && ctx.<Track>get("control").clips().size() == 2
                        && ctx.<TrackGroup>get("group").children().size() == 2);

        ctx.check("the copy is selected", selectedKeys(ctx).equals(Set.of(copy)), copy, selectedKeys(ctx));
        ctx.check("the inspector lets go of the original",
                editor(ctx).inspectorView.inspector.getInspectedConfigurable() == null);
        ctx.check("and so does the gizmo",
                editor(ctx).sceneView.sceneEditor.getTransformGizmo().getTargetTransform() == null);
        ctx.check("the clipboard is left alone", ClipboardManager.INSTANCE.getClipboardType() == null,
                null, ClipboardManager.INSTANCE.getClipboardType());
        var view = editor(ctx).hierarchyView;
        ctx.check("the hierarchy stays expanded",
                view.getRootNode() != null && view.treeList.isNodeExpanded(view.getRootNode()));
    }

    private static void checkOriginal(TestContext ctx, String when) {
        var editor = editor(ctx);
        var objects = editor.runtime.fxData.objects();
        ctx.check("only the original objects " + when, objects.equals(ctx.get("originalObjects")),
                ctx.<List<IFXObject>>get("originalObjects").size(), objects.size());
        ctx.check("only the original tracks " + when,
                editor.runtime.fxData.timeline().tracks().equals(ctx.get("originalTracks")));
        ctx.check("the runtime holds the root and the originals " + when, editor.runtime.objects.size() == 5,
                5, editor.runtime.objects.size());
    }

    private static void checkCustomData(TestContext ctx) {
        var vector = new CustomData();
        var tag = vector.toNBT();
        tag.putInt("channelCount", 3);
        var short3 = CustomData.fromNBT(tag);
        ctx.check("a stream declaring 3 channels but storing 1 gets 3",
                short3.getChannels().size() == 3 && short3.getChannelName(2).equals("[2]"), 3, short3.getChannels().size());

        var four = new CustomData();
        four.setChannelCount(4);
        tag = four.toNBT();
        tag.putInt("channelCount", 2);
        var long2 = CustomData.fromNBT(tag);
        ctx.check("a stream declaring 2 channels but storing 4 keeps 2", long2.getChannels().size() == 2,
                2, long2.getChannels().size());

        var color = new CustomData();
        color.setType(CustomData.Type.COLOR);
        tag = color.toNBT();
        var channels = tag.getList("channels", Tag.TAG_COMPOUND);
        var doubled = new ListTag();
        doubled.add(channels.getFirst().copy());
        doubled.add(channels.getFirst().copy());
        tag.put("channels", doubled);
        var color1 = CustomData.fromNBT(tag);
        ctx.check("a colour stream keeps a single function", color1.getChannels().size() == 1,
                1, color1.getChannels().size());

        var copied = short3.copy();
        ctx.check("a copy keeps the fitted channels", copied.getChannels().size() == 3, 3, copied.getChannels().size());
    }

    private static float x(IFXObject object) {
        return object.transform().localPosition().x;
    }

    private static void seek(TestContext ctx, long tick) {
        var scene = editor(ctx).sceneView;
        scene.particleManager.pause();
        scene.simulateTo(tick);
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static Transform root(TestContext ctx) {
        return editor(ctx).runtime.root.transform();
    }

    private static IFXObject object(TestContext ctx, String name) {
        return ctx.<List<IFXObject>>get("originalObjects").stream()
                .filter(o -> name.equals(o.getName())).findFirst().orElseThrow();
    }

    private static List<IFXObject> added(TestContext ctx) {
        var added = new ArrayList<>(editor(ctx).runtime.fxData.objects());
        added.removeAll(ctx.<List<IFXObject>>get("originalObjects"));
        return added;
    }

    private static List<Track> addedTracks(TestContext ctx) {
        var added = new ArrayList<>(editor(ctx).runtime.fxData.timeline().tracks());
        added.removeAll(ctx.<List<Track>>get("originalTracks"));
        return added;
    }

    @Nullable
    private static IFXObject named(List<IFXObject> objects, String name) {
        var matches = objects.stream().filter(o -> name.equals(o.getName())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean freshIds(TestContext ctx, List<IFXObject> added) {
        var taken = new HashSet<UUID>();
        ctx.<List<IFXObject>>get("originalObjects").forEach(o -> taken.add(o.id()));
        taken.add(editor(ctx).runtime.root.id());
        return added.stream().allMatch(o -> taken.add(o.id()));
    }

    private static Set<IFXObject> selectedKeys(TestContext ctx) {
        var keys = new HashSet<IFXObject>();
        editor(ctx).hierarchyView.treeList.getSelected().forEach(node -> keys.add(node.getKey()));
        return keys;
    }

    private static FXObjectTreeNode nodeOf(TestContext ctx, IFXObject object) {
        var node = editor(ctx).hierarchyView.getRootNode();
        while (node != null && node.getKey() != object) {
            node = node.getChildren().stream().filter(child -> child.getKey() == object
                    || object.transform().isInheritedParent(child.getKey().transform())).findFirst().orElse(null);
        }
        return Objects.requireNonNull(node, "no tree node for " + object.getName());
    }

    private static void chord(ScenarioBuilder s, int key) {
        s.key(key, Keys.MOD_CONTROL).frames(2);
    }

    /** Tags the hierarchy row showing {@code name} so the click resolves to it, then clicks it. */
    private static void clickRow(ScenarioBuilder s, String name) {
        var id = "row-" + name.toLowerCase();
        s.step("find the " + name + " row", ctx -> rowLabel(ctx, name).setId(id));
        s.click("#" + id);
    }

    private static void rightClickRow(ScenarioBuilder s, String name) {
        clickOn(s, "the " + name + " row with the right button", Keys.MOUSE_RIGHT, ctx -> centreOf(rowLabel(ctx, name)));
    }

    /** A spot in the scene view clear of the gizmo, the top bar and the corner overlays. */
    private static void clickScene(ScenarioBuilder s) {
        clickOn(s, "the scene", Keys.MOUSE_LEFT, ctx -> {
            var bounds = ElementBounds.of(editor(ctx).sceneView.sceneEditor);
            return new float[]{bounds.x() + bounds.width() * 0.85f, bounds.y() + bounds.height() * 0.35f};
        });
    }

    /**
     * Hover, press and release at one spot, resolved once: a menu opens or closes on the press, so the target
     * is covered or gone by the release.
     */
    private static void clickOn(ScenarioBuilder s, String label, int button, Function<TestContext, float[]> spot) {
        s.step("hover " + label, ctx -> {
            var at = ctx.put("clickAt", spot.apply(ctx));
            ctx.input().moveTo(at[0], at[1]);
        });
        s.step("press " + label, ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseDown(at[0], at[1], button);
        });
        s.step("release " + label, ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseUp(at[0], at[1], button);
        });
    }

    private static float[] centreOf(UIElement element) {
        var bounds = ElementBounds.of(element);
        return new float[]{bounds.centerX(), bounds.centerY()};
    }

    private static UIElement rowLabel(TestContext ctx, String name) {
        var view = editor(ctx).hierarchyView;
        return ctx.query().type(TextElement.class).withText(name).where(element -> isInside(element, view)).one().element();
    }

    /** Menu labels count as internal elements, which {@code withText} skips, so this matches the text itself. */
    @Nullable
    private static UIElement menuEntry(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).visible().list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> element.getFirstAncestorOfType(Menu.class) != null)
                .findFirst().orElse(null);
    }

    private static boolean isInside(UIElement element, UIElement ancestor) {
        for (var e = element; e != null; e = e.getParent()) {
            if (e == ancestor) return true;
        }
        return false;
    }
}
