package com.lowdragmc.photon.client.postfx.graph.gui;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.IGraphTool;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphCompiler;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect;
import com.lowdragmc.photon.client.postfx.runtime.PostFXPreview;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The render-graph editor's live preview: every frame the panel is visible it asks {@link PostFXPreview}
 * to run THIS editor's graph (the live, unsaved model) over a clean world-frame capture at weight 1 with
 * schema defaults, and draws the result — production effects and other open editors are untouched.
 * <p>
 * The run itself happens a frame later, at {@code RenderFrameEvent.Pre}: GUI drawing is inside an open
 * render pass, where opening another one throws. So the panel always shows the previous frame's result,
 * and shows a hint until there is one (no level rendering behind the editor, or a graph that won't
 * compile).
 */
public class PostFXPreviewTool extends UIElement implements IGraphTool {

    private final RenderGraphView view;

    // compile cache keyed on the live graph's change version + referenced fullscreen entries
    @Nullable
    private CompiledEffect compiled;
    private Map<String, Object> defaultParams = Map.of();
    private Map<IResourcePath, FullscreenGraphRuntime.Entry> compiledEntries = Map.of();
    private long compiledVersion = Long.MIN_VALUE;
    private String compileError = "";

    public PostFXPreviewTool(RenderGraphView view) {
        this.view = view;
        // fill whatever the dock panel gives us; drawTarget letterboxes inside (aspect preserved)
        com.lowdragmc.lowdraglib2.gui.ui.Style.defaultPipeline(getLayout(),
                layout -> layout.widthPercent(100).heightPercent(100));
    }

    @Override
    public Component getTitle() {
        return Component.translatable("photon.render_graph.preview");
    }

    @Override
    public void drawBackgroundAdditional(com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext rawContext) {
        super.drawBackgroundAdditional(rawContext);
        if (!(rawContext instanceof GUIContext guiContext)) return;
        if (!(view.getGraph() instanceof RenderGraph renderGraph)) return;

        float x = getContentX();
        float y = getContentY();
        float width = getContentWidth();
        float height = getPaddingHeight();

        ensureCompiled(renderGraph);
        if (compiled == null) {
            PostFXPreview.requestCapture(); // keep the capture warm while the author fixes the graph
            guiContext.drawTexture(new TextTexture(compileError.isEmpty()
                    ? "photon.render_graph.preview.no_frame" : compileError, 0xffff5555)
                    .setWidth((int) width), x, y, width, height);
            return;
        }

        // GUI drawing runs inside an open render pass, so the chain cannot execute here: ask for it and
        // blit the frame that was rendered at RenderFrameEvent.Pre. Production effects and other open
        // editors are untouched — this runs the LIVE, unsaved graph at weight 1 with schema defaults.
        PostFXPreview.requestPreview(compiled, defaultParams);
        var preview = PostFXPreview.previewTexture();
        if (preview == null) {
            guiContext.drawTexture(new TextTexture("photon.render_graph.preview.no_frame"), x, y, width, height);
            return;
        }
        guiContext.drawTexture(preview, x, y, width, height);
    }

    /** Recompile the LIVE graph when it (or any referenced fullscreen graph) changed. */
    private void ensureCompiled(RenderGraph renderGraph) {
        if (compiledVersion == renderGraph.getChangeVersion() && entriesFresh()) return;
        compiledVersion = renderGraph.getChangeVersion();
        try {
            var result = RenderGraphCompiler.compile(null, renderGraph);
            compiled = result.effect();
            defaultParams = compiled.defaultParams();
            compiledEntries = result.passEntries();
            compileError = "";
        } catch (RenderGraphCompiler.CompileError error) {
            compiled = null;
            compiledEntries = Map.of();
            compileError = error.getMessage();
        } catch (RuntimeException e) {
            compiled = null;
            compiledEntries = Map.of();
            compileError = String.valueOf(e.getMessage());
        }
    }

    private boolean entriesFresh() {
        for (var binding : compiledEntries.entrySet()) {
            if (FullscreenGraphRuntime.get(binding.getKey()) != binding.getValue()) return false;
        }
        return true;
    }

}
