package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.FX;

/**
 * FX serialization round-trip coverage (the user-facing .fx format must stay stable across the
 * 26.1 migration — see the migration plan's serialization notes). The FX object registries and
 * payload accessors load on every dist, so these run fully on the headless game-test server.
 */
public final class FXSerializationGameTest {

    private static final String REGISTRY_ROUND_TRIP = "fx_objects_registry_round_trip";
    private static final String EMPTY_FX_ROUND_TRIP = "empty_fx_round_trip";
    private static final String LEGACY_SHADER_SAMPLERS = "legacy_custom_shader_sampler_format";
    private static final String LEGACY_GRADIENT = "legacy_gradient_color_format";

    private FXSerializationGameTest() {}

    public static void registerFunctions() {
        PhotonGameTests.registerFunction(REGISTRY_ROUND_TRIP, helper -> {
            // every registered FX object type: create → serialize → deserialize → re-serialize,
            // asserting the wrapper round-trips to an identical tag (deep CompoundTag equality)
            for (var name : PhotonRegistries.FX_OBJECTS.registry().keySet()) {
                var type = PhotonRegistries.FX_OBJECTS.get(name);
                if (type == null) continue;
                var created = type.create();
                // encode/parse via the codec directly so failures surface their DataResult errors
                var encoded = com.lowdragmc.photon.client.gameobject.IFXObject.CODEC
                        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, created);
                if (encoded.error().isPresent() || encoded.result().isEmpty()) {
                    helper.fail(net.minecraft.network.chat.Component.literal("FX object '" + name + "' failed to serialize: "
                            + encoded.error().map(Object::toString).orElse("empty result")));
                    return;
                }
                var first = encoded.result().get();
                var parsed = com.lowdragmc.photon.client.gameobject.IFXObject.CODEC
                        .parse(net.minecraft.nbt.NbtOps.INSTANCE, first);
                if (parsed.error().isPresent() || parsed.result().isEmpty()) {
                    helper.fail(net.minecraft.network.chat.Component.literal("FX object '" + name + "' failed to deserialize: "
                            + parsed.error().map(Object::toString).orElse("empty result") + "\ntag=" + first));
                    return;
                }
                var reloaded = parsed.result().get();
                var second = reloaded.serializeWrapper();
                if (!first.equals(second)) {
                    helper.fail(net.minecraft.network.chat.Component.literal(
                            "FX object '" + name + "' round-trip mismatch:\nfirst= " + first + "\nsecond=" + second));
                    return;
                }
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(LEGACY_SHADER_SAMPLERS, helper -> {
            // 1.21 wrote CustomShaderMaterial's sampler textures as bare lists; deserializing that
            // legacy shape must land on the same data as the current {curves|gradients:[...]} form
            var material = new com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial();
            material.curveTexture.addCurve(new com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve());
            material.gradientTexture.addGradient(new com.lowdragmc.lowdraglib2.math.GradientColor());
            var current = material.serializeWrapper();
            if (current == null) {
                helper.fail(net.minecraft.network.chat.Component.literal("custom_shader material failed to serialize"));
                return;
            }
            var legacy = current.copy();
            var data = legacy.getCompoundOrEmpty("data");
            data.put("curveTexture", data.getCompoundOrEmpty("curveTexture").getListOrEmpty("curves"));
            data.put("gradientTexture", data.getCompoundOrEmpty("gradientTexture").getListOrEmpty("gradients"));
            var reloaded = com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial.deserializeWrapper(legacy);
            var second = reloaded.serializeWrapper();
            if (!current.equals(second)) {
                helper.fail(net.minecraft.network.chat.Component.literal(
                        "legacy sampler round-trip mismatch:\nexpected=" + current + "\nactual=" + second));
                return;
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(LEGACY_GRADIENT, helper -> {
            // 1.21 stored gradient points as FLAT float lists (a=[t,a,...], rgb=[t,r,g,b,...]);
            // decoding that shape must land on real points (crashed the editor as an empty gradient)
            var legacy = new net.minecraft.nbt.CompoundTag();
            var a = new net.minecraft.nbt.ListTag();
            for (float f : new float[]{0f, 1f, 1f, 0.5f}) a.add(net.minecraft.nbt.FloatTag.valueOf(f));
            var rgb = new net.minecraft.nbt.ListTag();
            for (float f : new float[]{0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f}) rgb.add(net.minecraft.nbt.FloatTag.valueOf(f));
            legacy.put("a", a);
            legacy.put("rgb", rgb);
            var gradient = new com.lowdragmc.lowdraglib2.math.GradientColor();
            com.lowdragmc.photon.utils.ValueIONbt.fromTag(gradient, Platform.getFrozenRegistry(), legacy);
            if (gradient.getAP().size() != 2 || gradient.getRgbP().size() != 2) {
                helper.fail(net.minecraft.network.chat.Component.literal(
                        "legacy gradient decode produced " + gradient.getAP().size() + " alpha / "
                                + gradient.getRgbP().size() + " rgb points (expected 2/2)"));
                return;
            }
            if (Math.abs(gradient.getAlpha(1f) - 0.5f) > 1e-4) {
                helper.fail(net.minecraft.network.chat.Component.literal(
                        "legacy gradient alpha(1)=" + gradient.getAlpha(1f) + " (expected 0.5)"));
                return;
            }
            // empty gradients must degrade, not throw (the crash path from the editor tick)
            var empty = new com.lowdragmc.lowdraglib2.math.GradientColor();
            com.lowdragmc.photon.utils.ValueIONbt.fromTag(empty, Platform.getFrozenRegistry(),
                    new net.minecraft.nbt.CompoundTag());
            empty.getColor(0.5f);
            helper.succeed();
        });

        PhotonGameTests.registerFunction(EMPTY_FX_ROUND_TRIP, helper -> {
            var fx = new FX();
            var provider = Platform.getFrozenRegistry();
            var tag = fx.serializeNBT(provider);
            var reloaded = new FX();
            reloaded.deserializeNBT(provider, tag);
            var second = reloaded.serializeNBT(provider);
            if (!tag.equals(second)) {
                helper.fail(net.minecraft.network.chat.Component.literal(
                        "FX round-trip mismatch:\nfirst= " + tag + "\nsecond=" + second));
                return;
            }
            helper.succeed();
        });
    }

    public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent event,
                                net.minecraft.core.Holder<net.minecraft.gametest.framework.TestEnvironmentDefinition<?>> environment) {
        var testData = PhotonGameTests.defaultTestData(environment);
        PhotonGameTests.registerFunctionTest(event, REGISTRY_ROUND_TRIP, testData);
        PhotonGameTests.registerFunctionTest(event, EMPTY_FX_ROUND_TRIP, testData);
        PhotonGameTests.registerFunctionTest(event, LEGACY_SHADER_SAMPLERS, testData);
        PhotonGameTests.registerFunctionTest(event, LEGACY_GRADIENT, testData);
    }
}
