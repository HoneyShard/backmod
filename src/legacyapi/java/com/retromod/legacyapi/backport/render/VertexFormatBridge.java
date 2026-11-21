/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Builds a vertex format from 26.2's typed attributes by choosing the 26.1.2 element with the same layout. */
public final class VertexFormatBridge {

    /** The elements each builder already holds, because a 26.1.2 format keeps each element once. */
    private static final Map<VertexFormat.Builder, Set<VertexFormatElement>> USED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VertexFormatBridge() {}

    /** {@code VertexFormat.builder(int)}: 26.1.2 has one vertex binding, so only binding 0 exists. */
    public static VertexFormat.Builder builder(int binding) {
        if (binding != 0) {
            throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 has one vertex binding, and this "
                    + "mod asked for binding " + binding + ".");
        }
        return VertexFormat.builder();
    }

    /** {@code addAttribute(name, format)}. */
    public static VertexFormat.Builder addAttribute(VertexFormat.Builder builder, String name, GpuFormat format) {
        Set<VertexFormatElement> used = USED.computeIfAbsent(builder, b -> new HashSet<>());
        VertexFormatElement element = elementFor(name, format, used);
        // The element is now taken, because a 26.1.2 format holds each element once.
        used.add(element);
        return builder.add(name, element);
    }

    /** {@code addAttribute(name, location, format)}. */
    public static VertexFormat.Builder addAttribute(VertexFormat.Builder builder, String name, int location,
                                                    GpuFormat format) {
        throw unsupported("attribute locations");
    }

    /** {@code addAttribute(name, format, divisor)}. */
    public static VertexFormat.Builder addAttribute(VertexFormat.Builder builder, String name, GpuFormat format,
                                                    int divisor) {
        throw unsupported("instanced attributes");
    }

    /** {@code addAttribute(name, binding, location, format, divisor)}. */
    public static VertexFormat.Builder addAttribute(VertexFormat.Builder builder, String name, int binding,
                                                    int location, GpuFormat format, int divisor) {
        throw unsupported("attribute locations and instanced attributes");
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("Backmod: Minecraft 26.1.2 vertex formats have no " + what + ".");
    }

    /** Finds the registered 26.1.2 element with the layout the format names, such as 3 floats or 4 normalized bytes. */
    static VertexFormatElement elementFor(String attribute, GpuFormat format, Set<VertexFormatElement> used) {
        String name = format.name();
        // Depth and stencil formats describe attachments, never vertex attributes.
        if (name.startsWith("D") || name.startsWith("S8")) {
            throw new UnsupportedOperationException("Backmod: " + format + " is a depth or stencil format, "
                    + "not a vertex attribute format.");
        }
        int split = name.lastIndexOf('_');
        String channels = name.substring(0, split);
        String kind = name.substring(split + 1);
        int count = channels.startsWith("RGBA") ? 4 : channels.startsWith("RGB") ? 3 : channels.startsWith("RG") ? 2 : 1;
        String bitsText = channels.replaceAll("[^0-9]", "");
        int bits = bitsText.isEmpty() ? 0 : Integer.parseInt(bitsText);
        boolean packed = channels.matches(".*[0-9]+[A-Z][0-9]+.*") || bits == 0;

        VertexFormatElement.Type type = null;
        boolean normalized = kind.endsWith("NORM");
        if (!packed) {
            type = switch (kind) {
                case "FLOAT" -> bits == 32 ? VertexFormatElement.Type.FLOAT : null;
                case "UNORM", "UINT" -> bits == 8 ? VertexFormatElement.Type.UBYTE
                        : bits == 16 ? VertexFormatElement.Type.USHORT
                        : bits == 32 && kind.equals("UINT") ? VertexFormatElement.Type.UINT : null;
                case "SNORM", "SINT" -> bits == 8 ? VertexFormatElement.Type.BYTE
                        : bits == 16 ? VertexFormatElement.Type.SHORT
                        : bits == 32 && kind.equals("SINT") ? VertexFormatElement.Type.INT : null;
                default -> null;
            };
        }
        if (type == null) {
            throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 has no vertex element for the format "
                    + format + ".");
        }
        // Several elements can share one layout (UV1 and UV2 are both two shorts), so list them all.
        Map<VertexFormatElement, String> names = knownNames();
        List<VertexFormatElement> candidates = new ArrayList<>();
        for (int id = 0; id < VertexFormatElement.MAX_COUNT; id++) {
            VertexFormatElement candidate = VertexFormatElement.byId(id);
            if (candidate != null && candidate.type() == type && candidate.normalized() == normalized
                    && candidate.count() == count && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        List<String> candidateNames = new ArrayList<>();
        List<Boolean> taken = new ArrayList<>();
        for (VertexFormatElement candidate : candidates) {
            candidateNames.add(names.getOrDefault(candidate, ""));
            taken.add(used.contains(candidate));
        }
        int chosen = ElementChoice.choose(attribute, candidateNames, taken);
        if (chosen >= 0) {
            return candidates.get(chosen);
        }
        throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 has no free vertex element with "
                + count + " x " + type + (normalized ? " (normalized)" : "") + " for the attribute " + attribute
                + " (" + format + ").");
    }

    /** The names 26.1.2 gives its built-in elements, such as POSITION or UV2, joined with {@code |} when one element has two. */
    private static Map<VertexFormatElement, String> knownNames() {
        Map<VertexFormatElement, String> names = new IdentityHashMap<>();
        for (Field field : VertexFormatElement.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == VertexFormatElement.class) {
                try {
                    VertexFormatElement element = (VertexFormatElement) field.get(null);
                    names.merge(element, field.getName(), (a, b) -> a + "|" + b);
                } catch (IllegalAccessException e) {
                    // A field that cannot be read just does not help the choice.
                }
            }
        }
        return names;
    }
}
