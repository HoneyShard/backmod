/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;

/** The layouts 26.2 ships by name. Each holds the sampler and uniform names 26.1.2's own pipelines register. */
public final class BindGroupLayouts {

    public static final BindGroupLayout DYNAMIC_TRANSFORMS = buffer("DynamicTransforms");
    public static final BindGroupLayout PROJECTION = buffer("Projection");
    public static final BindGroupLayout MATRICES_PROJECTION = BindGroupLayout.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER).build();
    public static final BindGroupLayout CHUNK_SECTION = buffer("ChunkSection");
    public static final BindGroupLayout FOG = buffer("Fog");
    public static final BindGroupLayout GLOBALS = buffer("Globals");
    public static final BindGroupLayout LIGHTING = buffer("Lighting");
    public static final BindGroupLayout SAMPLER0 = samplers("Sampler0");
    public static final BindGroupLayout SAMPLER1 = samplers("Sampler1");
    public static final BindGroupLayout SAMPLER2 = samplers("Sampler2");
    public static final BindGroupLayout SAMPLER0_SAMPLER2 = samplers("Sampler0", "Sampler2");
    public static final BindGroupLayout SAMPLER0_SAMPLER1 = samplers("Sampler0", "Sampler1");
    public static final BindGroupLayout SAMPLER0_SAMPLER1_SAMPLER2 = samplers("Sampler0", "Sampler1", "Sampler2");
    public static final BindGroupLayout CLOUD_INFO = BindGroupLayout.builder()
            .withUniform("CloudInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("CloudFaces", UniformType.TEXEL_BUFFER, TextureFormat.RED8I).build();
    public static final BindGroupLayout DISSOLVE_MASK_SAMPLER = samplers("DissolveMaskSampler");
    public static final BindGroupLayout IN_SAMPLER = samplers("InSampler");
    public static final BindGroupLayout LIGHTMAP_INFO = buffer("LightmapInfo");
    public static final BindGroupLayout SPRITE_ANIMATION_INFO = buffer("SpriteAnimationInfo");
    public static final BindGroupLayout SPRITE = samplers("Sprite");
    public static final BindGroupLayout CURRENT_SPRITE_NEXT_SPRITE = samplers("CurrentSprite", "NextSprite");

    private BindGroupLayouts() {}

    private static BindGroupLayout buffer(String name) {
        return BindGroupLayout.builder().withUniform(name, UniformType.UNIFORM_BUFFER).build();
    }

    private static BindGroupLayout samplers(String... names) {
        BindGroupLayout.Builder builder = BindGroupLayout.builder();
        for (String name : names) {
            builder.withSampler(name);
        }
        return builder.build();
    }
}
