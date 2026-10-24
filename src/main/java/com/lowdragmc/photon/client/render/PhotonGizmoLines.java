package com.lowdragmc.photon.client.render;

import com.lowdragmc.lowdraglib2.client.utils.RenderBufferUtils;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector3f;
import oshi.util.tuples.Pair;

import java.util.List;

/** Editor gizmo lines drawn over the scene (no depth test): a light's reach or a collider shows through walls. */
public final class PhotonGizmoLines {
    /** 26.1's line types all depth-test. */
    public static final RenderType LINES = RenderType.create("photon_gizmo_lines",
            RenderSetup.builder(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Photon.id("pipeline/gizmo_lines"))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build()).createRenderSetup());

    private PhotonGizmoLines() {
    }

    /** {@code lines} in {@code poseStack}'s space, through the editor's after-world buffer source. */
    public static void draw(PoseStack poseStack, MultiBufferSource bufferSource, List<Pair<Vector3f, Vector3f>> lines,
                            int color, float width) {
        if (lines.isEmpty()) return;
        RenderBufferUtils.drawEdges(poseStack, bufferSource.getBuffer(LINES), lines, color, width);
    }
}
