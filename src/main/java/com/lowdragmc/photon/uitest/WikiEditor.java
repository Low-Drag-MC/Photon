package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.ui.EditorLayout;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.SplittableWindow.LayoutConfig;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.timeline.TimelineSection;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.view.FXTimelineView;
import com.lowdragmc.photon.gui.editor.view.timeline.SectionStrip;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The FX editor as the wiki recordings show it: a layout of their own, with the scene on top, a wide timeline below and
 * the inspector on the right. The user's saved editor layouts are kept aside while it records and put back afterwards.
 */
final class WikiEditor {
    static final int LEFT = 0;
    static final int RIGHT = 1;
    private static final Map<String, byte[]> savedLayouts = new HashMap<>();

    private WikiEditor() {
    }

    /** Opens the editor; {@link #close} has to be among the recording's teardowns. */
    static ScenarioBuilder open(ScenarioBuilder s) {
        return s.step("keep the user's editor layouts aside", ctx -> keepLayouts())
                .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                                EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                        .shouldCloseOnEsc(false)
                        .shouldCloseOnKeyInventory(false))
                .awaitScreen(ModularUIScreen.class)
                .awaitModularUI()
                .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty());
    }

    static ScenarioBuilder close(ScenarioBuilder s) {
        return s.teardown("close the project", ctx -> {
                    var editors = ctx.query().type(FXEditor.class).list();
                    if (editors.isEmpty()) return;
                    var editor = editors.getFirst().as(FXEditor.class);
                    editor.sceneView.particleManager.pause();
                    if (editor.getCurrentProject() != null) editor.closeCurrentProject(false, null);
                })
                // after the close, which saves the layout it closed with
                .teardown("put the user's editor layouts back", ctx -> restoreLayouts());
    }

    /** Runs {@code load}, then zooms the timeline so {@code ticks} fill its lanes once the layout has settled. */
    static ScenarioBuilder show(ScenarioBuilder s, String name, java.util.function.Consumer<TestContext> load, double ticks) {
        return s.step(name, load)
                .frames(10)
                .step("fit " + ticks + " ticks into the timeline", ctx -> fitTimeline(ctx, ticks))
                .frames(5);
    }

    /** The wheel over the ruler at tick 0 zooms in and keeps tick 0 at the left. */
    private static void fitTimeline(TestContext ctx, double ticks) {
        var view = view(ctx);
        var ruler = byId(ctx, "timeline.ruler");
        var width = ruler.getPositionX() + ruler.getSizeWidth() - view.originX();
        var x = view.originX() + 0.5f;
        var y = rulerY(ctx);
        for (int i = 0; i < 80 && view.scale() * 1.1f * ticks < width; i++) ctx.input().scroll(x, y, 1);
        for (int i = 0; i < 80 && view.scale() * ticks > width; i++) ctx.input().scroll(x, y, -1);
    }

    /** Opens {@code demo} as a new, unsaved project, paused at {@code tick}, the camera on {@code lookAt} (the effect sits a block up). */
    static void load(TestContext ctx, WikiDemos.Demo demo, boolean withSections, Vector3f lookAt, double radius,
                     double pitch, long tick) {
        var editor = editor(ctx);
        editor.sceneView.particleManager.pause();
        if (editor.getCurrentProject() != null) editor.closeCurrentProject(false, null);
        var project = new FXProject();
        demo.fill(project.getFx(), withSections);
        editor.loadProject(project, null);
        videoLayout(editor);
        editor.sceneView.fxObjectInfoView.setDisplay(false);
        var renderer = editor.sceneView.sceneEditor.scene.getRenderer();
        if (renderer != null) renderer.setCameraLookAt(lookAt, radius, Math.PI / 2, pitch);
        editor.sceneView.particleManager.pause();
        editor.sceneView.simulateTo(tick);
    }

    /** Scene top left, the timeline under it across the whole width, inspector and hierarchy on the right. */
    private static void videoLayout(FXEditor editor) {
        var top = new LayoutConfig(null, false, 100, new LayoutConfig("left", false, 50, null, null),
                new LayoutConfig("center", false, 50, null, null));
        var bottom = new LayoutConfig("bottom", false, 100, new LayoutConfig(null, false, 50, null, null),
                new LayoutConfig(null, false, 50, null, null));
        var right = new LayoutConfig("right", true, 62, new LayoutConfig(null, false, 50, null, null),
                new LayoutConfig(null, false, 50, null, null));
        var root = new LayoutConfig("root", false, 74, new LayoutConfig(null, true, 54, top, bottom), right);
        editor.applyLayout(new EditorLayout(root, List.of(
                new EditorLayout.SlotEntry("fff", List.of("editor.scene"), "editor.scene"),
                new EditorLayout.SlotEntry("fsf", List.of("editor.timeline", "editor.view.resources"), "editor.timeline"),
                new EditorLayout.SlotEntry("sf", List.of("editor.view.inspector", "editor.view.history"), "editor.view.inspector"),
                new EditorLayout.SlotEntry("ss", List.of("editor.fx_object.hierarchy"), "editor.fx_object.hierarchy"))));
    }

    private static File layoutsDir() {
        return new File(LDLib2.getAssetsDir().getParentFile(), "editor_layouts");
    }

    private static void keepLayouts() {
        if (!savedLayouts.isEmpty()) return;
        var files = layoutsDir().listFiles(File::isFile);
        if (files == null) return;
        for (var file : files) {
            try {
                savedLayouts.put(file.getName(), Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                throw new IllegalStateException("could not keep " + file + " aside", e);
            }
        }
    }

    private static void restoreLayouts() {
        var dir = layoutsDir();
        var files = dir.listFiles(File::isFile);
        if (files != null) {
            for (var file : files) {
                if (!savedLayouts.containsKey(file.getName()) && !file.delete()) file.deleteOnExit();
            }
        }
        for (var entry : savedLayouts.entrySet()) {
            try {
                Files.write(new File(dir, entry.getKey()).toPath(), entry.getValue());
            } catch (IOException e) {
                LDLib2.LOGGER.error("Could not put the editor layout {} back", entry.getKey(), e);
            }
        }
        savedLayouts.clear();
    }

    // ---- finding things ----------------------------------------------------------------------------

    static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    static FXTimelineView view(TestContext ctx) {
        return editor(ctx).timelineView;
    }

    static SectionStrip strip(TestContext ctx) {
        return view(ctx).sectionStrip();
    }

    static FXRuntime runtime(TestContext ctx) {
        var runtime = editor(ctx).runtime;
        ctx.require("a project is open", runtime != null);
        return runtime;
    }

    static TimelineSection section(TestContext ctx, String name) {
        var section = runtime(ctx).fxData.timeline().sections().stream()
                .filter(s -> s.name().equals(name)).findFirst().orElse(null);
        ctx.require("a section " + name, section != null);
        return section;
    }

    static boolean isIn(TestContext ctx, String section) {
        return section.equals(runtime(ctx).sections().current());
    }

    static UIElement band(TestContext ctx, String name) {
        var band = strip(ctx).band(name);
        ctx.require("a band for " + name, band != null);
        return band;
    }

    /** A band's left edge: its first child is the edge line. */
    static UIElement edge(TestContext ctx, String name) {
        return band(ctx, name).getChildren().getFirst();
    }

    /** Where to drop a band's edge for it to land on {@code tick}, allowing for where on the edge it was grabbed. */
    static float[] edgeDrop(TestContext ctx, String name, double tick) {
        var edge = edge(ctx, name);
        var grab = edge.getPositionX() + edge.getSizeWidth() / 2 - band(ctx, name).getPositionX();
        return new float[]{x(ctx, tick) + grab - 0.5f, stripY(ctx)};
    }

    static UIElement byId(TestContext ctx, String id) {
        return ctx.query().withId(id).one().as(UIElement.class);
    }

    /** A menu entry by its label: menus are internal UI, which {@code withText} leaves out. */
    static UIElement menuEntry(TestContext ctx, String label) {
        var found = ctx.query().where(e -> (e.hasClass("__menu_leaf-node__") || e.hasClass("__menu_branch-node__"))
                && e.selfAndAllChildren().anyMatch(c -> c instanceof TextElement text && text.getText().getString().equals(label)))
                .list();
        ctx.require("a menu entry \"" + label + "\"", !found.isEmpty());
        return found.getLast().as(UIElement.class);
    }

    static boolean menuOpen(TestContext ctx) {
        return !ctx.query().withClass("__menu_leaf-node__").list().isEmpty();
    }

    /** The clip labelled with its object's name. */
    static UIElement clip(TestContext ctx, String objectName) {
        var found = ctx.query().withId("timeline.clip").where(e -> e.selfAndAllChildren()
                .anyMatch(c -> c instanceof TextElement text && text.getText().getString().equals(objectName))).list();
        ctx.require("a clip for " + objectName, !found.isEmpty());
        return found.getFirst().as(UIElement.class);
    }

    /** The hierarchy row showing {@code name}. */
    static UIElement hierarchyRow(TestContext ctx, String name) {
        var hierarchy = editor(ctx).hierarchyView;
        var found = hierarchy.selfAndAllChildren()
                .filter(c -> c instanceof TextElement text && text.getText().getString().equals(name)).toList();
        ctx.require("a hierarchy row for " + name, !found.isEmpty());
        return found.getFirst();
    }

    /** The text field of the dialog on top. */
    static TextField dialogField(TestContext ctx) {
        var dialogs = ctx.query().type(Dialog.class).list();
        ctx.require("a dialog is open", !dialogs.isEmpty());
        var field = dialogs.getLast().as(Dialog.class).selfAndAllChildren()
                .filter(e -> e instanceof TextField).map(e -> (TextField) e).findFirst().orElse(null);
        ctx.require("with a text field", field != null);
        return field;
    }

    static float x(TestContext ctx, double tick) {
        return view(ctx).originX() + view(ctx).tickToLocalX(tick);
    }

    static float stripY(TestContext ctx) {
        var strip = strip(ctx).strip;
        return strip.getPositionY() + strip.getSizeHeight() / 2;
    }

    static float rulerY(TestContext ctx) {
        var ruler = byId(ctx, "timeline.ruler");
        return ruler.getPositionY() + ruler.getSizeHeight() / 2;
    }

    /** A rectangle around the timeline's ruler, section row and first lanes from {@code fromTick} to {@code toTick}. */
    static float[] timelineRect(TestContext ctx, double fromTick, double toTick, float lanes) {
        var ruler = byId(ctx, "timeline.ruler");
        var left = x(ctx, fromTick);
        var top = ruler.getPositionY() - 18;
        return new float[]{left, top, x(ctx, toTick) - left, 18 + ruler.getSizeHeight() + SectionStrip.HEIGHT + lanes};
    }

    /** The top of the inspector, where an inspected section or clip lists its settings. */
    static float[] inspectorRows(TestContext ctx, float height) {
        var inspector = editor(ctx).inspectorView;
        return new float[]{inspector.getPositionX(), inspector.getPositionY(), inspector.getSizeWidth(), height};
    }

    /** The open menus, with room on the right for a submenu. */
    static float[] menuRect(TestContext ctx) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (var ref : ctx.query().where(e -> e.hasClass("__menu_leaf-node__") || e.hasClass("__menu_branch-node__")).list()) {
            var e = ref.as(UIElement.class);
            minX = Math.min(minX, e.getPositionX());
            minY = Math.min(minY, e.getPositionY());
            maxX = Math.max(maxX, e.getPositionX() + e.getSizeWidth());
            maxY = Math.max(maxY, e.getPositionY() + e.getSizeHeight());
        }
        ctx.require("a menu is open", minX <= maxX);
        var w = maxX - minX;
        return new float[]{minX - 20, minY - 20, w * 2.2f + 40, maxY - minY + 40};
    }

    /** The dialog box on top (its element is a full-screen veil), with as much of its surroundings again around it. */
    static float[] dialogRect(TestContext ctx) {
        var field = dialogField(ctx);
        var confirm = confirmButton(ctx);
        var left = Math.min(field.getPositionX(), confirm.getPositionX());
        var top = field.getPositionY() - 16;
        var right = Math.max(field.getPositionX() + field.getSizeWidth(), confirm.getPositionX() + confirm.getSizeWidth());
        var bottom = confirm.getPositionY() + confirm.getSizeHeight();
        var w = right - left;
        var h = bottom - top;
        return new float[]{left - w * 0.5f, top - h * 0.5f, w * 2, h * 2};
    }

    /** Preview once and hold at the end, instead of starting over: a take waits for the end. */
    static ScenarioBuilder holdAtTheEnd(ScenarioBuilder s) {
        return s.click("the loop toggle, off", ctx -> byId(ctx, "timeline.wrapMode"), LEFT).frames(2);
    }

    static UIElement confirmButton(TestContext ctx) {
        return ctx.query().withClass("__confirm-button__").one().as(UIElement.class);
    }

    static long time(TestContext ctx) {
        return editor(ctx).sceneView.particleManager.getTime();
    }

    static ScenarioBuilder say(ScenarioBuilder s, String en, String zh) {
        return s.caption(en).caption("zh", zh);
    }
}
