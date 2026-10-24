package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One variant's std140 {@code KilaMaterial} block, built from what its modules declare (26.1 has no free uniforms).
 * Vectors first, scalars last, so nothing is padded; the members reach GLSL as {@link #MEMBERS_DEFINE}.
 */
public final class KilaBlock {
    public static final String NAME = "KilaMaterial";
    /** ⚠️ {@code photon:kila_lib.glsl} declares {@code uniform KilaMaterial { KILA_BLOCK };} */
    public static final String MEMBERS_DEFINE = "KILA_BLOCK";

    public enum Type {
        VEC4("vec4", 16), IVEC4("ivec4", 16), FLOAT("float", 4), INT("int", 4);

        public final String glsl;
        public final int size;

        Type(String glsl, int size) {
            this.glsl = glsl;
            this.size = size;
        }

        public boolean isInt() {
            return this == IVEC4 || this == INT;
        }

        public int components() {
            return size / 4;
        }
    }

    public record Member(String name, Type type) {
    }

    public record Field(String name, Type type, int offset) {
    }

    private final List<Field> fields;
    private final Map<String, Field> byName;
    private final int size;
    private final String glsl;

    /** {@code members} in the order they were declared; the vectors are moved ahead of the scalars. */
    public KilaBlock(Collection<Member> members) {
        var ordered = new ArrayList<Member>(members.size());
        for (var member : members) if (member.type().size == 16) ordered.add(member);
        for (var member : members) if (member.type().size != 16) ordered.add(member);
        var fields = new ArrayList<Field>(ordered.size());
        var byName = new HashMap<String, Field>();
        var glsl = new StringBuilder();
        int offset = 0;
        for (var member : ordered) {
            var field = new Field(member.name(), member.type(), offset);
            if (byName.put(field.name(), field) != null) {
                throw new IllegalArgumentException("declared twice: " + field.name());
            }
            fields.add(field);
            offset += member.type().size;
            if (!glsl.isEmpty()) glsl.append(' ');
            glsl.append(member.type().glsl).append(' ').append(member.name()).append(';');
        }
        this.fields = Collections.unmodifiableList(fields);
        this.byName = byName;
        // std140 rounds a block up to a whole vec4
        this.size = Math.max(16, (offset + 15) / 16 * 16);
        this.glsl = glsl.toString();
    }

    /** Every member, in block order. */
    public List<Field> fields() {
        return fields;
    }

    /** null for a name the block does not carry. */
    @Nullable
    public Field field(String name) {
        return byName.get(name);
    }

    /** The block's size in bytes. */
    public int size() {
        return size;
    }

    /** The members as GLSL declarations, in block order. */
    public String glsl() {
        return glsl;
    }

    /** {@code KILA_BLOCK <members>}, as a flag holding a space (see {@code KilaLayout.Builder#alias}). */
    public String define() {
        return MEMBERS_DEFINE + " " + glsl;
    }
}
