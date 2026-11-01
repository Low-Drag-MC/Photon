package com.lowdragmc.photon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every English text has a Chinese one: a missing key shows English in the Chinese UI, as Animated glTF did. */
class LangParityTest {

    private static JsonObject lang(String name) throws IOException {
        try (var in = new InputStreamReader(Objects.requireNonNull(
                LangParityTest.class.getResourceAsStream("/assets/photon/lang/" + name + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        }
    }

    @Test
    void everyEnglishKeyIsTranslated() throws IOException {
        var en = lang("en_us");
        var zh = lang("zh_cn");
        var missing = en.keySet().stream().filter(key -> !zh.has(key)).toList();
        assertTrue(missing.isEmpty(), "zh_cn.json lacks " + missing);
    }
}
