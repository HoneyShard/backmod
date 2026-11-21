/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.mojang.blaze3d.vertex;

/** Compile-only stub of the 26.1.2 class. */
public final class VertexFormat {
    private VertexFormat() {}

    public static Builder builder() { throw new UnsupportedOperationException(); }

    /** Compile-only stub of the 26.1.2 builder. */
    public static class Builder {
        private Builder() {}

        public Builder add(String name, VertexFormatElement element) { throw new UnsupportedOperationException(); }

        public VertexFormat build() { throw new UnsupportedOperationException(); }
    }

    /** Compile-only stub of the 26.1.2 draw mode. */
    public enum Mode {
        LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS
    }
}
