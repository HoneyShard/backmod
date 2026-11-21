/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Gives a 26.2 pipeline builder call its 26.1.2 form, keeping what the two builders split differently. */
public final class RenderPipelineBridge {

    /** What one builder has been told so far, because 26.1.2 takes the vertex format and draw mode together. */
    private static final class Pending {
        VertexFormat format;
        VertexFormat.Mode mode;
        final Map<String, Object> bound = new HashMap<>();
    }

    private static final Map<RenderPipeline.Builder, Pending> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RenderPipelineBridge() {}

    private static Pending pending(RenderPipeline.Builder builder) {
        return PENDING.computeIfAbsent(builder, b -> new Pending());
    }

    /** {@code withBindGroupLayout}: adds each sampler and uniform of the layout once. */
    public static RenderPipeline.Builder withBindGroupLayout(RenderPipeline.Builder builder, BindGroupLayout layout) {
        Pending state = pending(builder);
        for (String sampler : layout.getSamplers()) {
            // Two layouts may both name a sampler, and 26.2 merges them.
            if (state.bound.putIfAbsent("sampler:" + sampler, sampler) == null) {
                builder.withSampler(sampler);
            }
        }
        for (BindGroupLayout.UniformDescription uniform : layout.getUniforms()) {
            Object previous = state.bound.putIfAbsent("uniform:" + uniform.name(), uniform);
            if (previous == null) {
                if (uniform.textureFormat() == null) {
                    builder.withUniform(uniform.name(), uniform.type());
                } else {
                    builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
                }
            } else if (!previous.equals(uniform)) {
                throw new IllegalStateException("Backmod: bind group layouts disagree about uniform "
                        + uniform.name() + ": " + previous + " and " + uniform);
            }
        }
        return builder;
    }

    /** {@code withVertexBinding}: 26.1.2 has one vertex binding, so only binding 0 can be set. */
    public static RenderPipeline.Builder withVertexBinding(RenderPipeline.Builder builder, int binding,
                                                           VertexFormat format) {
        if (binding != 0) {
            throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 pipelines have one vertex binding, "
                    + "and this mod asked for binding " + binding + ".");
        }
        Pending state = pending(builder);
        state.format = format;
        return apply(builder, state);
    }

    /** {@code withPrimitiveTopology}: the draw mode, which 26.1.2 calls {@code VertexFormat.Mode}. */
    public static RenderPipeline.Builder withPrimitiveTopology(RenderPipeline.Builder builder,
                                                               VertexFormat.Mode mode) {
        Pending state = pending(builder);
        state.mode = mode;
        return apply(builder, state);
    }

    /** Sets the format and mode on the builder as soon as both are known. */
    private static RenderPipeline.Builder apply(RenderPipeline.Builder builder, Pending state) {
        if (state.format != null && state.mode != null) {
            builder.withVertexFormat(state.format, state.mode);
        }
        return builder;
    }

    /** {@code RenderPipeline.getVertexFormatBinding(int)}. */
    public static VertexFormat getVertexFormatBinding(RenderPipeline pipeline, int binding) {
        if (binding != 0) {
            throw new UnsupportedOperationException("Backmod: Minecraft 26.1.2 pipelines have one vertex binding, "
                    + "and this mod asked for binding " + binding + ".");
        }
        return pipeline.getVertexFormat();
    }

    /** {@code RenderPipeline.getPrimitiveTopology()}. */
    public static VertexFormat.Mode getPrimitiveTopology(RenderPipeline pipeline) {
        return pipeline.getVertexFormatMode();
    }
}
