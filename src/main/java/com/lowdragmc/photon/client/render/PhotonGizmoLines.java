package com.lowdragmc.photon.client.render;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.lowdraglib2.client.utils.RenderUtils;
import com.lowdragmc.photon.Photon;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import java.util.List;

/** Editor gizmo lines drawn over the scene (no depth test): a light's reach or a collider shows through walls. */
public final class PhotonGizmoLines {
    /** Vanilla's line pipelines all depth-test. */
    public static final RenderType LINES = RenderType.create("photon_gizmo_lines",
            RenderSetup.builder(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Photon.id("pipeline/gizmo_lines"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    // 26.3 gives a pipeline without one no colour target at all (a depth-only pipeline)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build()).createRenderSetup());

    private PhotonGizmoLines() {
    }

    /** {@code lines} in {@code poseStack}'s space, into the editor's after-world draw. */
    public static void draw(PoseStack poseStack, RenderUtils.ImmediateDraw draw, List<Pair<Vector3f, Vector3f>> lines,
                            int color, float width) {
        if (lines.isEmpty()) return;
        RenderBufferUtils.drawEdges(poseStack, draw.getBuffer(LINES), lines, color, width);
    }
}
