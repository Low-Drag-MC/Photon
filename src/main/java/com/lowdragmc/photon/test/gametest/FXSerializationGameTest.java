package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomData;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRColorFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.utils.ValueIONbt;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * FX serialization round-trip coverage (the user-facing .fx format must stay stable across the
 * 26.1 migration — see the migration plan's serialization notes). The FX object registries and
 * payload accessors load on every dist, so these run fully on the headless game-test server.
 */
public final class FXSerializationGameTest {

    private static final String REGISTRY_ROUND_TRIP = "fx_objects_registry_round_trip";
    private static final String NUMBER_FUNCTION_ROUND_TRIP = "number_functions_registry_round_trip";
    private static final String LEGACY_LDR_COLOR_UPGRADE = "legacy_ldr_color_stream_upgrade";
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
                var encoded = IFXObject.CODEC
                        .encodeStart(NbtOps.INSTANCE, created);
                if (encoded.error().isPresent() || encoded.result().isEmpty()) {
                    helper.fail(Component.literal("FX object '" + name + "' failed to serialize: "
                            + encoded.error().map(Object::toString).orElse("empty result")));
                    return;
                }
                var first = encoded.result().get();
                var parsed = IFXObject.CODEC
                        .parse(NbtOps.INSTANCE, first);
                if (parsed.error().isPresent() || parsed.result().isEmpty()) {
                    helper.fail(Component.literal("FX object '" + name + "' failed to deserialize: "
                            + parsed.error().map(Object::toString).orElse("empty result") + "\ntag=" + first));
                    return;
                }
                var reloaded = parsed.result().get();
                var second = reloaded.serializeWrapper();
                if (!first.equals(second)) {
                    helper.fail(Component.literal(
                            "FX object '" + name + "' round-trip mismatch:\nfirst= " + first + "\nsecond=" + second));
                    return;
                }
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(NUMBER_FUNCTION_ROUND_TRIP, helper -> {
            // The same guarantee REGISTRY_ROUND_TRIP gives FX objects, for the functions that carry the
            // authored values inside them. Worth its own test since the HDR set stores an HDRColor,
            // whose @Persisted support lives in LDLib2's accessor registry rather than in Photon — a
            // missing accessor there would drop every colour to its default without failing anything.
            for (var holder : PhotonRegistries.NUMBER_FUNCTIONS) {
                var function = holder.value().get();
                var name = holder.clazz().getSimpleName();
                var first = function.serializeWrapper();
                var second = NumberFunction.deserializeWrapper(first).serializeWrapper();
                if (!first.equals(second)) {
                    helper.fail(Component.literal("number function '" + name + "' round-trip mismatch:"
                            + "\nfirst= " + first + "\nsecond=" + second));
                    return;
                }
            }
            // and a non-default value survives, which an empty round-trip cannot show
            var hdr = new HDRConstantColor(new HDRColor(0.25f, 0.5f, 0.75f, 1f, 4f));
            var reloaded = NumberFunction.deserializeWrapper(hdr.serializeWrapper());
            if (!(reloaded instanceof HDRConstantColor loaded) || !loaded.getColor().equals(hdr.getColor())) {
                helper.fail(Component.literal("HDR colour did not survive serialization: expected "
                        + hdr.getColor() + ", got " + reloaded));
                return;
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(LEGACY_LDR_COLOR_UPGRADE, helper -> {
            // A pre-HDR custom-data COLOR stream holds an LDR function. Loading one must hand back the
            // HDR counterpart (the type dropdown only offers those now) with the colour preserved.
            var legacy = new CompoundTag();
            legacy.putString("type", CustomData.Type.COLOR.name());
            legacy.putInt("channelCount", 1);
            var channels = new ListTag();
            channels.add(new Color(0xFF804020).serializeWrapper());
            legacy.put("channels", channels);

            var loaded = CustomData.fromNBT(legacy);
            var channel = loaded.getChannels().getFirst();
            if (!(channel instanceof HDRColorFunction)) {
                helper.fail(Component.literal(
                        "legacy LDR colour stream was not upgraded: " + channel.getClass().getSimpleName()));
                return;
            }
            var sampled = new float[4];
            loaded.sampleInto(sampled, 0f, () -> 0f);
            // the upgrade is value-preserving: 0x80/255, 0x40/255, 0x20/255 at alpha 1
            var expected = new float[]{0x80 / 255f, 0x40 / 255f, 0x20 / 255f, 1f};
            for (int i = 0; i < expected.length; i++) {
                if (Math.abs(sampled[i] - expected[i]) > 1e-3) {
                    helper.fail(Component.literal("upgraded colour changed value: expected "
                            + java.util.Arrays.toString(expected) + ", got " + java.util.Arrays.toString(sampled)));
                    return;
                }
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(LEGACY_SHADER_SAMPLERS, helper -> {
            // 1.21 wrote CustomShaderMaterial's sampler textures as bare lists; deserializing that
            // legacy shape must land on the same data as the current {curves|gradients:[...]} form
            var material = new CustomShaderMaterial();
            material.curveTexture.addCurve(new Curve());
            material.gradientTexture.addGradient(new GradientColor());
            var current = material.serializeWrapper();
            if (current == null) {
                helper.fail(Component.literal("custom_shader material failed to serialize"));
                return;
            }
            var legacy = current.copy();
            var data = legacy.getCompoundOrEmpty("data");
            data.put("curveTexture", data.getCompoundOrEmpty("curveTexture").getListOrEmpty("curves"));
            data.put("gradientTexture", data.getCompoundOrEmpty("gradientTexture").getListOrEmpty("gradients"));
            var reloaded = IMaterial.deserializeWrapper(legacy);
            var second = reloaded.serializeWrapper();
            if (!current.equals(second)) {
                helper.fail(Component.literal(
                        "legacy sampler round-trip mismatch:\nexpected=" + current + "\nactual=" + second));
                return;
            }
            helper.succeed();
        });

        PhotonGameTests.registerFunction(LEGACY_GRADIENT, helper -> {
            // 1.21 stored gradient points as FLAT float lists (a=[t,a,...], rgb=[t,r,g,b,...]);
            // decoding that shape must land on real points (crashed the editor as an empty gradient)
            var legacy = new CompoundTag();
            var a = new ListTag();
            for (float f : new float[]{0f, 1f, 1f, 0.5f}) a.add(FloatTag.valueOf(f));
            var rgb = new ListTag();
            for (float f : new float[]{0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f}) rgb.add(FloatTag.valueOf(f));
            legacy.put("a", a);
            legacy.put("rgb", rgb);
            var gradient = new GradientColor();
            ValueIONbt.fromTag(gradient, Platform.getFrozenRegistry(), legacy);
            if (gradient.getAP().size() != 2 || gradient.getRgbP().size() != 2) {
                helper.fail(Component.literal(
                        "legacy gradient decode produced " + gradient.getAP().size() + " alpha / "
                                + gradient.getRgbP().size() + " rgb points (expected 2/2)"));
                return;
            }
            if (Math.abs(gradient.getAlpha(1f) - 0.5f) > 1e-4) {
                helper.fail(Component.literal(
                        "legacy gradient alpha(1)=" + gradient.getAlpha(1f) + " (expected 0.5)"));
                return;
            }
            // empty gradients must degrade, not throw (the crash path from the editor tick)
            var empty = new GradientColor();
            ValueIONbt.fromTag(empty, Platform.getFrozenRegistry(),
                    new CompoundTag());
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
                helper.fail(Component.literal(
                        "FX round-trip mismatch:\nfirst= " + tag + "\nsecond=" + second));
                return;
            }
            helper.succeed();
        });
    }

    public static void register(RegisterGameTestsEvent event,
                                Holder<TestEnvironmentDefinition<?>> environment) {
        var testData = PhotonGameTests.defaultTestData(environment);
        PhotonGameTests.registerFunctionTest(event, REGISTRY_ROUND_TRIP, testData);
        PhotonGameTests.registerFunctionTest(event, NUMBER_FUNCTION_ROUND_TRIP, testData);
        PhotonGameTests.registerFunctionTest(event, LEGACY_LDR_COLOR_UPGRADE, testData);
        PhotonGameTests.registerFunctionTest(event, EMPTY_FX_ROUND_TRIP, testData);
        PhotonGameTests.registerFunctionTest(event, LEGACY_SHADER_SAMPLERS, testData);
        PhotonGameTests.registerFunctionTest(event, LEGACY_GRADIENT, testData);
    }
}
