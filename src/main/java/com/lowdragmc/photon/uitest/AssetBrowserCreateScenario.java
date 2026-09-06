package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.resource.Resource;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.browser.FileOps;
import com.lowdragmc.lowdraglib2.editor.ui.browser.ResourceBehaviorCache;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.gui.editor.FXEditor;
import com.lowdragmc.photon.gui.editor.FXProject;
import com.lowdragmc.photon.gui.editor.resource.CurveResource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every resource type the editor knows can be created from the asset browser, and doing so writes a file.
 *
 * <p>Reported from the wild: curves, materials and gradients had no "add new resource" entry in the
 * browser's create menu, and the empty submenu they did show did nothing when clicked. The cause was that
 * they registered their create entries through {@code setOnMenu} — the hook for the context menu of an
 * <em>existing</em> resource — while the browser builds its create menu out of {@code addDefault} and
 * {@code onCreateMenu}, which they left unset.
 *
 * <p>So the check is on the same two hooks the browser reads, through the same
 * {@link ResourceBehaviorCache} it borrows the per-type behavior from, and it covers every type the
 * project registers rather than the three that were reported: any type may forget the same hook next.
 * The cache is rebuilt here rather than borrowed because the browser keeps its own private.
 */
@LDLRegisterClient(name = "asset_browser_create", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AssetBrowserCreateScenario implements UIScenario {

    /** Resources are created into a folder of this scenario's own, never into the dev's assets. */
    private static final String FIXTURE_DIR = "uitest_create";

    private static File fixtureDir() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/" + FIXTURE_DIR);
    }

    /** A create entry as the menu holds it: the label the user reads, and what clicking it runs. */
    private record CreateEntry(String name, Runnable action) {}

    @Override
    public void configure(ScenarioOptions options) {
        // the editor's scene view renders a world, so it cannot be built on the title screen
        options.tags("photon", "editor", "assets").requiresWorld(true).scenarioTimeoutMs(180_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("create an empty fixture folder", ctx -> {
            var dir = fixtureDir();
            FileOps.deleteRecursively(dir);
            ctx.require("the fixture directory was created", dir.mkdirs());
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())

        // the resource types come from the project, not from the editor: without one loaded the browser
        // has nothing to offer creating in the first place
        .step("open an empty project", ctx -> editor(ctx).loadProject(FXProject.TYPE.newEmptyProject(), null))
        .waitUntil("the project's resource types are loaded", ctx ->
                editor(ctx).resourceView.getResources().containsKey(CurveResource.INSTANCE))

        .step("browse the fixture folder", ctx ->
                editor(ctx).resourceView.getAssetBrowser().openDirectory(fixtureDir()))
        .waitUntil("the browser moved", ctx ->
                fixtureDir().equals(editor(ctx).resourceView.getAssetBrowser().getCurrentDirectory()))
        .screenshot("01_empty_folder")

        .step("every resource type offers a way to create one", ctx -> {
            var editor = editor(ctx);
            var behaviors = new ResourceBehaviorCache(editor.resourceView.getAssetBrowser(), editor);
            behaviors.setDirectory(fixtureDir());
            ctx.put("behaviors", behaviors);
            for (var resource : List.copyOf(behaviors.availableResources())) {
                var entries = createEntries(behaviors, resource);
                ctx.log("%s: %s".formatted(resource.getName(),
                        entries.stream().map(CreateEntry::name).toList()));
                ctx.check("\"%s\" offers a create entry".formatted(resource.getName()),
                        !entries.isEmpty(), ">= 1", entries.size());
            }
        })

        // and the entry does something: the three that were reported showed an empty submenu, which is
        // indistinguishable from a menu whose entries are all dead
        .step("using the first entry writes a file", ctx -> {
            var behaviors = ctx.<ResourceBehaviorCache>get("behaviors");
            for (var resource : List.copyOf(behaviors.availableResources())) {
                var entries = createEntries(behaviors, resource);
                if (entries.isEmpty()) continue; // already reported by the previous step
                var entry = entries.getFirst();
                var before = fixtureFiles();
                entry.action().run();
                var added = new ArrayList<>(fixtureFiles());
                added.removeAll(before);
                ctx.check("\"%s -> %s\" created a %s file".formatted(
                                resource.getName(), entry.name(), resource.getFileExtension()),
                        added.size() == 1 && added.getFirst().endsWith(resource.getFileExtension()),
                        "one " + resource.getFileExtension() + " file", added);
            }
        })
        // the browser polls the folder rather than being told about it, so give it a moment to list them
        .frames(40)
        .screenshot("02_created")

        .teardown("drop the behavior containers", ctx -> {
            var behaviors = ctx.<ResourceBehaviorCache>get("behaviors");
            if (behaviors != null) behaviors.dispose();
        })
        .teardown("close the editor", ctx -> ctx.mc().setScreen(null))
        .teardown("delete the fixture folder",
                ctx -> ctx.check("the fixture was removed", FileOps.deleteRecursively(fixtureDir())));
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    private static Set<String> fixtureFiles() {
        var names = new LinkedHashSet<String>();
        var files = fixtureDir().listFiles();
        if (files != null) {
            for (var file : files) {
                names.add(file.getName());
            }
        }
        return names;
    }

    /** What the browser's create menu ends up holding for one resource type, flattened. */
    private static List<CreateEntry> createEntries(ResourceBehaviorCache behaviors, Resource<?> resource) {
        var behavior = behaviors.get(resource);
        if (behavior == null) return List.of();
        var menu = TreeBuilder.Menu.start();
        behavior.appendCreateMenu(menu, () -> {});
        var entries = new ArrayList<CreateEntry>();
        collectEntries(menu.build(), entries);
        return entries;
    }

    private static void collectEntries(TreeNode<Tuple<IGuiTexture, Component>, Runnable> node,
                                       List<CreateEntry> entries) {
        for (var child : node.getChildren()) {
            if (child.isBranch()) {
                collectEntries(child, entries);
            } else if (child.getContent() != null) { // null content is a separator line
                entries.add(new CreateEntry(child.getKey().getB().getString(), child.getContent()));
            }
        }
    }
}
