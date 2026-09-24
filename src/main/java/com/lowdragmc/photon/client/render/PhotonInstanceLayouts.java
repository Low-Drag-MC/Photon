package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.gameobject.emitter.data.AdditionalGPUDataSetting;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vertex layouts of Photon's instanced draws: a base mesh on binding 0 and one record per instance on binding 1.
 * <p>
 * Element names are the GLSL input names of {@code include/particle.glsl}; both backends bind attributes by name.
 * Keep in lockstep with {@code particle.glsl} and the record fillers ({@code TileParticleRenderer} etc.). The
 * resulting locations reproduce the 1.21 numbering custom shaders address the attribute tail by.
 */
public final class PhotonInstanceLayouts {

    /** @param offsetFloats offset inside the record, in 4-byte units */
    public record Attrib(String name, GpuFormat format, int offsetFloats) {
        public int floats() {
            return format.blockSize() / Float.BYTES;
        }
    }

    /** One additional-GPU-data tail slot and the 1.21 location a custom shader addresses it by. */
    public record TailSlot(int location, GpuFormat format, int offsetFloats) {
    }

    /** @param strideFloats floats per instance record, including the attribute tail */
    public record Layout(String name, List<Attrib> base, int baseStrideFloats,
                         List<Attrib> instance, int strideFloats, List<TailSlot> tail) {

        /**
         * Appends the additional-GPU-data tail to each record; which slots become elements is decided per shader
         * ({@link #instanceFormat(Map)}).
         */
        public Layout withInstanceTail(List<AdditionalGPUDataSetting.TailAttrib> attribs, int tailFloats) {
            if (tailFloats == 0) {
                return this;
            }
            var slots = new ArrayList<TailSlot>();
            for (var attrib : attribs) {
                slots.add(new TailSlot(attrib.location(), FLOAT_FORMATS[attrib.floats() - 1], attrib.offsetFloats()));
            }
            return new Layout(name, base, baseStrideFloats, instance, strideFloats + tailFloats, List.copyOf(slots));
        }

        /** Elements before the tail. */
        public int elementCount() {
            return base.size() + instance.size();
        }

        public Set<Integer> tailLocations() {
            var locations = new HashSet<Integer>();
            for (var slot : tail) {
                locations.add(slot.location());
            }
            return locations;
        }

        public VertexFormat baseFormat() {
            return BASE_FORMATS.computeIfAbsent(this.name, key -> {
                var builder = VertexFormat.builder(0);
                for (var attrib : base) {
                    builder.addAttribute(attrib.name(), attrib.format());
                }
                return builder.build();
            });
        }

        public VertexFormat instanceFormat() {
            return instanceFormat(Map.of());
        }

        /**
         * The instance binding with an element per tail slot the shader reads ({@code tailInputs}: location →
         * input name). Vulkan counts matched inputs, so only declared slots may be present. The last element is
         * widened to the record end so the stride covers unread tail floats.
         */
        public VertexFormat instanceFormat(Map<Integer, String> tailInputs) {
            var elements = new ArrayList<Attrib>(instance);
            for (var slot : tail) {
                var input = tailInputs.get(slot.location());
                if (input != null) {
                    elements.add(new Attrib(input, slot.format(), slot.offsetFloats()));
                }
            }
            var key = new StringBuilder(name).append('@').append(strideFloats);
            for (int i = instance.size(); i < elements.size(); i++) {
                var attrib = elements.get(i);
                key.append(';').append(attrib.name()).append(':').append(attrib.format()).append('+')
                        .append(attrib.offsetFloats());
            }
            return INSTANCE_FORMATS.computeIfAbsent(key.toString(), k -> {
                var builder = VertexFormat.builder(1);
                var recordBytes = strideFloats * Float.BYTES;
                var widest = 0;
                for (int i = 1; i < elements.size(); i++) {
                    if (end(elements.get(i)) >= end(elements.get(widest))) widest = i;
                }
                for (int i = 0; i < elements.size(); i++) {
                    var attrib = elements.get(i);
                    var offset = attrib.offsetFloats() * Float.BYTES;
                    var span = i == widest ? recordBytes - offset : attrib.format().blockSize();
                    builder.addAttribute(attrib.name(), offset, span, attrib.format(), 1);
                }
                return builder.build();
            });
        }

        private static int end(Attrib attrib) {
            return attrib.offsetFloats() + attrib.floats();
        }
    }

    private static final Map<String, VertexFormat> BASE_FORMATS = new ConcurrentHashMap<>();
    private static final Map<String, VertexFormat> INSTANCE_FORMATS = new ConcurrentHashMap<>();

    private static final GpuFormat F1 = GpuFormat.R32_FLOAT;
    private static final GpuFormat F2 = GpuFormat.RG32_FLOAT;
    private static final GpuFormat F3 = GpuFormat.RGB32_FLOAT;
    private static final GpuFormat F4 = GpuFormat.RGBA32_FLOAT;
    private static final GpuFormat I1 = GpuFormat.R32_SINT;
    private static final GpuFormat I2 = GpuFormat.RG32_SINT;
    private static final GpuFormat[] FLOAT_FORMATS = {F1, F2, F3, F4};

    private static List<Attrib> attribs(Object... nameAndFormat) {
        var list = new ArrayList<Attrib>();
        var offset = 0;
        for (int i = 0; i < nameAndFormat.length; i += 2) {
            var format = (GpuFormat) nameAndFormat[i + 1];
            list.add(new Attrib((String) nameAndFormat[i], format, offset));
            offset += format.blockSize() / Float.BYTES;
        }
        return List.copyOf(list);
    }

    private static int floats(List<Attrib> attribs) {
        var last = attribs.getLast();
        return last.offsetFloats() + last.floats();
    }

    private static Layout layout(String name, List<Attrib> base, List<Attrib> instance) {
        return new Layout(name, base, floats(base), instance, floats(instance), List.of());
    }

    public static final Layout TILE = layout("tile",
            attribs("aPos", F3),
            attribs("iPos", F3, "iSize", F2, "iScale", F3, "iRot", F4, "iColor", F4, "iUV", F4, "iLight", I1));

    public static final Layout MODEL = layout("model",
            attribs("aPos", F3, "aUV", F2, "aNormal", F3, "aBrightness", F1),
            attribs("iPos", F3, "iScale", F3, "iRot", F4, "iColor", F4, "iLight", I1));

    /** Brightness moves into {@code aNormal.w} so the tangent takes its slot and later locations stay put. */
    public static final Layout MODEL_TANGENT = layout("model_tangent",
            attribs("aPos", F3, "aUV", F2, "aNormal", F4, "aTangent", F4),
            attribs("iPos", F3, "iScale", F3, "iRot", F4, "iColor", F4, "iLight", I1));

    public static final Layout TRAIL = layout("trail",
            attribs("aPos", F2),
            attribs("iSeg", I2, "iSegV", F2));

    public static final Layout ARA = layout("ara",
            attribs("aPos", F2),
            attribs("iSeg", I1, "iSegV", F2));

    public static final Layout ARA_TUBE = layout("ara_tube",
            attribs("aPos", F4),
            attribs("iSeg", I1));

    /** Beam positions are eye-relative. */
    public static final Layout BEAM = layout("beam",
            attribs("aPos", F2),
            attribs("iStart", F4, "iEnd", F3, "iColor", F4, "iUV", F4, "iLight", I1));

    private PhotonInstanceLayouts() {
    }
}
