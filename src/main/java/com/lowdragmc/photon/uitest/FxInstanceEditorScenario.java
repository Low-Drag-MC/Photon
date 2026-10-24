package com.lowdragmc.photon.uitest;

import com.lowdragmc.photon.client.gameobject.light.LightObject;
import com.lowdragmc.photon.client.gameobject.light.LightConfig;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.EditAction;
import com.lowdragmc.lowdraglib2.configurator.ui.ArrayConfiguratorGroup;
import com.lowdragmc.lowdraglib2.configurator.ui.Configurator;
import com.lowdragmc.lowdraglib2.configurator.ui.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib2.editor.ClipboardManager;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ElementBounds;
import com.lowdragmc.lowdraglib2.uitest.ElementRef;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.instance.InstanceDiffRules;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceRecord;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.fx.timeline.AnimationTrack;
import com.lowdragmc.photon.client.fx.timeline.Clip;
import com.lowdragmc.photon.client.fx.timeline.Track;
import com.lowdragmc.photon.client.fx.timeline.property.PositionPropertyType;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXInstanceEditing;
import com.lowdragmc.photon.gui.editor.FXInstanceInspector;
import java.util.function.BiFunction;
import com.lowdragmc.photon.gui.editor.FXInstanceOverridesDialog;
import com.lowdragmc.photon.gui.editor.FXInstanceStyle;
import com.lowdragmc.photon.gui.editor.FXObjectClipboard;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.FXObjectTreeNode;
import com.lowdragmc.photon.gui.editor.view.timeline.PhotonTrackTypes;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * FX instances in the editor, by the gestures an author uses: an .fx dragged from the asset browser into the
 * hierarchy, the hierarchy's menu, typing into the inspector, its row menu, Ctrl+Z / Y / C / V, saving and
 * reopening, and the source changing on disk; then what those must not break: a source restructured or only baked
 * again, an anchor waiting for its source, an export before an undo to opening, an instance inside another, a group
 * row's revert, a paste that would place the effect inside itself, and marks on a list's rows.
 */
