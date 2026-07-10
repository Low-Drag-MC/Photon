package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.kilagraph.editor.ShaderFunctionGraphResource;
import com.lowdragmc.kilagraph.rendertype.ShaderFunctionGraph;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;

/**
 * The Photon editor resource for reusable shader functions: identical to KilaGraph's
 * {@link ShaderFunctionGraphResource} (same name, so lang keys / file extensions / library meta are
 * shared), but new/loaded graphs are {@link PhotonShaderFunctionGraph}s — carrying Photon's node
 * palette so functions can use Particle Data, Depth Fade, Viewport, etc.
 */
public class PhotonShaderFunctionGraphResource extends ShaderFunctionGraphResource {
    public static final PhotonShaderFunctionGraphResource INSTANCE = new PhotonShaderFunctionGraphResource();

    protected PhotonShaderFunctionGraphResource() {}

    @Override
    public ShaderFunctionGraph createGraph() {
        return new PhotonShaderFunctionGraph();
    }

    @Override
    public IGuiTexture getIcon() {
        return PhotonIcons.SHADER_GRAPH_FUNCTION;
    }
}
