package com.lowdragmc.photon.client.postfx.graph;

import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.variable.VariableKind;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.IVariableNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.variable.VariableDeclarationModelBase;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.photon.client.postfx.graph.nodes.EffectWeightNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.OutputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.PassNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.SceneColorInputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.SceneDepthInputNode;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect.ResourceRef;
import com.lowdragmc.photon.client.postfx.runtime.CompiledEffect.ValueBinding;
import com.lowdragmc.photon.client.postfx.runtime.CustomShaderPass;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a {@link RenderGraph} into a flat {@link CompiledEffect}: DFS from the output node
 * topologically orders the contributing passes (cycle-checked), each pass output becomes a transient
 * resource with first/last-use pass indices (the pool aliases inside those windows), texture wires
 * resolve to {@link ResourceRef}s and value ports to {@link ValueBinding}s (inline constant, a
 * blackboard parameter, or the Effect Weight node — v1 allows no CPU math between them and a port).
 *
 * <p>Also captures the {@code FullscreenGraphRuntime} entry of every referenced pass graph so
 * {@code RenderGraphRuntime} can detect nested staleness by identity.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenderGraphCompiler {

    /** A user-fixable authoring problem (unwired port, missing graph, cycle...). */
    public static final class CompileError extends RuntimeException {
        public CompileError(String message) {
            super(message);
        }
    }

    public record Result(CompiledEffect effect,
                         Map<IResourcePath, FullscreenGraphRuntime.Entry> passEntries) {}

    private static final Set<TypeHandle> LERPABLE_TYPES = Set.of(
            TypeHandles.FLOAT, RenderTypeGraphTypes.VEC2, RenderTypeGraphTypes.VEC3,
            RenderTypeGraphTypes.VEC4, TypeHandles.COLOR);

    private RenderGraphCompiler() {}

    /** Compile {@code graph}; {@code source} is stamped on the effect (null for editor validation). */
    public static Result compile(@Nullable IResourcePath source, RenderGraph graph) {
        var outputModel = graph.getOutputNodeModel();
        if (outputModel == null) throw new CompileError("effect has no output node");
        var outputNode = (OutputNode) ((ICustomNodeModel) outputModel).getNode();

        var colorPort = outputModel.getInputsById().get(OutputNode.COLOR_PORT);
        if (colorPort == null || !colorPort.isConnected()) {
            throw new CompileError("connect a Pass to the effect output");
        }

        var state = new State();
        var outputRef = state.resolveTextureSource(colorPort);
        if (outputRef.source() == ResourceRef.Source.SCENE_DEPTH) {
            throw new CompileError("the effect output cannot be scene depth");
        }
        if (outputRef.source() == ResourceRef.Source.SCENE_COLOR) {
            // scene color wired straight through (the fresh-graph starter): a valid no-op effect
            return new Result(new CompiledEffect(source, outputNode.priority(), outputNode.autoBlend(),
                    buildSchema(graph), List.of(), List.of(), -1), state.passEntries);
        }

        // resource lifetimes: written at its own pass, alive until its last consumer
        int passCount = state.passes.size();
        int[] lastUse = new int[passCount];
        for (int i = 0; i < passCount; i++) lastUse[i] = i;
        for (int i = 0; i < passCount; i++) {
            for (var ref : state.passes.get(i).textures.values()) {
                if (ref.source() == ResourceRef.Source.RESOURCE) {
                    lastUse[ref.resource()] = Math.max(lastUse[ref.resource()], i);
                }
            }
        }

        var resources = new ArrayList<CompiledEffect.ResourceDesc>(passCount);
        var passes = new ArrayList<CompiledEffect.CompiledPass>(passCount);
        for (int i = 0; i < passCount; i++) {
            var build = state.passes.get(i);
            resources.add(new CompiledEffect.ResourceDesc(build.size, build.format, i, lastUse[i],
                    "pass%d_%s".formatted(i, build.debugName)));
            passes.add(new CompiledEffect.CompiledPass(build.graphPath, build.customShader,
                    build.textures, build.params, i));
        }

        var effect = new CompiledEffect(
                source,
                outputNode.priority(),
                outputNode.autoBlend(),
                buildSchema(graph),
                List.copyOf(resources),
                List.copyOf(passes),
                outputRef.resource());
        return new Result(effect, state.passEntries);
    }

    /** The blendable parameter schema: every INPUT blackboard variable. */
    private static List<CompiledEffect.ParamSpec> buildSchema(RenderGraph graph) {
        var schema = new ArrayList<CompiledEffect.ParamSpec>();
        for (var declaration : graph.graphModel.getGraphVariableModels()) {
            if (declaration == null || declaration.getVariableKind() == VariableKind.OUTPUT) continue;
            var defaultValue = declaration.tryGetDefaultValue(declaration.getDataType()).result().orElse(null);
            schema.add(new CompiledEffect.ParamSpec(declaration.getName(), defaultValue,
                    LERPABLE_TYPES.contains(declaration.getDataTypeHandle())));
        }
        return List.copyOf(schema);
    }

    /** Mutable walk state: memoized pass visits + the topo-ordered pass builds. */
    private static final class State {
        final Map<NodeModel, Integer> passIndices = new HashMap<>();
        final Set<NodeModel> visiting = new HashSet<>();
        final List<PassBuild> passes = new ArrayList<>();
        final Map<IResourcePath, FullscreenGraphRuntime.Entry> passEntries = new LinkedHashMap<>();

        /** Resolve what feeds a TEXTURE input port, recursing into upstream passes first. */
        ResourceRef resolveTextureSource(PortModel inputPort) {
            if (!(inputPort.getFirstConnectedPort() instanceof PortModel sourcePort)
                    || !(sourcePort.getNodeModel() instanceof NodeModel sourceModel)) {
                throw new CompileError("texture input '%s' is not connected".formatted(inputPort.getName()));
            }
            var sourceNode = nodeOf(sourceModel);
            if (sourceNode instanceof SceneColorInputNode) return ResourceRef.SCENE_COLOR_REF;
            if (sourceNode instanceof SceneDepthInputNode) return ResourceRef.SCENE_DEPTH_REF;
            if (sourceNode instanceof PassNode pass) return ResourceRef.of(visitPass(sourceModel, pass));
            throw new CompileError("texture input '%s' has an unsupported source".formatted(inputPort.getName()));
        }

        /** Post-order DFS: dependencies get lower indices, so resource ids follow execution order. */
        int visitPass(NodeModel model, PassNode node) {
            var memo = passIndices.get(model);
            if (memo != null) return memo;
            if (!visiting.add(model)) throw new CompileError("pass graph contains a cycle");
            try {
                var build = buildPass(model, node);
                int index = passes.size();
                passes.add(build);
                passIndices.put(model, index);
                return index;
            } finally {
                visiting.remove(model);
            }
        }

        private PassBuild buildPass(NodeModel model, PassNode node) {
            if (node.passSource().type() == PassSource.Type.CUSTOM_SHADER) {
                return buildCustomShaderPass(model, node);
            }
            var graphPath = node.graphPath();
            if (graphPath == null) throw new CompileError("a Pass has no fullscreen graph selected");
            var entry = FullscreenGraphRuntime.get(graphPath);
            if (entry == null || !entry.isValid() || entry.getGraph() == null) {
                throw new CompileError("pass graph '%s' is broken: %s".formatted(
                        graphPath.getResourceName(), entry == null ? "not found" : entry.getErrorMessage()));
            }
            passEntries.put(graphPath, entry);
            var compiled = entry.getCompiled();

            var build = new PassBuild();
            build.graphPath = graphPath;
            build.format = node.targetFormat();
            build.debugName = graphPath.getResourceName();

            var passSize = node.passSize();
            ResourceRef firstTextureRef = null;
            ResourceRef sizeInputRef = null;
            var sizeInputName = passSize.inputPort();
            for (var declaration : entry.getGraph().graphModel.getGraphVariableModels()) {
                if (declaration == null) continue;
                var name = declaration.getName();
                var port = model.getInputsById().get(name);
                if (compiled.variableSamplers().containsKey(name)) {
                    if (port == null) {
                        throw new CompileError(("pass '%s' is missing port '%s' — its fullscreen graph "
                                + "changed; re-select the graph on the node").formatted(build.debugName, name));
                    }
                    var ref = resolveTextureSource(port);
                    build.textures.put(compiled.variableSamplers().get(name), ref);
                    if (firstTextureRef == null) firstTextureRef = ref;
                    if (name.equals(sizeInputName)) sizeInputRef = ref;
                } else if (compiled.uniformFields().containsKey(name) && port != null) {
                    var binding = resolveValueBinding(port);
                    // null = no override, keep the fullscreen graph's own default
                    if (binding != null) build.params.put(name, binding);
                }
            }

            build.size = resolveSize(passSize, sizeInputRef, firstTextureRef, build.debugName);
            return build;
        }

        /** A hand-written core shader pass: the interface comes from the shader json — every
         *  sampler is a required texture port, every float/vec uniform an optional value port. */
        private PassBuild buildCustomShaderPass(NodeModel model, PassNode node) {
            var location = node.passSource().shader();
            if (location.isEmpty()) throw new CompileError("a Pass has no custom shader set");
            var info = CustomShaderPass.getInfo(location);
            if (info == null) {
                throw new CompileError("custom pass shader '%s' is missing or unreadable".formatted(location));
            }

            var build = new PassBuild();
            build.customShader = location;
            build.format = node.targetFormat();
            var slash = location.lastIndexOf('/');
            build.debugName = slash >= 0 ? location.substring(slash + 1) : location;

            var passSize = node.passSize();
            ResourceRef firstTextureRef = null;
            ResourceRef sizeInputRef = null;
            var sizeInputName = passSize.inputPort();
            for (var sampler : info.samplers()) {
                var port = model.getInputsById().get(sampler);
                if (port == null) {
                    throw new CompileError(("pass '%s' is missing port '%s' — its shader changed; "
                            + "re-select the shader on the node").formatted(build.debugName, sampler));
                }
                var ref = resolveTextureSource(port);
                build.textures.put(sampler, ref);
                if (firstTextureRef == null) firstTextureRef = ref;
                if (sampler.equals(sizeInputName)) sizeInputRef = ref;
            }
            for (var uniform : info.uniforms()) {
                var port = model.getInputsById().get(uniform.name());
                if (port == null) continue;
                var binding = resolveValueBinding(port);
                // null = no override, keep the shader json's own default
                if (binding != null) build.params.put(uniform.name(), binding);
            }
            build.size = resolveSize(passSize, sizeInputRef, firstTextureRef, build.debugName);
            return build;
        }

        private SizeSpec resolveSize(PassSize passSize, @Nullable ResourceRef sizeInputRef,
                                     @Nullable ResourceRef firstTextureRef, String debugName) {
            return switch (passSize.mode()) {
                case SCREEN_RELATIVE -> SizeSpec.screen(passSize.scale());
                case ABSOLUTE -> SizeSpec.absolute(passSize.width(), passSize.height());
                case INPUT_RELATIVE -> {
                    var reference = sizeInputRef != null ? sizeInputRef : firstTextureRef;
                    if (reference == null) {
                        throw new CompileError(("pass '%s' uses INPUT_RELATIVE sizing but has no texture "
                                + "input to derive from").formatted(debugName));
                    }
                    // scene inputs are screen-sized, so relative-to-scene degrades to screen-relative
                    yield reference.source() == ResourceRef.Source.RESOURCE
                            ? SizeSpec.relativeTo(reference.resource(), passSize.scale())
                            : SizeSpec.screen(passSize.scale());
                }
            };
        }

        /** A value port: unconnected → inline constant; connected → must be a blackboard parameter. */
        @Nullable
        private ValueBinding resolveValueBinding(PortModel port) {
            if (!port.isConnected()) {
                Object constant;
                try {
                    constant = port.tryGetValue(Object.class).result().orElse(null);
                } catch (RuntimeException e) {
                    constant = null;
                }
                return constant == null ? null : new ValueBinding.Constant(constant);
            }
            if (port.getFirstConnectedPort() instanceof PortModel sourcePort
                    && sourcePort.getNodeModel() instanceof NodeModel sourceModel) {
                if (nodeOf(sourceModel) instanceof EffectWeightNode) {
                    return ValueBinding.EffectWeight.INSTANCE;
                }
                IVariableNode variableNode = null;
                if (sourceModel instanceof IVariableNode direct) variableNode = direct;
                else if (nodeOf(sourceModel) instanceof IVariableNode wrapped) variableNode = wrapped;
                if (variableNode != null
                        && variableNode.getVariable() instanceof VariableDeclarationModelBase declaration) {
                    return new ValueBinding.ParamRef(declaration.getName());
                }
            }
            throw new CompileError(("value input '%s' must be an inline constant, a blackboard "
                    + "parameter, or the Effect Weight node (CPU math between them is not supported yet)")
                    .formatted(port.getName()));
        }
    }

    private static final class PassBuild {
        @Nullable
        IResourcePath graphPath;
        @Nullable
        String customShader;
        TargetFormat format = TargetFormat.RGBA16F;
        SizeSpec size = SizeSpec.screen(1f);
        String debugName = "";
        final Map<String, ResourceRef> textures = new LinkedHashMap<>();
        final Map<String, ValueBinding> params = new LinkedHashMap<>();

        PassBuild() {
        }
    }

    @Nullable
    private static Node nodeOf(NodeModel model) {
        return model instanceof ICustomNodeModel custom ? custom.getNode() : null;
    }
}
