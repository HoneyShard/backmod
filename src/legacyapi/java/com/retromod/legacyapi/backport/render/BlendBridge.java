/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;

/** Builds a 26.1.2 blend function from 26.2 blend factors, which 26.1.2 splits into source and destination enums. */
public final class BlendBridge {

    private BlendBridge() {}

    /** The 26.2 factor is retargeted to {@code SourceFactor}; this finds the destination constant of the same name. */
    private static DestFactor dest(SourceFactor factor) {
        try {
            return DestFactor.valueOf(factor.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Backmod: blend factor " + factor.name() + " cannot be a "
                    + "destination factor in Minecraft 26.1.2.", e);
        }
    }

    /** {@code new BlendFunction(sourceColor, destColor, sourceAlpha, destAlpha)}. */
    public static BlendFunction blendFunction(SourceFactor sourceColor, SourceFactor destColor,
                                              SourceFactor sourceAlpha, SourceFactor destAlpha) {
        return new BlendFunction(sourceColor, dest(destColor), sourceAlpha, dest(destAlpha));
    }

    /** {@code new BlendFunction(source, dest)}. */
    public static BlendFunction blendFunction(SourceFactor source, SourceFactor dest) {
        return new BlendFunction(source, dest(dest));
    }
}