@LDLRegisterClient(name = "fx_instance_editor", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxInstanceEditorScenario implements UIScenario {
    private static final String ROOT = "uitest_instance_editor";
    private static String dir = ROOT;
    private static final UUID B_EMIT = id("b_emit");
    private static final UUID B_LATE = id("b_late");
    private static final UUID B_GROUP = id("b_group");
    private static final UUID B_CHILD = id("b_child");

    @Override
    public void define(ScenarioBuilder s) {
        s.step("an override is named by the rows it runs through", ctx -> {
            var emitter = new ParticleEmitter();
            BiFunction<String, String, Void> named = (path, expected) -> {
                var name = FXInstanceOverridesDialog.describe(emitter, List.of(path.split("/")));
                ctx.check(path, name.equals(expected), expected, name);
                return null;
            };
            named.apply("config/duration", I18n.get("ParticleConfig.duration"));
            named.apply("config/startColor/data/number", I18n.get("ParticleConfig.startColor"));
            named.apply("_additional", I18n.get("photon.fx_object.enabled"));
            named.apply("nothing/like/this", "nothing/like/this");
            var material = FXInstanceOverridesDialog.describe(emitter, List.of("config", "renderer", "materials", "payload", "0"));
            ctx.check("a list element is numbered", material.startsWith(I18n.get("ParticleConfig.renderer")) && material.endsWith("#1"),
                    I18n.get("ParticleConfig.renderer") + " › … › #1", material);
        });
        s.step("write the fixtures", FxInstanceEditorScenario::writeFixtures)
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
                .step("load a project with one object", FxInstanceEditorScenario::loadProject)
                .frames(10);

        s.group("the asset browser lists .fx files", g -> {
            g.step("open the fixture folder", ctx -> browser(ctx).openDirectory(fixtureDir()));
            g.waitUntil("the folder is shown", ctx -> fixtureDir().equals(browser(ctx).getCurrentDirectory())
                    && entry(ctx, "base") != null);
            g.step("base.fx shows without 'show all files'", ctx -> {
                var entry = entry(ctx, "base");
                ctx.check("there is an entry for base.fx", entry != null, "an entry", null);
                if (entry != null) entry.setId("entry-base");
            });
            g.frames(2);
            g.step("show the .fx icons", ctx -> ctx.screenshot("asset_browser_fx"));
        });

        s.group("drag an .fx into the hierarchy", g -> {
            dragPoints(g, "base.fx onto the hierarchy", ctx -> centreOf(entry(ctx, "base")), FxInstanceEditorScenario::emptyHierarchySpot);
            g.frames(4);
            g.step("an instance was placed under the root", ctx -> {
                ctx.log("after drop: runtime %s, history %s".formatted(System.identityHashCode(editor(ctx).runtime), describeHistory(ctx)));
                var instance = instanceNamed(ctx, "base");
                ctx.require("an instance named base", instance != null);
                ctx.put("instance", instance);
                var editor = editor(ctx);
                ctx.check("under the root", instance.transform().parent() == editor.runtime.root.transform(), "root",
                        instance.transform().parent());
                ctx.check("it is an instance", editor.instances.role(instance) == FXInstanceEditing.Role.INSTANCE,
                        FXInstanceEditing.Role.INSTANCE, editor.instances.role(instance));
                var placed = List.of(B_EMIT, B_LATE, B_GROUP, B_CHILD).stream()
                        .map(source -> editor.runtime.objects.get(InstanceIds.xor(instance.id(), source))).toList();
                ctx.check("it placed every object of the source under its derived id", placed.stream().allMatch(o -> o != null
                        && editor.instances.role(o) == FXInstanceEditing.Role.PLACED), "4 placed", placed);
                expand(ctx, instance);
            });
            g.frames(2);
            focusHierarchy(g);
            g.step("its rows are drawn in the instance color", ctx -> {
                var label = rowLabel(ctx, "b_emit");
                ctx.check("b_emit's row is blue", label instanceof TextElement text
                        && text.getTextStyle().textColor() == FXInstanceStyle.PLACED_COLOR, FXInstanceStyle.PLACED_COLOR, label);
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undo takes it away", ctx -> {
                ctx.log("after undo: runtime %s, objects %s, history %s".formatted(System.identityHashCode(editor(ctx).runtime),
                        editor(ctx).runtime.objects.values().stream().map(IFXObject::getName).toList(), describeHistory(ctx)));
                ctx.check("no instance", instanceNamed(ctx, "base") == null, "none", "still there");
            });
            chord(g, GLFW.GLFW_KEY_Y);
            g.step("redo brings back the same one", ctx -> {
                ctx.log("after redo: runtime %s, objects %s, history %s".formatted(System.identityHashCode(editor(ctx).runtime),
                        editor(ctx).runtime.objects.values().stream().map(IFXObject::getName).toList(), describeHistory(ctx)));
                ctx.check("the same instance", instanceNamed(ctx, "base") == ctx.<FXInstanceObject>get("instance"), "same", "other");
                expand(ctx, ctx.get("instance"));
            });
            g.frames(2);
        });

        s.group("add one from the hierarchy's menu", g -> {
            clickRow(g, "Plain");
            rightClickRow(g, "Plain");
            g.frames(2);
            clickMenu(g, "Add FX Instance...");
            g.frames(2);
            g.step("the picker is open: choose the other effect", ctx -> {
                var picker = ctx.query().type(SearchComponentConfigurator.class).list();
                ctx.require("a picker", !picker.isEmpty());
                Consumer<String> onUpdate = ctx.getField(picker.getFirst().element(), "onUpdate");
                onUpdate.accept(fx("other").toString());
                ctx.put("pickerConfirm", centreOf(buttonWithText(ctx, "Confirm")));
            });
            clickOn(g, "the picker's Confirm", Keys.MOUSE_LEFT, ctx -> ctx.get("pickerConfirm"));
            g.frames(2);
            g.step("an instance of it sits beside Plain", ctx -> {
                var other = instanceNamed(ctx, "other");
                ctx.require("an instance of other", other != null);
                ctx.check("a sibling of Plain", other.transform().parent() == plain(ctx).transform().parent(), "the root", other.transform().parent());
                ctx.check("it placed its object", editor(ctx).runtime.objects.get(InstanceIds.xor(other.id(), id("o_emit"))) != null, "o_emit", null);
            });
        });

        s.group("an edited value is marked and reverts from its row", g -> {
            clickRow(g, "b_emit");
            g.frames(2);
            g.step("the inspector says where it comes from", ctx -> ctx.check("a 'from FX instance' header",
                    !ctx.query().withId("photon_fx_instance_placed_header").list().isEmpty(), "a header", "none"));
            g.step("find the duration field", ctx -> durationField(ctx).setId("duration-field"));
            g.typeInto("#duration-field", "77\n");
            g.frames(4);
            g.step("the value changed and its row is marked", ctx -> {
                ctx.check("duration is 77", emitter(ctx).config.getDuration() == 77, 77, emitter(ctx).config.getDuration());
                ctx.check("the row is marked overridden", durationRow(ctx).isOverridden(), "marked", "not marked");
                ctx.check("the object reads as overridden", editor(ctx).instances.isOverridden(emitter(ctx)), "overridden", "not");
            });
            g.step("scroll the duration row into view", ctx -> KilaUiTestKit.scrollIntoView(durationRow(ctx)));
            g.frames(2);
            rightClickOn(g, "the duration row", ctx -> centreOf(durationRow(ctx).label));
            g.frames(2);
            clickMenu(g, "Revert");
            g.frames(4);
            focusHierarchy(g);
            g.step("revert put the source's value back", ctx -> {
                ctx.check("duration is 40 again", emitter(ctx).config.getDuration() == 40, 40, emitter(ctx).config.getDuration());
                ctx.check("the row is no longer marked", !durationRow(ctx).isOverridden(), "not marked", "marked");
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undo brings the edit back", ctx -> ctx.check("77", emitter(ctx).config.getDuration() == 77, 77,
                    emitter(ctx).config.getDuration()));
            chord(g, GLFW.GLFW_KEY_Y);
            g.step("redo reverts again", ctx -> ctx.check("40", emitter(ctx).config.getDuration() == 40, 40,
                    emitter(ctx).config.getDuration()));
        });

        s.group("the instance's header names its source and holds its actions in a menu", g -> {
            clickRow(g, "base");
            g.frames(2);
            g.step("a header without buttons", ctx -> {
                var header = ctx.query().withId("photon_fx_instance_header").list();
                ctx.require("the instance header", !header.isEmpty());
                var buttons = new ArrayList<String>();
                collectButtons(header.getFirst().element(), buttons);
                ctx.check("no buttons in it", buttons.isEmpty(), "none", buttons);
                // the label is as wide as its text, which a long id makes wider than the panel
                var label = header.getFirst().as(Configurator.class).label;
                ctx.put("headerAt", new float[]{label.getPositionX() + 12, label.getPositionY() + label.getSizeHeight() / 2});
            });
            rightClickOn(g, "the instance header", ctx -> ctx.get("headerAt"));
            g.frames(2);
            g.step("its menu holds the instance's actions", ctx -> {
                for (var entry : List.of("Open Source", "Overrides...", "Revert All", "Replace...")) {
                    ctx.check("'" + entry + "' is offered", menuEntry(ctx, entry) != null, entry, "missing");
                }
                ctx.screenshot("instance_header_menu");
            });
            clickMenu(g, "Overrides...");
            g.frames(2);
            g.step("which opens the overrides", ctx -> {
                ctx.check("a dialog", !ctx.query().type(Dialog.class).list().isEmpty(), "a dialog", "none");
                closeDialogs(ctx);
            });
            g.frames(2);
        });

        s.group("the placed object's header reverts it from its menu", g -> {
            g.step("override b_emit's duration", ctx -> {
                emitter(ctx).config.setDuration(123);
                editor(ctx).instances.changed();
            });
            clickRow(g, "b_emit");
            g.frames(2);
            g.step("scroll the header into view", ctx -> {
                var header = ctx.query().withId("photon_fx_instance_placed_header").list();
                ctx.require("the placed header", !header.isEmpty());
                KilaUiTestKit.scrollIntoView(header.getFirst().as(Configurator.class));
            });
            g.frames(2);
            g.step("find the header", ctx -> {
                var header = ctx.query().withId("photon_fx_instance_placed_header").list();
                var label = header.getFirst().as(Configurator.class).label;
                ctx.put("placedHeaderAt", new float[]{label.getPositionX() + 12, label.getPositionY() + label.getSizeHeight() / 2});
            });
            rightClickOn(g, "the placed header", ctx -> ctx.get("placedHeaderAt"));
            g.frames(2);
            clickMenu(g, "Revert Object");
            g.frames(4);
            g.step("its value is the source's again", ctx -> ctx.check("duration is 40", emitter(ctx).config.getDuration() == 40, 40,
                    emitter(ctx).config.getDuration()));
            focusHierarchy(g);
        });

        s.group("removing a placed object is an override, and it comes back", g -> {
            clickRow(g, "b_late");
            rightClickRow(g, "b_late");
            g.frames(2);
            clickMenu(g, "Remove");
            g.frames(4);
            g.step("it is gone and recorded as removed", ctx -> {
                ctx.check("b_late is gone", placedObject(ctx, B_LATE) == null, "gone", "there");
                var record = editor(ctx).instances.recordOf(instance(ctx));
                ctx.check("the record removes it", record.removed().equals(List.of(B_LATE)), List.of(B_LATE), record.removed());
            });
            clickRow(g, "base");
            rightClickRow(g, "base");
            g.frames(2);
            clickMenu(g, "FX Instance");
            g.frames(2);
            clickMenu(g, "Overrides...");
            g.frames(2);
            g.step("the overrides list offers it back", ctx -> {
                ctx.check("the list fits inside the dialog", overflowing(ctx).isEmpty(), "nothing sticks out", overflowing(ctx));
                ctx.screenshot("overrides_dialog");
                var restore = buttonInDialogNear(ctx, "Removed: b_late");
                ctx.require("a restore button beside 'Removed: b_late'", restore != null);
                ctx.put("restoreAt", centreOf(restore));
            });
            clickOn(g, "Restore beside b_late", Keys.MOUSE_LEFT, ctx -> ctx.get("restoreAt"));
            g.frames(4);
            g.step("it is back, with its id", ctx -> {
                ctx.check("b_late is back", placedObject(ctx, B_LATE) != null, "back", "gone");
                ctx.check("nothing is recorded removed", editor(ctx).instances.recordOf(instance(ctx)).removed().isEmpty(), "none",
                        editor(ctx).instances.recordOf(instance(ctx)).removed());
                closeDialogs(ctx);
            });
            g.frames(2);
        });

        s.group("disabling a placed object, and reverting it", g -> {
            clickRow(g, "b_child");
            rightClickRow(g, "b_child");
            g.frames(2);
            clickMenu(g, "Disable");
            g.frames(4);
            g.step("it is off and the record says so", ctx -> {
                var child = placedObject(ctx, B_CHILD);
                ctx.check("disabled", child != null && !child.isEnabled(), "disabled", child);
                var changes = editor(ctx).instances.changesOf(child);
                ctx.check("one change, the saved switch", changes.size() == 1 && changes.getFirst().path().equals(List.of("_additional")),
                        "[_additional]", changes);
            });
            rightClickRow(g, "b_child");
            g.frames(2);
            clickMenu(g, "Revert Object");
            g.frames(4);
            g.step("it is on again and overrides nothing", ctx -> {
                var child = placedObject(ctx, B_CHILD);
                ctx.check("enabled", child != null && child.isEnabled(), "enabled", child);
                ctx.check("no change left", editor(ctx).instances.changesOf(child).isEmpty(), "none", editor(ctx).instances.changesOf(child));
            });
        });

        s.group("objects can hang inside, the instance's own cannot move", g -> {
            g.step("tag the rows", ctx -> {
                rowLabel(ctx, "Plain").setId("row-plain");
                rowLabel(ctx, "b_group").setId("row-group");
            });
            g.drag("#row-plain", "#row-group");
            g.frames(4);
            g.step("Plain hangs under b_group as an added object", ctx -> {
                var plain = plain(ctx);
                ctx.check("under b_group", plain.transform().parent() == placedObject(ctx, B_GROUP).transform(), "b_group",
                        plain.transform().parent());
                ctx.check("an added object", editor(ctx).instances.role(plain) == FXInstanceEditing.Role.ADDED,
                        FXInstanceEditing.Role.ADDED, editor(ctx).instances.role(plain));
                ctx.check("its row reads '+ Plain'", rowLabel(ctx, "+ Plain") != null, "+ Plain", null);
            });
            g.step("tag the rows again", ctx -> {
                rowLabel(ctx, "b_emit").setId("row-emit");
                rowLabel(ctx, "other").setId("row-other");
            });
            g.drag("#row-emit", "#row-other");
            g.frames(4);
            g.step("a placed object stays where its source has it", ctx -> ctx.check("b_emit still under base",
                    placedObject(ctx, B_EMIT).transform().parent() == instance(ctx).transform(), "base",
                    placedObject(ctx, B_EMIT).transform().parent()));
        });

        s.group("save and reopen", g -> {
            g.step("edit a value again and bind a track to a placed object", ctx -> {
                emitter(ctx).config.setDuration(55);
                var track = PhotonTrackTypes.ACTIVATOR.create().targetId(InstanceIds.xor(instance(ctx).id(), B_CHILD));
                track.clips().add(new Clip(0, 20, 1));
                editor(ctx).runtime.fxData.timeline().tracks().add(track);
                editor(ctx).instances.changed();
            });
            g.step("save, close and open the file again", ctx -> {
                var editor = editor(ctx);
                try {
                    var file = Files.createTempFile("photon_fx_instance_editor", ".fxproj").toFile();
                    file.deleteOnExit();
                    ctx.put("file", file);
                    var project = (FXProject) editor.getCurrentProject();
                    FXProject.TYPE.saveProjectToFile(project, file);
                    ctx.put("saved", NbtIo.read(file.toPath()));
                    editor.closeCurrentProject(false, null);
                    editor.loadProject(FXProject.TYPE.loadProjectFromFile(file), file);
                } catch (Exception e) {
                    throw new IllegalStateException("could not round-trip the project", e);
                }
            });
            g.frames(4);
            g.step("the file holds records, not what the instances placed", ctx -> {
                CompoundTag saved = ctx.get("saved");
                var fxData = saved.getCompound("data").getCompound("fx").getCompound("fxData");
                var objects = InstanceTags.objects(fxData);
                var names = objects.stream().map(InstanceTags::name).toList();
                ctx.check("base, other and Plain only", names.size() == 3 && names.containsAll(List.of("base", "other", "Plain")),
                        "[base, other, Plain]", names);
                var base = objects.stream().filter(o -> "base".equals(InstanceTags.name(o))).findFirst().orElseThrow();
                var record = InstanceRecord.read(InstanceTags.data(base).getCompound(InstanceTags.OVERRIDES));
                ctx.check("its record has the edit", record.modified().size() == 1
                                && record.modified().getFirst().change().path().equals(List.of("config", "duration")),
                        "config/duration", record.modified());
                ctx.check("and where Plain hangs", record.added().equals(List.of(new InstanceRecord.Added(B_GROUP, id("Plain")))),
                        "(b_group, Plain)", record.added());
                ctx.check("and no timeline of the source", !InstanceTags.data(base).contains(InstanceTags.TIMELINE), "none",
                        InstanceTags.data(base).getAllKeys());
            });
            g.step("the reopened project is the same and not modified", ctx -> {
                var editor = editor(ctx);
                var instance = instanceNamed(ctx, "base");
                ctx.require("the instance", instance != null);
                ctx.put("instance", instance);
                ctx.check("the edit is there", emitter(ctx).config.getDuration() == 55, 55, emitter(ctx).config.getDuration());
                ctx.check("Plain hangs under b_group again", plain(ctx).transform().parent() == placedObject(ctx, B_GROUP).transform(),
                        "b_group", plain(ctx).transform().parent());
                ctx.check("the track still binds the placed object", editor.runtime.fxData.timeline().tracks().stream()
                        .map(Track::targetId).anyMatch(InstanceIds.xor(instance.id(), B_CHILD)::equals), "bound", "lost");
                ctx.check("the project is not modified", !editor.isCurrentProjectDirty(), "clean", "dirty");
                expand(ctx, instance);
            });
            g.frames(2);
        });

        s.group("copy and paste an instance", g -> {
            clickRow(g, "base");
            chord(g, GLFW.GLFW_KEY_C);
            clickRow(g, "other");
            chord(g, GLFW.GLFW_KEY_V);
            g.frames(4);
            g.step("the paste is another instance with the same overrides", ctx -> {
                var editor = editor(ctx);
                var copies = editor.runtime.objects.values().stream()
                        .filter(o -> o instanceof FXInstanceObject && "base".equals(o.getName())).toList();
                ctx.check("two instances of base", copies.size() == 2, 2, copies.size());
                var copy = (FXInstanceObject) copies.stream().filter(o -> o != instance(ctx)).findFirst().orElse(null);
                ctx.require("the copy", copy != null);
                ctx.check("a new id", !copy.id().equals(instance(ctx).id()), "new", copy.id());
                ctx.check("it is an instance", editor.instances.role(copy) == FXInstanceEditing.Role.INSTANCE,
                        FXInstanceEditing.Role.INSTANCE, editor.instances.role(copy));
                var copyEmit = (ParticleEmitter) editor.runtime.objects.get(InstanceIds.xor(copy.id(), B_EMIT));
                ctx.check("its placed objects follow its id", copyEmit != null, "copy^b_emit", null);
                ctx.check("with the override", copyEmit != null && copyEmit.config.getDuration() == 55, 55,
                        copyEmit == null ? null : copyEmit.config.getDuration());
                var added = editor.runtime.objects.values().stream().filter(o -> "Plain".equals(o.getName())).toList();
                ctx.check("and its own copy of Plain hanging inside", added.size() == 2 && added.stream().anyMatch(o ->
                                o.transform().parent() != null && o.transform().parent().sceneObject() == editor.runtime.objects
                                        .get(InstanceIds.xor(copy.id(), B_GROUP))), "a second Plain", added);
                ctx.check("the track bound to its b_child was copied with it", editor.runtime.fxData.timeline().tracks().stream()
                        .map(Track::targetId).anyMatch(InstanceIds.xor(copy.id(), B_CHILD)::equals), "bound", "missing");
                ctx.put("copy", copy);
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undo removes the paste", ctx -> ctx.check("one instance of base", editor(ctx).runtime.objects.values().stream()
                    .filter(o -> o instanceof FXInstanceObject && "base".equals(o.getName())).count() == 1, 1, "more"));
        });

        s.group("the source changes on disk", g -> {
            g.step("write base again with new values, as an export would", ctx -> {
                ctx.put("emitBefore", placedObject(ctx, B_EMIT));
                ctx.put("lateBefore", placedObject(ctx, B_LATE));
                try {
                    writeBase(300, 25);
                } catch (IOException e) {
                    ctx.require("rewrote base: " + e, false);
                }
                FXHelper.invalidate(List.of(fx("base")));
            });
            g.ticks(3);
            g.step("the instance follows what it does not override", ctx -> {
                var late = (ParticleEmitter) placedObject(ctx, B_LATE);
                ctx.check("b_late took the new duration", late != null && late.config.getDuration() == 25, 25,
                        late == null ? null : late.config.getDuration());
                ctx.check("b_emit kept its override", emitter(ctx).config.getDuration() == 55, 55, emitter(ctx).config.getDuration());
                ctx.check("the same objects, not new ones", placedObject(ctx, B_EMIT) == ctx.<Object>get("emitBefore")
                        && placedObject(ctx, B_LATE) == ctx.<Object>get("lateBefore"), "same", "replaced");
            });
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undo goes back to the source as it was", ctx -> {
                var late = (ParticleEmitter) placedObject(ctx, B_LATE);
                ctx.check("b_late is back at 40", late != null && late.config.getDuration() == 40, 40,
                        late == null ? null : late.config.getDuration());
            });
            chord(g, GLFW.GLFW_KEY_Y);
        });

        s.group("the timeline holds an instance for its source's length", g -> g.step("a control clip on it defaults to the source's timeline", ctx -> {
            var other = instanceNamed(ctx, "other");
            ctx.require("the other instance", other != null);
            var duration = editor(ctx).timelineView.defaultClipDuration(other.id());
            ctx.check("at least the 60 ticks of other's timeline", duration >= 60, ">= 60", duration);
        }));

        s.group("the asset browser's menu places an .fx too", g -> {
            g.step("show the folder", ctx -> browser(ctx).openDirectory(fixtureDir()));
            g.waitUntil("other.fx is listed", ctx -> entry(ctx, "other") != null);
            g.step("count the instances", ctx -> ctx.put("instancesBefore", editor(ctx).instances.instances().size()));
            rightClickOn(g, "other.fx", ctx -> centreOf(entry(ctx, "other")));
            g.frames(2);
            clickMenu(g, "Add as FX Instance");
            g.frames(4);
            g.step("one more instance of other", ctx -> ctx.check("one more instance",
                    editor(ctx).instances.instances().size() == ctx.<Integer>get("instancesBefore") + 1,
                    ctx.<Integer>get("instancesBefore") + 1, editor(ctx).instances.instances().size()));
        });

        s.group("Revert All puts the whole instance back", g -> {
            g.step("override, remove and add inside the instance", ctx -> {
                emitter(ctx).config.setDuration(66);
                editor(ctx).instances.changed();
            });
            clickRow(g, "b_late");
            rightClickRow(g, "b_late");
            g.frames(2);
            clickMenu(g, "Remove");
            g.frames(2);
            clickRow(g, "base");
            rightClickRow(g, "base");
            g.frames(2);
            clickMenu(g, "FX Instance");
            g.frames(2);
            clickMenu(g, "Revert All");
            g.frames(4);
            g.step("values, removed and added objects are the source's again", ctx -> {
                ctx.check("b_emit has the source's duration", emitter(ctx).config.getDuration() == 300, 300, emitter(ctx).config.getDuration());
                ctx.check("b_late is back", placedObject(ctx, B_LATE) != null, "back", "gone");
                ctx.check("Plain is out", editor(ctx).runtime.objects.values().stream().noneMatch(o -> o == ctx.<Object>get("plainBefore")
                        || ("Plain".equals(o.getName()) && editor(ctx).instances.owner(o) == instance(ctx))), "out", "still inside");
                ctx.check("nothing is overridden", editor(ctx).instances.recordOf(instance(ctx)).isPristine(), "pristine",
                        editor(ctx).instances.recordOf(instance(ctx)));
            });
            focusHierarchy(g);
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("one undo brings it all back", ctx -> {
                ctx.check("b_emit's override is back", emitter(ctx).config.getDuration() == 66, 66, emitter(ctx).config.getDuration());
                ctx.check("b_late is removed again", placedObject(ctx, B_LATE) == null, "removed", "there");
                ctx.check("Plain hangs inside again", editor(ctx).runtime.objects.values().stream().anyMatch(o -> "Plain".equals(o.getName())
                        && editor(ctx).instances.owner(o) == instance(ctx)), "inside", "out");
            });
        });

        s.group("Replace places another source", g -> {
            clickRow(g, "base");
            rightClickRow(g, "base");
            g.frames(2);
            clickMenu(g, "FX Instance");
            g.frames(2);
            clickMenu(g, "Replace...");
            g.frames(2);
            g.step("choose other", ctx -> {
                ctx.check("the picker fits inside its dialog", overflowing(ctx).isEmpty(), "nothing sticks out", overflowing(ctx));
                ctx.screenshot("picker_dialog");
                var picker = ctx.query().type(SearchComponentConfigurator.class).list();
                ctx.require("a picker", !picker.isEmpty());
                Consumer<String> onUpdate = ctx.getField(picker.getFirst().element(), "onUpdate");
                onUpdate.accept(fx("other").toString());
                ctx.put("pickerConfirm", centreOf(buttonWithText(ctx, "Confirm")));
            });
            clickOn(g, "the picker's Confirm", Keys.MOUSE_LEFT, ctx -> ctx.get("pickerConfirm"));
            g.frames(4);
            g.step("the instance now places other", ctx -> {
                var instance = instance(ctx);
                ctx.check("its source is other", fx("other").equals(instance.getFx()), fx("other"), instance.getFx());
                ctx.check("it places other's object", editor(ctx).runtime.objects.get(InstanceIds.xor(instance.id(), id("o_emit"))) != null,
                        "o_emit", null);
                ctx.check("and no longer base's", placedObject(ctx, B_GROUP) == null, "gone", "there");
            });
            focusHierarchy(g);
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("undo puts base back", ctx -> {
                ctx.check("its source is base", fx("base").equals(instance(ctx).getFx()), fx("base"), instance(ctx).getFx());
                ctx.check("base's objects are back", placedObject(ctx, B_GROUP) != null, "back", "gone");
            });
        });

        s.group("exporting into its own source is refused", g -> g.step("the project places base, so it may not become base", ctx -> {
            var project = (FXProject) editor(ctx).getCurrentProject();
            ctx.check("exporting as base is a cycle", com.lowdragmc.photon.gui.editor.FXFileActions.cycleThrough(project, fx("base")) != null,
                    "a cycle", "none");
            ctx.check("exporting as something else is not", com.lowdragmc.photon.gui.editor.FXFileActions.cycleThrough(project, fx("elsewhere")) == null,
                    "none", "a cycle");
        }));

        s.group("a cycle is refused", g -> {
            g.step("the project is known as base", ctx -> ((FXProject) editor(ctx).getCurrentProject()).addKnownAs(fx("base")));
            g.step("show the folder again", ctx -> browser(ctx).openDirectory(fixtureDir()));
            g.waitUntil("base.fx is listed", ctx -> entry(ctx, "base") != null);
            g.step("tag it", ctx -> {
                entry(ctx, "base").setId("entry-base-2");
                ctx.put("instancesBefore", editor(ctx).instances.instances().size());
            });
            dragPoints(g, "base.fx onto the hierarchy again", ctx -> centreOf(entry(ctx, "base")), FxInstanceEditorScenario::emptyHierarchySpot);
            g.frames(4);
            g.step("nothing was placed, and the author was told", ctx -> {
                ctx.check("no new instance", editor(ctx).instances.instances().size() == ctx.<Integer>get("instancesBefore"),
                        ctx.<Integer>get("instancesBefore"), editor(ctx).instances.instances().size());
                ctx.check("a dialog explains", !ctx.query().type(Dialog.class).list().isEmpty(), "a dialog", "none");
                closeDialogs(ctx);
            });
        });

        s.group("a missing source keeps its record", g -> {
            g.step("load a project whose instance's source is gone", ctx -> {
                ctx.put("unused", 0);
                var editor = editor(ctx);
                editor.closeCurrentProject(false, null);
                var project = new FXProject();
                var gone = new FXInstanceObject(fx("gone"));
                gone.setName("gone");
                var record = new InstanceRecord(List.of(new InstanceRecord.Modification(B_EMIT,
                        com.lowdragmc.photon.client.fx.instance.NbtPatch.Change.set(List.of("config", "duration"),
                                net.minecraft.nbt.IntTag.valueOf(3)))), List.of(), List.of());
                gone.setOverrides(record.write());
                var data = new FX();
                data.getFxData().objects().add(gone);
                var root = data.serializeNBT(Platform.getFrozenRegistry());
                project.getFx().deserializeNBT(Platform.getFrozenRegistry(), root);
                editor.loadProject(project, null);
                ctx.put("goneRecord", record);
            });
            g.frames(4);
            g.step("show the root's children", ctx -> editor(ctx).hierarchyView.treeList.expandNode(
                    java.util.Objects.requireNonNull(editor(ctx).hierarchyView.getRootNode())));
            g.frames(2);
            g.step("it is shown missing and saves its record back", ctx -> {
                var editor = editor(ctx);
                var gone = instanceNamed(ctx, "gone");
                ctx.require("the instance", gone != null);
                ctx.check("missing", editor.instances.role(gone) == FXInstanceEditing.Role.MISSING, FXInstanceEditing.Role.MISSING,
                        editor.instances.role(gone));
                var label = rowLabel(ctx, "gone");
                ctx.check("its row is red", label instanceof TextElement text
                        && text.getTextStyle().textColor() == FXInstanceStyle.MISSING_COLOR, FXInstanceStyle.MISSING_COLOR, label);
                var saved = editor.getCurrentProject().serializeNBT(Platform.getFrozenRegistry());
                var object = InstanceTags.objects(saved.getCompound("data").getCompound("fx").getCompound("fxData")).getFirst();
                ctx.check("its record is written back", ctx.<InstanceRecord>get("goneRecord")
                        .equals(InstanceRecord.read(InstanceTags.data(object).getCompound(InstanceTags.OVERRIDES))), "the same record", object);
            });
        });

        s.group("undo back to opening keeps the objects shown", g -> {
            g.step("open a plain project", ctx -> {
                var editor = editor(ctx);
                editor.closeCurrentProject(false, null);
                loadProject(ctx);
            });
            g.frames(2);
            g.step("make an edit through the history", ctx -> {
                var editor = editor(ctx);
                var extra = new EmptyFXObject();
                extra.setName("Extra");
                editor.historyView.pushHistory(net.minecraft.network.chat.Component.literal("add"),
                        com.lowdragmc.lowdraglib2.configurator.EditAction.of(() -> {
                            extra.transform().parent(editor.runtime.root.transform(), false);
                            editor.hierarchyView.addSceneObject(extra);
                        }, () -> editor.hierarchyView.removeSceneObject(extra)));
            });
            chord(g, GLFW.GLFW_KEY_Z);
            chord(g, GLFW.GLFW_KEY_Z);
            g.step("the hierarchy shows what the project saves", ctx -> {
                var editor = editor(ctx);
                var project = (FXProject) editor.getCurrentProject();
                var shown = editor.runtime.fxData;
                ctx.check("the runtime is on the project's objects", shown == project.getFx().getFxData(), "the project's", "stale");
                ctx.check("Plain is the object shown", editor.runtime.objects.values().stream().anyMatch(o -> o == plain(ctx)), "Plain", "none");
            });
        });

        s.group("a source restructured on disk keeps every object, in order", g -> {
            g.step("open a project placing struct, with an object added under its group and one removed", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("struct"), editor.runtime.root.transform());
                ctx.require("struct is placed", instance != null);
                ctx.put("struct", instance);
                var mine = named(new EmptyFXObject(), "Mine");
                mine.transform().parent(structPlaced(ctx, "s_group").transform(), false);
                editor.hierarchyView.addSceneObject(mine);
                editor.hierarchyView.removeSceneObject(structPlaced(ctx, "s_lone"));
                editor.instances.changed();
                ctx.put("mine", mine);
                ctx.put("kept", structPlaced(ctx, "s_kept"));
                ctx.put("top", structPlaced(ctx, "s_top"));
            });
            g.step("move s_kept under s_top, drop s_group and add s_new, as an export would", ctx -> {
                try {
                    writeStruct(true);
                } catch (IOException e) {
                    ctx.require("rewrote struct: " + e, false);
                }
                FXHelper.invalidate(List.of(fx("struct")));
            });
            g.ticks(3);
            g.step("the kept objects moved, what went is gone, what arrived is in its place", ctx -> {
                var instance = ctx.<FXInstanceObject>get("struct");
                var kept = ctx.<IFXObject>get("kept");
                var top = ctx.<IFXObject>get("top");
                ctx.check("s_kept is the same object", structPlaced(ctx, "s_kept") == kept, "same", "replaced");
                ctx.check("now under s_top", kept.transform().parent() == top.transform(), "s_top", kept.transform().parent());
                ctx.check("s_group is gone", structPlaced(ctx, "s_group") == null, "gone", "there");
                var arrived = structPlaced(ctx, "s_new");
                ctx.check("s_new arrived first under s_top, as the source orders them", arrived != null
                        && top.transform().children().indexOf(arrived.transform()) == 0
                        && top.transform().children().indexOf(kept.transform()) == 1, "s_new, s_kept", childNames(top));
                var mine = ctx.<IFXObject>get("mine");
                ctx.check("Mine, whose place went away, waits under the instance", mine.transform().parent() == instance.transform(),
                        "the instance", mine.transform().parent());
                ctx.check("and keeps where it was hung", editor(ctx).instances.recordOf(instance).added()
                        .contains(new InstanceRecord.Added(id("s_group"), mine.id())), "anchored to s_group", editor(ctx).instances.recordOf(instance));
                ctx.check("every transform lists the children it has", misaligned(instance).isEmpty(), List.of(), misaligned(instance));
                ctx.check("no object is in the effect twice", distinctObjects(ctx), "distinct", editor(ctx).runtime.fxData.objects().size());
            });
            g.step("undo the refresh", ctx -> editor(ctx).historyView.undo());
            g.step("everything is back where it was", ctx -> {
                var instance = ctx.<FXInstanceObject>get("struct");
                var group = structPlaced(ctx, "s_group");
                ctx.check("s_group is back", group != null, "back", "gone");
                ctx.check("with s_kept under it", group != null && ctx.<IFXObject>get("kept").transform().parent() == group.transform(),
                        "s_group", ctx.<IFXObject>get("kept").transform().parent());
                ctx.check("and Mine", group != null && ctx.<IFXObject>get("mine").transform().parent() == group.transform(),
                        "s_group", ctx.<IFXObject>get("mine").transform().parent());
                ctx.check("s_new is gone", structPlaced(ctx, "s_new") == null, "gone", "there");
                ctx.check("every transform lists the children it has", misaligned(instance).isEmpty(), List.of(), misaligned(instance));
            });
            g.step("redo it", ctx -> editor(ctx).historyView.redo());
            g.step("the restructured layout again", ctx -> {
                var kept = ctx.<IFXObject>get("kept");
                ctx.check("s_kept under s_top", kept.transform().parent() == ctx.<IFXObject>get("top").transform(), "s_top",
                        kept.transform().parent());
                ctx.check("every transform lists the children it has", misaligned(ctx.get("struct")).isEmpty(), List.of(),
                        misaligned(ctx.get("struct")));
            });
        });

        s.group("a source only baked again is no step in the history", g -> {
            g.step("make an edit and undo it, so there is something to redo", ctx -> {
                var editor = editor(ctx);
                var extra = named(new EmptyFXObject(), "Extra3");
                editor.historyView.pushHistory(Component.literal("add"), EditAction.of(() -> {
                    extra.transform().parent(editor.runtime.root.transform(), false);
                    editor.hierarchyView.addSceneObject(extra);
                }, () -> editor.hierarchyView.removeSceneObject(extra)));
                editor.historyView.undo();
            });
            g.step("forget every loaded effect", ctx -> {
                ctx.put("undoBefore", stackSize(ctx, "undoStack"));
                ctx.put("redoBefore", stackSize(ctx, "redoStack"));
                FXHelper.clearCache();
            });
            g.ticks(3);
            g.step("nothing was recorded, nothing replaced", ctx -> {
                ctx.check("no new step", stackSize(ctx, "undoStack") == ctx.<Integer>get("undoBefore"), ctx.<Integer>get("undoBefore"),
                        stackSize(ctx, "undoStack"));
                ctx.check("redo is still there", stackSize(ctx, "redoStack") == ctx.<Integer>get("redoBefore"), ctx.<Integer>get("redoBefore"),
                        stackSize(ctx, "redoStack"));
                ctx.check("the same objects", structPlaced(ctx, "s_kept") == ctx.<IFXObject>get("kept"), "same", "replaced");
            });
        });

        s.group("an object anchored in a missing source goes back when the source appears", g -> {
            g.step("open a project whose instance's source is not written yet", ctx -> {
                var editor = editor(ctx);
                editor.closeCurrentProject(false, null);
                var waiting = named(new FXInstanceObject(fx("later")), "waiting");
                var waiter = named(new EmptyFXObject(), "Waiter");
                waiter.transform()._setInternalParentID(InstanceIds.xor(waiting.id(), id("l_group")));
                waiting.setOverrides(new InstanceRecord(List.of(), List.of(),
                        List.of(new InstanceRecord.Added(id("l_group"), waiter.id()))).write());
                var data = new FX();
                data.getFxData().objects().addAll(List.of(waiting, waiter));
                var project = new FXProject();
                project.getFx().deserializeNBT(Platform.getFrozenRegistry(), data.serializeNBT(Platform.getFrozenRegistry()));
                editor.loadProject(project, null);
                ctx.put("waiting", instanceNamed(ctx, "waiting"));
                ctx.put("waiter", editor.runtime.objects.get(waiter.id()));
            });
            g.step("it waits at the root", ctx -> {
                var editor = editor(ctx);
                ctx.check("missing", editor.instances.role(ctx.get("waiting")) == FXInstanceEditing.Role.MISSING,
                        FXInstanceEditing.Role.MISSING, editor.instances.role(ctx.get("waiting")));
                ctx.check("Waiter hangs off the root", ctx.<IFXObject>get("waiter").transform().parent() == editor.runtime.root.transform(),
                        "the root", ctx.<IFXObject>get("waiter").transform().parent());
            });
            g.step("write the source", ctx -> {
                try {
                    write("later", List.of(named(new EmptyFXObject(), "l_group")));
                } catch (IOException e) {
                    ctx.require("wrote later: " + e, false);
                }
                FXHelper.invalidate(List.of(fx("later")));
            });
            g.ticks(3);
            g.step("Waiter is back under its anchor", ctx -> {
                var waiting = ctx.<FXInstanceObject>get("waiting");
                var group = editor(ctx).runtime.objects.get(InstanceIds.xor(waiting.id(), id("l_group")));
                ctx.check("the instance places its source", editor(ctx).instances.role(waiting) == FXInstanceEditing.Role.INSTANCE,
                        FXInstanceEditing.Role.INSTANCE, editor(ctx).instances.role(waiting));
                ctx.check("under l_group", group != null && ctx.<IFXObject>get("waiter").transform().parent() == group.transform(),
                        "l_group", ctx.<IFXObject>get("waiter").transform().parent());
                ctx.check("still the same Waiter", editor(ctx).runtime.objects.get(ctx.<IFXObject>get("waiter").id()) == ctx.get("waiter"),
                        "same", "replaced");
            });
            g.step("undo", ctx -> editor(ctx).historyView.undo());
            g.step("it waits at the root again", ctx -> {
                ctx.check("Waiter hangs off the root", ctx.<IFXObject>get("waiter").transform().parent() == editor(ctx).runtime.root.transform(),
                        "the root", ctx.<IFXObject>get("waiter").transform().parent());
                ctx.check("and is not removed", editor(ctx).runtime.objects.get(ctx.<IFXObject>get("waiter").id()) == ctx.get("waiter"),
                        "there", "removed");
            });
        });

        s.group("an undo back to opening keeps what the project was exported as", g -> {
            g.step("open, export, then edit", ctx -> {
                var editor = openPlain(ctx);
                ((FXProject) editor.getCurrentProject()).addKnownAs(fx("exported_as"));
                var extra = named(new EmptyFXObject(), "Extra2");
                editor.historyView.pushHistory(Component.literal("add"), EditAction.of(() -> {
                    extra.transform().parent(editor.runtime.root.transform(), false);
                    editor.hierarchyView.addSceneObject(extra);
                }, () -> editor.hierarchyView.removeSceneObject(extra)));
                ctx.put("plainBefore", plain(ctx));
            });
            g.step("undo back to opening", ctx -> {
                editor(ctx).historyView.undo();
                editor(ctx).historyView.undo();
            });
            g.step("it is still known by its export, and shows the same objects", ctx -> {
                var project = (FXProject) editor(ctx).getCurrentProject();
                ctx.check("known as exported_as", project.getKnownAs().contains(fx("exported_as")), fx("exported_as"), project.getKnownAs());
                ctx.check("the same Plain", plain(ctx) == ctx.get("plainBefore"), "same", "replaced");
            });
        });

        s.group("an instance placed inside another is an added object", g -> {
            g.step("place base, then other under base's group", ctx -> {
                var editor = openPlain(ctx);
                var outer = editor.instances.create(fx("base"), editor.runtime.root.transform());
                ctx.require("base is placed", outer != null);
                var group = editor.runtime.objects.get(InstanceIds.xor(outer.id(), B_GROUP));
                var inner = editor.instances.create(fx("other"), group.transform());
                ctx.require("other is placed", inner != null);
                ctx.put("outer", outer);
                ctx.put("inner", inner);
            });
            g.step("it is base's added object, and an instance of its own", ctx -> {
                var instances = editor(ctx).instances;
                FXInstanceObject outer = ctx.get("outer");
                FXInstanceObject inner = ctx.get("inner");
                ctx.check("an added top", instances.isAddedTop(inner), "added", "not");
                ctx.check("listed as base's", instances.addedTops(outer).contains(inner), "listed", instances.addedTops(outer));
                ctx.check("still an instance", instances.role(inner) == FXInstanceEditing.Role.INSTANCE, FXInstanceEditing.Role.INSTANCE,
                        instances.role(inner));
                ctx.check("base records where it hangs", instances.recordOf(outer).added().contains(new InstanceRecord.Added(B_GROUP, inner.id())),
                        "under b_group", instances.recordOf(outer));
            });
            g.step("Revert All on base", ctx -> editor(ctx).instances.revertAll(ctx.get("outer")));
            g.step("takes it out", ctx -> ctx.check("other is gone", !editor(ctx).runtime.objects.containsValue(ctx.get("inner")),
                    "gone", "there"));
            g.step("undo", ctx -> editor(ctx).historyView.undo());
            g.step("brings it back under the group", ctx -> {
                FXInstanceObject outer = ctx.get("outer");
                FXInstanceObject inner = ctx.get("inner");
                var group = editor(ctx).runtime.objects.get(InstanceIds.xor(outer.id(), B_GROUP));
                ctx.check("under b_group", inner.transform().parent() == group.transform(), "b_group", inner.transform().parent());
            });
        });

        s.group("reverting a transform row keeps what hangs under the object", g -> {
            g.step("move a placed emitter and hang an object under it", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("base"), editor.runtime.root.transform());
                ctx.require("base is placed", instance != null);
                ctx.put("instance", instance);
                var emit = placedObject(ctx, B_EMIT);
                emit.transform().localPosition(new org.joml.Vector3f(1, 2, 3));
                var hanger = named(new EmptyFXObject(), "Hanger");
                hanger.transform().parent(emit.transform(), false);
                editor.hierarchyView.addSceneObject(hanger);
                editor.instances.changed();
                ctx.put("hanger", hanger);
            });
            g.step("revert the transform row", ctx -> editor(ctx).instances.revert(placedObject(ctx, B_EMIT), List.of("transform")));
            g.step("the value is the source's, the structure the effect's", ctx -> {
                var emit = placedObject(ctx, B_EMIT);
                var position = emit.transform().localPosition();
                ctx.check("back at the source's position", position.lengthSquared() < 1e-6, "(0, 0, 0)", position);
                ctx.check("Hanger still hangs under it", ctx.<IFXObject>get("hanger").transform().parent() == emit.transform(),
                        "b_emit", ctx.<IFXObject>get("hanger").transform().parent());
                ctx.check("every transform lists the children it has", misaligned(instance(ctx)).isEmpty(), List.of(),
                        misaligned(instance(ctx)));
                ctx.<IFXObject>get("hanger").transform().setSiblingIndex(0);
                ctx.check("and still does after a reorder", misaligned(instance(ctx)).isEmpty(), List.of(), misaligned(instance(ctx)));
            });
        });

        s.group("a list element's rows are marked", g -> {
            g.step("give a placed emitter another texture", ctx -> {
                var emit = (ParticleEmitter) placedObject(ctx, B_EMIT);
                emit.config.renderer.getMaterials().set(0,
                        new MaterialSetting(new TextureMaterial(Photon.id("textures/particle/smoke.png"))));
                editor(ctx).instances.changed();
                expand(ctx, instance(ctx));
            });
            g.frames(2);
            clickRow(g, "b_emit");
            g.frames(4);
            g.step("its material element is marked, the list's other rows are not", ctx -> {
                var marked = new ArrayList<String>();
                var unmarked = 0;
                for (var ref : ctx.query().type(ArrayConfiguratorGroup.class).list()) {
                    for (var row : ref.as(ArrayConfiguratorGroup.class).getConfigurators()) {
                        if (!(row instanceof ArrayConfiguratorGroup<?>.ItemConfigurator item)) continue;
                        if (item.isOverridden()) {
                            marked.add(String.valueOf(item.object));
                        } else {
                            unmarked++;
                        }
                    }
                }
                ctx.check("one element is marked", marked.size() == 1, 1, marked);
                ctx.log("unmarked elements: " + unmarked);
            });
        });

        s.group("a placed spot's cone rows are marked, though they hang under its type", g -> {
            g.step("widen a placed spot light", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("spot"), editor.runtime.root.transform());
                ctx.require("spot is placed", instance != null);
                ctx.put("instance", instance);
                ((LightObject) editor.runtime.objects.get(InstanceIds.xor(instance.id(), id("lamp")))).config.setOuterAngle(50);
                editor.instances.changed();
                expand(ctx, instance);
            });
            g.frames(2);
            clickRow(g, "lamp");
            g.frames(4);
            g.step("Outer Angle is marked, Inner Angle is not", ctx -> {
                Configurator outer = null, inner = null;
                for (var ref : ctx.query().type(Configurator.class).list()) {
                    var row = ref.as(Configurator.class);
                    var label = row.label.getText().getString();
                    if (label.equals(I18n.get("LightConfig.outerAngle"))) outer = row;
                    if (label.equals(I18n.get("LightConfig.innerAngle"))) inner = row;
                }
                ctx.require("both cone rows are shown", outer != null && inner != null);
                ctx.check("the changed one is marked", outer.isOverridden(), "marked", "unmarked");
                ctx.check("the other is not", !inner.isOverridden(), "unmarked", "marked");
            });
        });

        s.group("one row of the overrides list reverts all it stands for, as one step", g -> g.step(
                "change two values and revert both", ctx -> {
                    var editor = openPlain(ctx);
                    var instance = editor.instances.create(fx("base"), editor.runtime.root.transform());
                    ctx.require("base is placed", instance != null);
                    ctx.put("instance", instance);
                    var emit = (ParticleEmitter) placedObject(ctx, B_EMIT);
                    int duration = emit.config.getDuration();
                    boolean looping = emit.config.isLooping();
                    emit.config.setDuration(duration + 37);
                    emit.config.setLooping(!looping);
                    editor.instances.changed();
                    int steps = stackSize(ctx, "undoStack");
                    editor.instances.revert(emit, List.of(List.of("config", "duration"), List.of("config", "looping")));
                    ctx.check("duration is the source's", emit.config.getDuration() == duration, duration, emit.config.getDuration());
                    ctx.check("looping is the source's", emit.config.isLooping() == looping, looping, emit.config.isLooping());
                    ctx.check("one history step", stackSize(ctx, "undoStack") == steps + 1, steps + 1, stackSize(ctx, "undoStack"));
                    ctx.check("nothing is overridden any more", !editor.instances.isOverridden(emit), "pristine", "overridden");
                }));

        s.group("copying an instance leaves what a timeline drives out of its record", g -> g.step(
                "copy an instance whose placed emitter an outer track moves", ctx -> {
                    var editor = openPlain(ctx);
                    var instance = editor.instances.create(fx("base"), editor.runtime.root.transform());
                    ctx.require("base is placed", instance != null);
                    var emit = (ParticleEmitter) editor.runtime.objects.get(InstanceIds.xor(instance.id(), B_EMIT));
                    var track = (AnimationTrack) PhotonTrackTypes.ANIMATION.create().targetId(emit.id());
                    var position = PositionPropertyType.INSTANCE.create(emit);
                    position.putKey(0, 0, 0);
                    position.putKey(0, 20, 10);
                    track.properties().add(position);
                    editor.runtime.fxData.timeline().tracks().add(track);
                    emit.transform().localPosition(new org.joml.Vector3f(4, 0, 0));
                    var clipboard = new FXObjectClipboard(List.of(instance), editor.runtime.fxData.timeline());
                    List<CompoundTag> objects = ctx.getField(clipboard, "objects");
                    var saved = objects.stream().filter(tag -> instance.id().equals(InstanceTags.id(tag))).findFirst().orElse(null);
                    ctx.require("the copied instance", saved != null);
                    var record = InstanceRecord.read(InstanceTags.data(saved).getCompound(InstanceTags.OVERRIDES));
                    ctx.check("the position the track gives it now is not an override",
                            record.modified().stream().noneMatch(m -> m.change().path().equals(InstanceDiffRules.POSITION)), "none", record);
                }));

        s.group("pasting an instance that would place the effect inside itself is told", g -> {
            g.step("open a project placing base", ctx -> {
                var editor = openPlain(ctx);
                ctx.put("instance", editor.instances.create(fx("base"), editor.runtime.root.transform()));
            });
            g.frames(2);
            clickRow(g, "base");
            chord(g, GLFW.GLFW_KEY_C);
            g.step("the project becomes base", ctx -> ((FXProject) editor(ctx).getCurrentProject()).addKnownAs(fx("base")));
            chord(g, GLFW.GLFW_KEY_V);
            g.frames(4);
            g.step("the paste places nothing, and the author is told why", ctx -> {
                var pasted = editor(ctx).runtime.objects.values().stream()
                        .filter(o -> o instanceof FXInstanceObject && o != instance(ctx)).findFirst().orElse(null);
                ctx.require("the paste", pasted != null);
                ctx.check("it is missing", editor(ctx).instances.role(pasted) == FXInstanceEditing.Role.MISSING,
                        FXInstanceEditing.Role.MISSING, editor(ctx).instances.role(pasted));
                ctx.check("a dialog explains", !ctx.query().type(Dialog.class).list().isEmpty(), "a dialog", "none");
                closeDialogs(ctx);
            });
        });

        s.group("a kept object the source wraps in a new group stays one object", g -> {
            g.step("open a project placing wrap", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("wrap"), editor.runtime.root.transform());
                ctx.require("wrap is placed", instance != null);
                ctx.put("wrap", instance);
                ctx.put("wrapKept", wrapPlaced(ctx, "w_kept"));
            });
            g.step("wrap w_kept in a new w_box, as an export would", ctx -> {
                try {
                    writeWrap("wrap", true);
                } catch (IOException e) {
                    ctx.require("rewrote wrap: " + e, false);
                }
                FXHelper.invalidate(List.of(fx("wrap")));
            });
            g.ticks(3);
            g.step("w_kept is in w_box, once", ctx -> checkWrapped(ctx, true));
            g.step("undo", ctx -> editor(ctx).historyView.undo());
            g.step("w_kept is back at the top, once", ctx -> checkWrapped(ctx, false));
            g.step("redo", ctx -> editor(ctx).historyView.redo());
            g.step("in w_box again, once", ctx -> checkWrapped(ctx, true));
            g.step("undo again", ctx -> editor(ctx).historyView.undo());
            g.step("at the top again, once", ctx -> checkWrapped(ctx, false));
        });

        s.group("replacing with a source that wraps a kept object keeps it one object", g -> {
            g.step("open a project placing wrap_flat and replace it with wrap_boxed", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("wrap_flat"), editor.runtime.root.transform());
                ctx.require("wrap_flat is placed", instance != null);
                ctx.put("wrap", instance);
                ctx.put("wrapKept", wrapPlaced(ctx, "w_kept"));
                editor.instances.replace(instance, fx("wrap_boxed"));
            });
            g.step("w_kept is in w_box, once", ctx -> checkWrapped(ctx, true));
            g.step("undo", ctx -> editor(ctx).historyView.undo());
            g.step("w_kept is back at the top, once", ctx -> checkWrapped(ctx, false));
        });

        s.group("an object of a type this game lacks does not take what it holds with it", g -> {
            g.step("place ghosted, whose ghost holds g_child", ctx -> {
                var editor = openPlain(ctx);
                var instance = editor.instances.create(fx("ghosted"), editor.runtime.root.transform());
                ctx.require("ghosted is placed", instance != null);
                ctx.put("ghosted", instance);
            });
            g.step("g_child waits under the instance and nothing is taken as removed", ctx -> {
                FXInstanceObject instance = ctx.get("ghosted");
                var child = editor(ctx).runtime.objects.get(InstanceIds.xor(instance.id(), id("g_child")));
                ctx.check("g_child is in the effect, under the instance", child != null && child.transform().parent() == instance.transform(),
                        "under the instance", child == null ? "missing" : child.transform().parent());
                var record = editor(ctx).instances.recordOf(instance);
                ctx.check("nothing is removed", record.removed().isEmpty(), List.of(), record.removed());
            });
            g.step("duplicating it", ctx -> {
                FXInstanceObject instance = ctx.get("ghosted");
                var copy = new FXObjectClipboard(List.of(instance), editor(ctx).runtime.fxData.timeline())
                        .instantiate(original -> editor(ctx).runtime.root.id(), editor(ctx).instances.knownAs());
                var copied = copy.objects().stream().filter(o -> o instanceof FXInstanceObject).findFirst().orElse(null);
                ctx.require("the copy is an instance", copied != null);
                var child = copy.objects().stream().filter(o -> o.id().equals(InstanceIds.xor(copied.id(), id("g_child")))).findFirst().orElse(null);
                ctx.check("places g_child as well", child != null && copied.id().equals(child.transform()._getInternalParentID()),
                        "under the copy", child == null ? "missing" : child.transform()._getInternalParentID());
            });
        });

        s.group("an undo back to opening that reads the project again keeps what it was exported as", g -> {
            g.step("open, change Plain outside the history, export, then edit", ctx -> {
                var editor = openPlain(ctx);
                plain(ctx).setName("Changed");
                ((FXProject) editor.getCurrentProject()).addKnownAs(fx("exported_later"));
                var extra = named(new EmptyFXObject(), "Extra4");
                editor.historyView.pushHistory(Component.literal("add"), EditAction.of(() -> {
                    extra.transform().parent(editor.runtime.root.transform(), false);
                    editor.hierarchyView.addSceneObject(extra);
                }, () -> editor.hierarchyView.removeSceneObject(extra)));
            });
            g.step("undo back to opening", ctx -> {
                editor(ctx).historyView.undo();
                editor(ctx).historyView.undo();
            });
            g.step("the project was read again, and is still known by its export", ctx -> {
                var project = (FXProject) editor(ctx).getCurrentProject();
                ctx.check("read again: Plain has its name from opening", editor(ctx).runtime.objects.values().stream()
                        .anyMatch(o -> "Plain".equals(o.getName())), "Plain", "Changed");
                ctx.check("known as exported_later", project.getKnownAs().contains(fx("exported_later")), fx("exported_later"),
                        project.getKnownAs());
            });
        });

        s.group("what may be done to an instance whose source is missing", g -> g.step("open source and replace only", ctx -> {
            var editor = openPlain(ctx);
            var missing = named(new FXInstanceObject(fx("not_written")), "not_written");
            var data = new FX();
            data.getFxData().objects().add(missing);
            var project = new FXProject();
            project.getFx().deserializeNBT(Platform.getFrozenRegistry(), data.serializeNBT(Platform.getFrozenRegistry()));
            editor.closeCurrentProject(false, null);
            editor.loadProject(project, null);
            var instance = instanceNamed(ctx, "not_written");
            ctx.require("the instance", instance != null);
            var menu = TreeBuilder.Menu.start();
            FXInstanceInspector.instanceActions(editor, instance, menu, java.util.function.UnaryOperator.identity());
            var enabled = new java.util.LinkedHashMap<String, Boolean>();
            for (var leaf : menu.build().getChildren()) {
                var name = leaf.getKey().getB();
                var key = name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
                        ? translatable.getKey() : name.getString();
                enabled.put(key, leaf.getContent() != null && leaf.getContent() != TreeBuilder.Menu.DISABLED);
            }
            ctx.check("open source and replace are offered, overrides and revert all are not", enabled.equals(Map.of(
                    "photon.fx_instance.open_source", true, "photon.fx_instance.overrides", false,
                    "photon.fx_instance.revert_all", false, "photon.fx_instance.replace", true)), "two of four", enabled);
        }));

        s.group("an .fx is placed by the id its path gives", g -> g.step("whatever folder holds the assets", ctx -> {
            var root = new File("pack-root").getAbsoluteFile();
            var cases = new java.util.LinkedHashMap<String, String>();
            cases.put("resourcepacks/pack/assets/ns/fx/a/b.fx", "ns:a/b");
            cases.put("assets/ns/fx/fx/c.fx", "ns:fx/c");
            // vanilla rejects an uppercase path; a mod may allow it, and then the effect does load by it
            var upper = ResourceLocation.tryBuild("ns", "MyFx");
            cases.put("assets/ns/fx/MyFx.fx", upper == null ? null : upper.toString());
            cases.put("assets/ns/textures/d.fx", null);
            cases.forEach((path, expected) -> {
                var id = FXInstanceEditing.idOf(new File(root, path));
                ctx.check(path, java.util.Objects.equals(expected, id == null ? null : id.toString()), expected, id);
            });
            ctx.check("a name no id can have is still under an fx folder, so it is told it is no id",
                    FXInstanceEditing.isUnderFxFolder(new File(root, "assets/ns/fx/My Fx.fx"))
                            && FXInstanceEditing.idOf(new File(root, "assets/ns/fx/My Fx.fx")) == null, true, false);
            ctx.check("a texture folder is not", !FXInstanceEditing.isUnderFxFolder(new File(root, "assets/ns/textures/d.fx")), false, true);
        }));

        s.teardown("close the project and delete the fixtures", ctx -> {
            ClipboardManager.INSTANCE.clear();
            closeDialogs(ctx);
            FXEditor editor = ctx.query().type(FXEditor.class).list().isEmpty() ? null : editor(ctx);
            if (editor != null) editor.closeCurrentProject(false, null);
            deleteTree(new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + ROOT));
            FXHelper.clearCache();
        });
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("fx_instance_editor/" + name).getBytes());
    }

    private static ResourceLocation fx(String name) {
        return Photon.id(dir + "/" + name);
    }

    private static File fixtureDir() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx/" + dir);
    }

    private static <T extends IFXObject> T named(T object, String name) {
        object.setName(name);
        object.transform()._setInternalID(id(name));
        return object;
    }

    private static ParticleEmitter emitter(String name, int duration) {
        var emitter = named(new ParticleEmitter(), name);
        emitter.config.setDuration(duration);
        emitter.config.setStartLifetime(NumberFunction.constant(20));
        emitter.config.setMaxParticles(8);
        return emitter;
    }

    private static void writeFixtures(TestContext ctx) {
        dir = ROOT + "/" + Long.toString(System.nanoTime() & 0xffffffL, 36);
        FXHelper.clearCache();
        try {
            writeBase(40, 40);
            writeStruct(false);
            writeWrap("wrap", false);
            writeWrap("wrap_flat", false);
            writeWrap("wrap_boxed", true);
            writeGhosted();
            var otherEmit = emitter("o_emit", 40);
            var activator = PhotonTrackTypes.ACTIVATOR.create().targetId(otherEmit.id());
            activator.clips().add(new Clip(0, 60, 1));
            write("other", List.of(otherEmit), List.of(activator));
            var lamp = named(new LightObject(), "lamp");
            lamp.config.setType(LightConfig.Type.Spot);
            write("spot", List.of(lamp));
        } catch (IOException e) {
            ctx.require("the fixtures were written: " + e, false);
        }
    }

    /** struct: s_group ⊃ s_kept, s_lone, then s_top. Restructured: s_top ⊃ s_new, s_kept; no s_group, no s_lone. */
    private static void writeStruct(boolean restructured) throws IOException {
        var kept = emitter("s_kept", 40);
        var top = named(new EmptyFXObject(), "s_top");
        if (!restructured) {
            var group = named(new EmptyFXObject(), "s_group");
            var lone = emitter("s_lone", 40);
            kept.transform()._setInternalParentID(group.id());
            lone.transform()._setInternalParentID(group.id());
            write("struct", List.of(group, kept, lone, top));
            return;
        }
        var arrived = emitter("s_new", 40);
        arrived.transform()._setInternalParentID(top.id());
        kept.transform()._setInternalParentID(top.id());
        write("struct", List.of(top, arrived, kept));
    }

    /** w_kept and w_side at the top; boxed, w_kept hangs in a new w_box instead. */
    private static void writeWrap(String name, boolean boxed) throws IOException {
        var kept = emitter("w_kept", 40);
        var side = emitter("w_side", 40);
        if (!boxed) {
            write(name, List.of(kept, side));
            return;
        }
        var box = named(new EmptyFXObject(), "w_box");
        kept.transform()._setInternalParentID(box.id());
        write(name, List.of(box, kept, side));
    }

    /** ghost (a type this game does not have) ⊃ g_child, and g_top. */
    private static void writeGhosted() throws IOException {
        var child = emitter("g_child", 40);
        child.transform()._setInternalParentID(id("ghost"));
        var fx = new FX();
        fx.getFxData().objects().addAll(List.of(child, emitter("g_top", 40)));
        var tag = fx.serializeNBT(Platform.getFrozenRegistry());
        var transform = new CompoundTag();
        transform.putString(InstanceTags.ID, id("ghost").toString());
        var children = new net.minecraft.nbt.ListTag();
        children.add(net.minecraft.nbt.StringTag.valueOf(id("g_child").toString()));
        transform.put(InstanceTags.CHILDREN, children);
        var data = new CompoundTag();
        data.putString(InstanceTags.NAME, "ghost");
        data.put(InstanceTags.TRANSFORM, transform);
        var ghost = new CompoundTag();
        ghost.putString(InstanceTags.TYPE, "uitest_no_such_object");
        ghost.put(InstanceTags.DATA, data);
        tag.getCompound("fxData").getList(InstanceTags.OBJECTS, CompoundTag.TAG_COMPOUND).addTag(0, ghost);
        tag.putInt("version", FXProject.VERSION);
        var file = new File(fixtureDir(), "ghosted" + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    private static void writeBase(int emitDuration, int lateDuration) throws IOException {
        var group = named(new EmptyFXObject(), "b_group");
        var child = emitter("b_child", 40);
        child.transform()._setInternalParentID(group.id());
        write("base", List.of(emitter("b_emit", emitDuration), emitter("b_late", lateDuration), group, child));
    }

    private static void write(String name, List<? extends IFXObject> objects) throws IOException {
        write(name, objects, List.of());
    }

    private static void write(String name, List<? extends IFXObject> objects, List<Track> tracks) throws IOException {
        var fx = new FX();
        fx.getFxData().objects().addAll(objects);
        fx.getFxData().timeline().tracks().addAll(tracks);
        var tag = fx.serializeNBT(Platform.getFrozenRegistry());
        tag.putInt("version", FXProject.VERSION);
        var file = new File(fixtureDir(), name + FX.SUFFIX);
        Files.createDirectories(file.getParentFile().toPath());
        NbtIo.writeCompressed(tag, file.toPath());
    }

    private static void loadProject(TestContext ctx) {
        var project = new FXProject();
        project.getFx().getFxData().objects().add(named(new EmptyFXObject(), "Plain"));
        var editor = editor(ctx);
        editor.loadProject(project, null);
        ctx.put("editor", editor);
        editor.hierarchyView.treeList.expandNode(java.util.Objects.requireNonNull(editor.hierarchyView.getRootNode()));
    }

    // ---- lookups -------------------------------------------------------------------------------------

    private static FXEditor openPlain(TestContext ctx) {
        var editor = editor(ctx);
        editor.closeCurrentProject(false, null);
        loadProject(ctx);
        return editor;
    }

    @Nullable
    private static IFXObject wrapPlaced(TestContext ctx, String name) {
        return editor(ctx).runtime.objects.get(InstanceIds.xor(ctx.<FXInstanceObject>get("wrap").id(), id(name)));
    }

    /** w_kept is the live object, in w_box or at the top, nowhere a second time, and every child list says the truth. */
    private static void checkWrapped(TestContext ctx, boolean boxed) {
        FXInstanceObject instance = ctx.get("wrap");
        IFXObject kept = ctx.get("wrapKept");
        var editor = editor(ctx);
        ctx.check("the runtime knows w_kept as the same object", editor.runtime.objects.get(kept.id()) == kept, "the same", "a copy");
        var inTree = new ArrayList<IFXObject>();
        instance.executeAll(scene -> {
            if (scene instanceof IFXObject object && object.id().equals(kept.id())) inTree.add(object);
        });
        ctx.check("once in the hierarchy", inTree.size() == 1 && inTree.getFirst() == kept, 1, inTree.size());
        ctx.check("once in the effect", distinctObjects(ctx), "distinct", editor.runtime.fxData.objects().size());
        var box = wrapPlaced(ctx, "w_box");
        var expected = boxed ? box : instance;
        ctx.check(boxed ? "in w_box" : "at the top", expected != null && kept.transform().parent() == expected.transform(),
                boxed ? "w_box" : "the instance", kept.transform().parent());
        if (!boxed) ctx.check("w_box is gone", box == null, "gone", "there");
        ctx.check("every transform lists the children it has", misaligned(instance).isEmpty(), List.of(), misaligned(instance));
        var side = wrapPlaced(ctx, "w_side");
        if (side != null) side.transform().setSiblingIndex(0);
        ctx.check("and still does after a reorder", misaligned(instance).isEmpty(), List.of(), misaligned(instance));
    }

    @Nullable
    private static IFXObject structPlaced(TestContext ctx, String name) {
        return editor(ctx).runtime.objects.get(InstanceIds.xor(ctx.<FXInstanceObject>get("struct").id(), id(name)));
    }

    private static List<String> childNames(IFXObject object) {
        return object.transform().children().stream().map(child -> ((IFXObject) child.sceneObject()).getName()).toList();
    }

    /** The objects under {@code root} whose saved child list is not the children they have, in order. */
    private static List<String> misaligned(IFXObject root) {
        var out = new ArrayList<String>();
        root.executeAll(scene -> {
            var transform = scene.transform();
            var ids = transform.children().stream().map(child -> child.id()).toList();
            if (!ids.equals(transform._getInternalChildID())) out.add(((IFXObject) scene).getName());
        });
        return out;
    }

    private static boolean distinctObjects(TestContext ctx) {
        var objects = editor(ctx).runtime.fxData.objects();
        return objects.stream().map(IFXObject::id).distinct().count() == objects.size();
    }

    private static int stackSize(TestContext ctx, String stack) {
        return ctx.<java.util.Stack<?>>getField(editor(ctx).historyView, stack).size();
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static com.lowdragmc.lowdraglib2.editor.ui.browser.AssetBrowser browser(TestContext ctx) {
        return editor(ctx).resourceView.getAssetBrowser();
    }

    @Nullable
    private static UIElement entry(TestContext ctx, String name) {
        Map<File, UIElement> entries = ctx.getField(browser(ctx), "entryUIs");
        return entries.get(new File(fixtureDir(), name + FX.SUFFIX));
    }

    @Nullable
    private static FXInstanceObject instanceNamed(TestContext ctx, String name) {
        var runtime = editor(ctx).runtime;
        if (runtime == null) return null;
        return runtime.objects.values().stream().filter(o -> o instanceof FXInstanceObject && name.equals(o.getName()))
                .map(o -> (FXInstanceObject) o).findFirst().orElse(null);
    }

    private static FXInstanceObject instance(TestContext ctx) {
        return ctx.get("instance");
    }

    @Nullable
    private static IFXObject placedObject(TestContext ctx, UUID source) {
        return editor(ctx).runtime.objects.get(InstanceIds.xor(instance(ctx).id(), source));
    }

    private static ParticleEmitter emitter(TestContext ctx) {
        return (ParticleEmitter) placedObject(ctx, B_EMIT);
    }

    private static IFXObject plain(TestContext ctx) {
        return editor(ctx).runtime.objects.values().stream().filter(o -> "Plain".equals(o.getName())).findFirst().orElseThrow();
    }

    private static Configurator durationRow(TestContext ctx) {
        return ctx.query().type(Configurator.class).where(c -> c instanceof Configurator row && row.getSourceField() != null
                        && row.getSourceField().getName().equals("duration")
                        && isInside(row, editor(ctx).inspectorView))
                .list().getFirst().as(Configurator.class);
    }

    private static UIElement durationField(TestContext ctx) {
        var row = durationRow(ctx);
        return ctx.query().type(TextField.class).where(field -> isInside(field, row)).list().getFirst().element();
    }

    private static void expand(TestContext ctx, IFXObject object) {
        var tree = editor(ctx).hierarchyView.treeList;
        var node = nodeOf(ctx, object);
        if (node != null) tree.expandNodeAlongPath(node);
        if (node != null) tree.expandNode(node);
        for (var child : object.transform().children()) {
            var childNode = nodeOf(ctx, (IFXObject) child.sceneObject());
            if (childNode != null) tree.expandNode(childNode);
        }
    }

    @Nullable
    private static FXObjectTreeNode nodeOf(TestContext ctx, IFXObject object) {
        var node = editor(ctx).hierarchyView.getRootNode();
        while (node != null && node.getKey() != object) {
            node = node.getChildren().stream().filter(child -> child.getKey() == object
                    || object.transform().isInheritedParent(child.getKey().transform())).findFirst().orElse(null);
        }
        return node;
    }

    private static String describeHistory(TestContext ctx) {
        try {
            java.util.Stack<?> history = ctx.getField(editor(ctx).historyView, "undoStack");
            Object redo = null;
            java.util.Stack<?> redoStack = ctx.getField(editor(ctx).historyView, "redoStack"); redo = redoStack.size();
            return history.size() + " undo / redo " + redo;
        } catch (Exception e) {
            return "? " + e;
        }
    }

    // ---- gestures ------------------------------------------------------------------------------------

    /** Keys reach the focused view only; after a drop from the browser the hierarchy has to be given it. */
    private static void focusHierarchy(ScenarioBuilder s) {
        s.step("tag the hierarchy", ctx -> editor(ctx).hierarchyView.setId("fx-hierarchy"));
        s.focus("#fx-hierarchy");
    }

    private static void chord(ScenarioBuilder s, int key) {
        s.key(key, Keys.MOD_CONTROL).frames(2);
    }

    private static void clickRow(ScenarioBuilder s, String name) {
        var id = "row-" + name.toLowerCase().replace(' ', '-');
        s.step("find the " + name + " row", ctx -> rowLabel(ctx, name).setId(id));
        s.click("#" + id);
        s.frames(1);
    }

    private static void rightClickRow(ScenarioBuilder s, String name) {
        rightClickOn(s, "the " + name + " row", ctx -> centreOf(rowLabel(ctx, name)));
    }

    private static void rightClickOn(ScenarioBuilder s, String label, Function<TestContext, float[]> spot) {
        clickOn(s, label + " with the right button", Keys.MOUSE_RIGHT, spot);
    }

    private static void clickMenu(ScenarioBuilder s, String text) {
        clickOn(s, "'" + text + "' in the menu", Keys.MOUSE_LEFT, ctx -> {
            var entry = menuEntry(ctx, text);
            ctx.require("a menu entry '" + text + "'", entry != null);
            return centreOf(entry);
        });
    }

    /** Press and release at one spot resolved once: menus open or close on the press. */
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

    /** Below the last row: dropping there places under the root. */
    private static float[] emptyHierarchySpot(TestContext ctx) {
        var bounds = ElementBounds.of(editor(ctx).hierarchyView);
        return new float[]{bounds.x() + bounds.width() * 0.5f, bounds.y() + bounds.height() - 10};
    }

    /**
     * Press, nudge, leave, move, settle and drop, each in its own frame, between two spots resolved when the
     * drag starts: a drag only begins when the source sees the pointer leave with the button held.
     */
    private static void dragPoints(ScenarioBuilder s, String label, Function<TestContext, float[]> from,
                                   Function<TestContext, float[]> to) {
        var start = new float[2];
        var end = new float[2];
        s.step(label + " :aim", ctx -> {
            System.arraycopy(from.apply(ctx), 0, start, 0, 2);
            System.arraycopy(to.apply(ctx), 0, end, 0, 2);
            ctx.input().moveTo(start[0], start[1]);
        });
        s.step(label + " :press", ctx -> ctx.input().mouseDown(start[0], start[1], Keys.MOUSE_LEFT));
        s.step(label + " :nudge", ctx -> ctx.input().dragTo(start[0] + 1, start[1] + 1, Keys.MOUSE_LEFT));
        for (float t : new float[]{0.35f, 0.75f, 1f}) {
            s.step(label + " :move " + t, ctx -> ctx.input().dragTo(start[0] + (end[0] - start[0]) * t,
                    start[1] + (end[1] - start[1]) * t, Keys.MOUSE_LEFT));
        }
        s.step(label + " :settle", ctx -> ctx.input().dragTo(end[0] + 1, end[1], Keys.MOUSE_LEFT));
        s.step(label + " :drop", ctx -> ctx.input().mouseUp(end[0], end[1], Keys.MOUSE_LEFT));
    }

    private static float[] centreOf(UIElement element) {
        var bounds = ElementBounds.of(element);
        return new float[]{bounds.centerX(), bounds.centerY()};
    }

    @Nullable
    private static UIElement rowLabel(TestContext ctx, String name) {
        var view = editor(ctx).hierarchyView;
        var found = ctx.query().type(TextElement.class).withText(name).where(element -> isInside(element, view)).list();
        return found.isEmpty() ? null : found.getFirst().element();
    }

    @Nullable
    private static UIElement menuEntry(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).visible().list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> element.getFirstAncestorOfType(Menu.class) != null)
                .findFirst().orElse(null);
    }

    private static Button buttonWithText(TestContext ctx, String text) {
        return ctx.query().type(Button.class).visible().list().stream()
                .map(ref -> (Button) ref.element())
                .filter(button -> ctx.query().type(TextElement.class).list().stream()
                        .anyMatch(label -> isInside(label.element(), button) && text.equals(label.text())))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** The button on the same row as a label reading {@code text}, inside a dialog. */
    @Nullable
    private static UIElement buttonInDialogNear(TestContext ctx, String text) {
        for (var label : ctx.query().type(TextElement.class).list()) {
            if (!text.equals(label.text()) || label.element().getFirstAncestorOfType(Dialog.class) == null) continue;
            var buttons = ctx.query().type(Button.class).list().stream().map(ElementRef::element).toList();
            for (var row = label.element().getParent(); row != null; row = row.getParent()) {
                for (var button : buttons) {
                    if (isInside(button, row)) return button;
                }
            }
            return null;
        }
        return null;
    }

    private static void closeDialogs(TestContext ctx) {
        for (var dialog : new ArrayList<>(ctx.query().type(Dialog.class).list())) {
            ((Dialog) dialog.element()).close();
        }
    }

    /** Whatever in an open dialog sticks out of the dialog's own box. */
    private static List<String> overflowing(TestContext ctx) {
        var out = new ArrayList<String>();
        for (var ref : ctx.query().type(Dialog.class).list()) {
            var box = ref.as(Dialog.class).overlay;
            collectOverflow(box, box.getPositionX(), box.getPositionX() + box.getSizeWidth(), out);
        }
        return out;
    }

    private static void collectOverflow(UIElement element, float left, float right, List<String> out) {
        for (var child : element.getChildren()) {
            if (child.getPositionX() < left - 0.5f || child.getPositionX() + child.getSizeWidth() > right + 0.5f) {
                out.add("%s at %.0f..%.0f outside %.0f..%.0f".formatted(child.getClass().getSimpleName(), child.getPositionX(),
                        child.getPositionX() + child.getSizeWidth(), left, right));
            }
            collectOverflow(child, left, right, out);
        }
    }

    private static void collectButtons(UIElement element, List<String> out) {
        for (var child : element.getChildren()) {
            if (child instanceof Button button) out.add(String.valueOf(button.getId()));
            collectButtons(child, out);
        }
    }

    private static boolean isInside(UIElement element, UIElement ancestor) {
        for (var e = element; e != null; e = e.getParent()) {
            if (e == ancestor) return true;
        }
        return false;
    }

    private static void deleteTree(File root) {
        if (!root.exists()) return;
        try (var walk = Files.walk(root.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
