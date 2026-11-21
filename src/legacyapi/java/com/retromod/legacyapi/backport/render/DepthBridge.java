/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;

/** 26.2 stores depth the other way round, so a depth state a mod writes for 26.2 needs its bias turned over. */
public final class DepthBridge {

    private DepthBridge() {}

    /**
     * {@code new DepthStencilState(op, write, slopeBias, constantBias)}. The compare op reaches this call already
     * mirrored, because each {@code CompareOp} constant is redirected where the mod reads it. 26.2's vanilla decal
     * states use +1 and +10 where 26.1.2's use -1 and -10, so the two bias values change sign.
     */
    public static DepthStencilState depthStencilState(CompareOp op, boolean writeDepth, float slopeBias,
                                                      float constantBias) {
        return new DepthStencilState(op, writeDepth, -slopeBias, -constantBias);
    }
}
