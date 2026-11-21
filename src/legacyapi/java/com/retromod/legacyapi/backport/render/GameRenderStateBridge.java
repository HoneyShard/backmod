/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;

/** Members 26.2 put on the game render state that 26.1.2 keeps elsewhere. */
public final class GameRenderStateBridge {

    private GameRenderStateBridge() {}

    /** {@code GameRenderState.useShaderTransparency()}: 26.1.2 answers it on {@code Minecraft}, from the same two flags. */
    public static boolean useShaderTransparency(GameRenderState state) {
        return Minecraft.useShaderTransparency();
    }
}
