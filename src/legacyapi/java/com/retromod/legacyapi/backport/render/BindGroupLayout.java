/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;

import java.util.ArrayList;
import java.util.List;

/** The 26.2 list of samplers and uniforms a pipeline binds, which 26.1.2 sets one at a time on the builder. */
public final class BindGroupLayout {

    /** One uniform a layout binds. A texel buffer also names its texture format. */
    public record UniformDescription(String name, UniformType type, TextureFormat textureFormat) {
        public UniformDescription(String name, UniformType type) {
            this(name, type, null);
        }
    }

    /** The 26.1.2 texture format for the few texel formats it has. */
    static TextureFormat textureFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> TextureFormat.RED8;
            case R8_SINT -> TextureFormat.RED8I;
            case RGBA8_UNORM -> TextureFormat.RGBA8;
            case D32_FLOAT -> TextureFormat.DEPTH32;
            default -> throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 has no texture format "
                    + "for " + format + ".");
        };
    }

    private final List<String> samplers;
    private final List<UniformDescription> uniforms;

    private BindGroupLayout(List<String> samplers, List<UniformDescription> uniforms) {
        this.samplers = List.copyOf(samplers);
        this.uniforms = List.copyOf(uniforms);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> getSamplers() {
        return samplers;
    }

    public List<UniformDescription> getUniforms() {
        return uniforms;
    }

    /** Collects the samplers and uniforms for one layout. */
    public static final class Builder {
        private final List<String> samplers = new ArrayList<>();
        private final List<UniformDescription> uniforms = new ArrayList<>();

        public Builder withSampler(String name) {
            samplers.add(name);
            return this;
        }

        public Builder withUniform(String name, UniformType type) {
            uniforms.add(new UniformDescription(name, type));
            return this;
        }

        public Builder withUniform(String name, UniformType type, TextureFormat format) {
            uniforms.add(new UniformDescription(name, type, format));
            return this;
        }

        /** The 26.2 form, which names the texel format with {@code GpuFormat}. */
        public Builder withUniform(String name, UniformType type, GpuFormat format) {
            return withUniform(name, type, textureFormat(format));
        }

        public BindGroupLayout build() {
            return new BindGroupLayout(samplers, uniforms);
        }
    }
}
