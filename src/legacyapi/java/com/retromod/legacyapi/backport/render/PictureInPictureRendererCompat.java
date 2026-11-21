/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** The 26.2 picture-in-picture renderer shape on top of 26.1.2's: no buffer source argument, and a submit collector handed in. */
public abstract class PictureInPictureRendererCompat<T extends PictureInPictureRenderState>
        extends PictureInPictureRenderer<T> {

    /** 26.2 builds the renderer without arguments. 26.1.2 wants the buffer source its GUI renderer uses. */
    protected PictureInPictureRendererCompat() {
        super(Minecraft.getInstance().renderBuffers().bufferSource());
    }

    /** The 26.2 method a mod overrides. */
    protected abstract void renderToTexture(T state, PoseStack poseStack, SubmitNodeCollector collector);

    /** 26.1.2 calls this one. It hands the mod the game's collector, then draws what the mod queued. */
    @Override
    protected final void renderToTexture(T state, PoseStack poseStack) {
        FeatureRenderDispatcher dispatcher = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher();
        renderToTexture(state, poseStack, dispatcher.getSubmitNodeStorage());
        // 26.2 draws the queue after the mod returns. In 26.1.2 the renderer does it, as vanilla's entity renderer does.
        dispatcher.renderAllFeatures();
    }
}
