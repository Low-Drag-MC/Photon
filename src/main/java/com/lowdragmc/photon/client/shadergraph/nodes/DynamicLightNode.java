package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.kilagraph.rendertype.compiler.StageAffinity;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.LitParticles;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

/**
 * The dynamic lights on this fragment, as Lit Particles take them. Including {@code photon:lit_particle.glsl} is what
 * makes {@code ShaderGraphRuntime} declare the lights and {@code ShaderGraphMaterial} bind them.
 */
@NodeAttribute(name = "photon_dynamic_light", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class DynamicLightNode extends ShaderNode {

    @Override
    public StageAffinity stageAffinity() {
        return StageAffinity.FRAGMENT_ONLY;
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("normal", RenderTypeGraphTypes.VEC3).withoutConfigurator();
        context.addInputPort("wrap", TypeHandles.FLOAT).withDefaultValue(0f);
        context.addOutputPort("light", RenderTypeGraphTypes.VEC3);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        // a node preview has no lights, and nothing binds them outside a particle shader graph (a postfx pass)
        if (ctx.isPreview() || PhotonShaderCompiler.current() == null) {
            ctx.output("light", new ShaderExpr("vec3(0.0)", GlslType.VEC3));
            return;
        }
        ctx.include(LitParticles.INCLUDE);
        String normal = "vec3(0.0)";
        if (ctx.isConnected("normal")) {
            String view = ctx.useBuiltinUniform("ModelViewMat", GlslType.MAT4);
            var turned = ctx.temp(GlslType.VEC3, "mat3(" + view + ") * " + ctx.input("normal").code()).code();
            // a zero normal stays zero, light from every side, rather than a NaN
            normal = "(dot(" + turned + ", " + turned + ") > 1e-12 ? normalize(" + turned + ") : vec3(0.0))";
        }
        String wrap = "clamp(" + ctx.input("wrap").code() + ", 0.0, 1.0)";
        ctx.output("light", new ShaderExpr("photon_surface_light(" + ctx.viewSpacePosition().code() + ", "
                + normal + ", " + wrap + ")", GlslType.VEC3));
    }

    @Override
    protected String previewOutputPortId() {
        return "light";
    }

    @Override
    public String glslExample() {
        return """
                // #moj_import <photon:lit_particle.glsl>
                light = photon_surface_light(viewPos,
                    viewNormal, wrap);""";
    }
}
