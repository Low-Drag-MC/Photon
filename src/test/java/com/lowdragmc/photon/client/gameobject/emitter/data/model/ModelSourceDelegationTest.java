package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceMeshSource} is a live reference to a mesh in the library, and an <b>imported</b> glb is
 * always behind one. Every capability of the referenced source therefore has to be forwarded — a default
 * it inherits instead silently answers "no" on behalf of a source that would have said yes.
 *
 * <p>⚠️ This is not hypothetical: {@code vertexAnimation()} was missed, and an imported animated glb
 * played one pose for every particle while the same source used inline spread them correctly. Reflection
 * rather than a list of names, so the next capability added to {@link IModelSource} has to be forwarded or
 * explicitly exempted here.</p>
 */
class ModelSourceDelegationTest {

    /**
     * Defaults that are genuinely the reference's own business rather than the referent's: serialization
     * describes THIS object, and {@code buildConfigurator} builds the reference's own UI.
     */
    private static final Set<String> NOT_A_CAPABILITY = Set.of(
            "serializeWrapper", "serializeAdditionalNBT", "deserializeAdditionalNBT",
            "buildConfigurator", "createConfigurator", "getRegistryHolderOptional");

    @Test
    void everyCapabilityOfTheReferencedSourceIsForwarded() {
        var overridden = Arrays.stream(ResourceMeshSource.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        var missed = Arrays.stream(IModelSource.class.getDeclaredMethods())
                .filter(Method::isDefault)
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .filter(name -> !NOT_A_CAPABILITY.contains(name))
                .filter(name -> !overridden.contains(name))
                .sorted()
                .toList();

        assertTrue(missed.isEmpty(), "ResourceMeshSource inherits IModelSource's default for "
                + missed + " instead of forwarding to the mesh it references. Either override it, or add "
                + "it to NOT_A_CAPABILITY with a reason.");
    }

    /** The one that was actually missed, named outright so a regression reads unambiguously. */
    @Test
    void vertexAnimationIsForwarded() throws NoSuchMethodException {
        var declared = ResourceMeshSource.class.getDeclaredMethod("vertexAnimation");
        assertTrue(declared.getDeclaringClass() == ResourceMeshSource.class,
                "an imported animated glb would draw every particle in one pose");
    }
}
