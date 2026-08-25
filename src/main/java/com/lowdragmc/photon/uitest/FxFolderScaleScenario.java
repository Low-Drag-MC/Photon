package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.editor.ui.EditorWindow;
import com.lowdragmc.lowdraglib2.editor.ui.browser.FileOps;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.util.FileNode;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.gui.editor.FXEditor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * The editor against an fx folder holding as many effects as people actually accumulate.
 *
 * <p>Reported from the wild: ~1800 exported {@code .fx} files in one folder froze the editor solid.
 * The cost was never the effects — nothing here loads one — but the folder listing behind the file
 * dialog's tree and the asset browser, which was re-read from scratch every client tick, at several
 * stat syscalls per entry per comparison.
 *
 * <p>What this pins is the thing a unit test cannot reach: that the editor still renders and ticks
 * with the folder open. The listing's own cost is covered by {@code FileNodeTest} in LDLib2; the
 * numbers here are wall-clock and therefore machine-dependent, so the thresholds are set to catch a
 * return of the freeze (which cost two orders of magnitude), not to police small regressions.
 */
@LDLRegisterClient(name = "fx_folder_scale", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxFolderScaleScenario implements UIScenario {

    /** What the report that prompted this was: "1,800 fx files into photon editor and it goes kaboom". */
    private static final int FILE_COUNT = 1800;
    /**
     * The effects live in a sub-folder of the export directory rather than in it directly: expanding a
     * folder node is the operation that used to freeze, and it keeps the fixture to one directory that
     * teardown can delete whole without ever touching effects a dev actually has.
     */
    private static final String BULK_DIR = "uitest_bulk";

    private static final int MEASURED_FRAMES = 120;
    /**
     * 120 frames inside 8s is 15fps — far below what a dev client renders idle, and far above what the
     * broken build managed: 64ms of directory listing per tick is 1.3s of work per second of wall
     * clock, which dropped the client to single-digit frames and tripped the harness watchdog.
     */
    private static final long FRAME_BUDGET_MS = 8_000;
    /**
     * 100 polls of the listing, i.e. five seconds of ticking. The old code needed ~6.5s for this;
     * anything under a second means the cache is doing its job even if the machine is slow.
     */
    private static final int POLL_COUNT = 100;
    private static final long POLL_BUDGET_MS = 1_000;
    /**
     * The other half of the poll: what a tick pays when the cache does not answer, which is every real
     * change to the folder and once per cache window regardless. The old code paid this every tick, at
     * ~64ms a time; 20 of them inside 600ms means a re-read still costs a fraction of that.
     */
    private static final int REREAD_COUNT = 20;
    private static final long REREAD_BUDGET_MS = 600;

    private static File fxDir() {
        return new File(LDLib2.getAssetsDir(), Photon.MOD_ID + "/fx");
    }

    private static File bulkDir() {
        return new File(fxDir(), BULK_DIR);
    }

    @Override
    public void configure(ScenarioOptions options) {
        // The editor's scene view renders a world, so it cannot be built on the title screen.
        // Creating and deleting 1800 files is slower than the default scenario budget allows.
        options.tags("photon", "editor", "scale").requiresWorld(true).scenarioTimeoutMs(180_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("write %d fx files".formatted(FILE_COUNT), ctx -> {
            var dir = bulkDir();
            FileOps.deleteRecursively(dir);
            ctx.require("the fixture directory was created", dir.mkdirs());
            try {
                for (int i = 0; i < FILE_COUNT; i++) {
                    // Content is irrelevant: listing a folder is what is under test, and nothing here
                    // ever opens one. Empty files keep the fixture cheap to write and delete.
                    Files.write(new File(dir, "uitest_%04d%s".formatted(i, FX.SUFFIX)).toPath(), new byte[0]);
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not write the fx fixture", e);
            }
            var listed = dir.list();
            ctx.check("the folder holds %d entries".formatted(FILE_COUNT),
                    listed != null && listed.length == FILE_COUNT,
                    FILE_COUNT, listed == null ? 0 : listed.length);
        })

        // The listing on its own, before any UI is involved. This is the tick cost that froze the
        // client, measured directly so a failure says which half broke.
        .step("poll and re-read the listing", ctx -> {
            var node = new FileNode(bulkDir()).setValid(Dialog.suffixFilter(FX.SUFFIX));
            var children = node.getChildren();
            ctx.check("the listing sees every file", children.size() == FILE_COUNT,
                    FILE_COUNT, children.size());

            // what a tick pays while the folder is unchanged, which is nearly every tick
            var start = System.nanoTime();
            for (int i = 0; i < POLL_COUNT; i++) {
                node.getChildren();
            }
            var pollMs = (System.nanoTime() - start) / 1_000_000;
            ctx.attach("pollMs", String.valueOf(pollMs));
            ctx.log("%d cached polls of %d entries took %dms".formatted(POLL_COUNT, FILE_COUNT, pollMs));
            ctx.check("%d polls stay under %dms".formatted(POLL_COUNT, POLL_BUDGET_MS),
                    pollMs < POLL_BUDGET_MS, "< " + POLL_BUDGET_MS + "ms", pollMs + "ms");

            // and what it pays when the cache cannot answer — the old cost of every single tick
            start = System.nanoTime();
            for (int i = 0; i < REREAD_COUNT; i++) {
                node.invalidate();
                node.getChildren();
            }
            var rereadMs = (System.nanoTime() - start) / 1_000_000;
            ctx.attach("rereadMs", String.valueOf(rereadMs));
            ctx.log("%d full re-reads of %d entries took %dms".formatted(REREAD_COUNT, FILE_COUNT, rereadMs));
            ctx.check("%d re-reads stay under %dms".formatted(REREAD_COUNT, REREAD_BUDGET_MS),
                    rereadMs < REREAD_BUDGET_MS, "< " + REREAD_BUDGET_MS + "ms", rereadMs + "ms");
        })

        .openModularUI("photon editor", ctx -> new ModularUI(UI.of(
                        EditorWindow.open(FXEditor.WINDOW_ID, FXEditor::new).setId("fx_editor")))
                .shouldCloseOnEsc(false)
                .shouldCloseOnKeyInventory(false))
        .awaitScreen(ModularUIScreen.class)
        .awaitModularUI()
        .waitUntil("the editor is laid out", ctx -> !ctx.query().type(FXEditor.class).list().isEmpty())
        .screenshot("01_editor_open")

        .group("file dialog", g -> g
                // Built exactly as FXProject's "Export FX" entry builds it — same root, same filter.
                // Opened through the API rather than by walking the File menu so that a change to the
                // menu layout cannot turn this into a false alarm about folder scale.
                .step("open the export dialog", ctx -> Dialog.showFileDialog(
                                "ldlib.gui.editor.tips.save_as", fxDir(), false,
                                Dialog.suffixFilter(FX.SUFFIX), file -> {})
                        .show(editor(ctx)))
                .waitUntil("the dialog's tree exists", ctx -> !ctx.query().type(TreeList.class).list().isEmpty())
                .screenshot("02_dialog_collapsed")

                // Two expands, as a user's two clicks: a collapsed node has no row, and a node with no
                // row is one TreeList#expandNode returns from without listing anything.
                .step("expand the fx folder", ctx -> {
                    var tree = dialogTree(ctx);
                    var root = tree.getRoot();
                    ctx.require("the dialog's tree has a root", root != null);
                    tree.expandNode(root);
                    ctx.check("the fixture folder is listed", root.getChildren().stream()
                            .anyMatch(child -> child.getKey().getName().equals(BULK_DIR)));
                })
                .step("expand the folder of %d files".formatted(FILE_COUNT), ctx -> {
                    var tree = dialogTree(ctx);
                    var root = tree.getRoot();
                    ctx.require("the dialog's tree has a root", root != null);
                    var bulk = root.getChildren().stream()
                            .filter(child -> child.getKey().getName().equals(BULK_DIR))
                            .findFirst()
                            .orElse(null);
                    ctx.require("the fixture folder is listed", bulk != null);
                    ctx.require("the fixture folder has a row", tree.getNodeUIs().containsKey(bulk));
                    tree.expandNode(bulk);
                })
                .waitUntil("every file has a row", ctx -> dialogTree(ctx).getNodeUIs().size() > FILE_COUNT)
                .step("the tree shows the whole folder", ctx -> {
                    var rows = dialogTree(ctx).getNodeUIs().size();
                    // one row per file, plus the root and the fixture folder itself
                    ctx.check("every file got a row", rows >= FILE_COUNT, ">= " + FILE_COUNT, rows);
                })
                .screenshot("03_dialog_expanded")
                .step("start timing the expanded tree", ctx -> ctx.put("frameStart", System.nanoTime()))
                .frames(MEASURED_FRAMES)
                .step("the editor still renders", ctx -> checkFrameBudget(ctx, "expanded tree"))
                .step("close the dialog", ctx -> ctx.query().type(Dialog.class).list()
                        .forEach(ref -> ref.as(Dialog.class).close())))

        .group("asset browser", g -> g
                .step("browse the folder of %d files".formatted(FILE_COUNT),
                        ctx -> editor(ctx).resourceView.getAssetBrowser().openDirectory(bulkDir()))
                .waitUntil("the browser moved", ctx ->
                        bulkDir().equals(editor(ctx).resourceView.getAssetBrowser().getCurrentDirectory()))
                .screenshot("04_asset_browser")
                .step("start timing the open folder", ctx -> ctx.put("frameStart", System.nanoTime()))
                .frames(MEASURED_FRAMES)
                .step("the editor still renders", ctx -> checkFrameBudget(ctx, "asset browser")))

        .teardown("close the editor", ctx -> ctx.mc().setScreen(null))
        .teardown("delete the fx files",
                ctx -> ctx.check("the fixture was removed", FileOps.deleteRecursively(bulkDir())));
    }

    private static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    /**
     * The file dialog's tree, identified by the folder it is rooted at. Picking it out of the editor's
     * other trees by elimination is not enough: the hierarchy view holds one too, and with no project
     * loaded its root is null, so "the tree that is not the asset browser's" can find the wrong one.
     */
    @SuppressWarnings("unchecked")
    private static TreeList<FileNode> dialogTree(TestContext ctx) {
        var wanted = fxDir();
        return ctx.query()
                .type(TreeList.class)
                .where(element -> ((TreeList<?>) element).getRoot() instanceof FileNode root
                        && root.getKey().equals(wanted))
                .one()
                .as(TreeList.class);
    }

    private static void checkFrameBudget(TestContext ctx, String what) {
        long elapsedMs = (System.nanoTime() - ctx.<Long>get("frameStart")) / 1_000_000;
        ctx.attach(what + " frameMs", String.valueOf(elapsedMs));
        ctx.log("%d frames with the %s took %dms".formatted(MEASURED_FRAMES, what, elapsedMs));
        ctx.check("%d frames with the %s stay under %dms".formatted(MEASURED_FRAMES, what, FRAME_BUDGET_MS),
                elapsedMs < FRAME_BUDGET_MS, "< " + FRAME_BUDGET_MS + "ms", elapsedMs + "ms");
    }

}
