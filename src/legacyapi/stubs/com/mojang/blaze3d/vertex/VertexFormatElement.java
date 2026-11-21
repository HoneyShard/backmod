/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.mojang.blaze3d.vertex;

/** Compile-only stub of the 26.1.2 record. */
public final class VertexFormatElement {
    public static final int MAX_COUNT = 32;

    /** Compile-only stub of the 26.1.2 component type. */
    public enum Type {
        FLOAT, UBYTE, BYTE, USHORT, SHORT, UINT, INT
    }

    private VertexFormatElement() {}

    public static VertexFormatElement byId(int id) { throw new UnsupportedOperationException(); }

    public Type type() { throw new UnsupportedOperationException(); }

    public boolean normalized() { throw new UnsupportedOperationException(); }

    public int count() { throw new UnsupportedOperationException(); }
}
