package com.lowdragmc.photon.client.postfx.graph.gui;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.IGraphTool;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphCompiler;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect;
import com.lowdragmc.photon.client.postfx.runtime.PostEffectStack;
import com.lowdragmc.photon.client.postfx.runtime.PostFXPreview;
import com.lowdragmc.photon.client.postfx.runtime.PostFXTargetPool;
import com.lowdragmc.photon.client.postfx.runtime.RenderGraphExecutor;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import com.lowdragmc.lowdraglib2.client.shader.HDRTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * The render-graph editor's live preview: every frame the panel is visible it requests a clean
 * world-frame capture ({@link PostFXPreview}), runs THIS editor's graph (the live, unsaved model)
 * over that copy at weight 1 with schema defaults, and draws the result into the panel —
 * production effects and other open editors are untouched (the output is a pooled target released
 * right after drawing). Without a world frame (no level rendering behind the editor) it shows a
 * hint instead.
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
    public void drawBackgroundAdditional(GUIContext guiContext) {
        super.drawBackgroundAdditional(guiContext);
        if (!(view.getGraph() instanceof RenderGraph renderGraph)) return;

        PostFXPreview.requestCapture(); // next frame's render hooks refresh the capture
        var source = PostFXPreview.source();

        float x = getContentX();
        float y = getContentY();
        float width = getContentWidth();
        float height = getPaddingHeight();

        if (source == null) {
            guiContext.drawTexture(new TextTexture("photon.render_graph.preview.no_frame"), x, y, width, height);
            return;
        }

        ensureCompiled(renderGraph);
        if (compiled == null) {
            guiContext.drawTexture(new TextTexture(compileError.isEmpty()
                    ? "photon.render_graph.preview.no_frame" : compileError, 0xffff5555)
                    .setWidth((int) width), x, y, width, height);
            return;
        }

        // run the chain offscreen, then hand the framebuffer back to the UI pass
        guiContext.graphics.flush();
        HDRTarget result = null;
        if (!compiled.passes().isEmpty()) {
            PostEffectStack.setPostRenderState();
            try {
                // no mask source in the preview (it runs against a clean scene capture) — mask
                // inputs degrade to an empty sampler
                result = RenderGraphExecutor.execute(compiled, 1f, defaultParams, source,
                        source.getDepthTextureId(), -1, -1);
            } finally {
                PostEffectStack.restorePostRenderState();
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            }
        }

        var shown = result != null ? result : source; // broken/no-op effect previews the clean scene
        drawTarget(guiContext, shown, x, y, width, height);
        if (result != null) {
            PostFXTargetPool.release(result);
        }
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

    /** Aspect-fit blit of a render target's color texture into the panel rect (V flipped —
     *  render targets are bottom-up). */
    private static void drawTarget(GUIContext guiContext, HDRTarget target,
                                   float x, float y, float width, float height) {
        if (width <= 1 || height <= 1 || target.width <= 0 || target.height <= 0) return;
        float targetAspect = (float) target.width / target.height;
        float drawWidth = width;
        float drawHeight = width / targetAspect;
        if (drawHeight > height) {
            drawHeight = height;
            drawWidth = height * targetAspect;
        }
        float drawX = x + (width - drawWidth) / 2f;
        float drawY = y + (height - drawHeight) / 2f;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        Matrix4f matrix = guiContext.graphics.pose().last().pose();
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, drawX, drawY + drawHeight, 0).setUv(0f, 0f);
        buffer.addVertex(matrix, drawX + drawWidth, drawY + drawHeight, 0).setUv(1f, 0f);
        buffer.addVertex(matrix, drawX + drawWidth, drawY, 0).setUv(1f, 1f);
        buffer.addVertex(matrix, drawX, drawY, 0).setUv(0f, 1f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableCull();
    }
}
