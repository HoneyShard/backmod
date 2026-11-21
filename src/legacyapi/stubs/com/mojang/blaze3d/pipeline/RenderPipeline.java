// Compile-only stub of a 26.2 host type for src/legacyapi; 26.3 moved it to com.mojang.renderpearl.api.pipeline. Never packaged.
// The 26.1.2 members below are the ones the backport render shims call.
package com.mojang.blaze3d.pipeline;

import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

public class RenderPipeline {
    private RenderPipeline() {}

    public VertexFormat getVertexFormat() { throw new UnsupportedOperationException(); }

    public VertexFormat.Mode getVertexFormatMode() { throw new UnsupportedOperationException(); }

    public static class Builder {
        private Builder() {}

        public Builder withSampler(String name) { throw new UnsupportedOperationException(); }

        public Builder withUniform(String name, UniformType type) { throw new UnsupportedOperationException(); }

        public Builder withUniform(String name, UniformType type, TextureFormat format) {
            throw new UnsupportedOperationException();
        }

        public Builder withVertexFormat(VertexFormat format, VertexFormat.Mode mode) {
            throw new UnsupportedOperationException();
        }
    }
}
