// Compile-only stub of a 26.1.2 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.render.pip;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

public abstract class PictureInPictureRenderer<T extends PictureInPictureRenderState> implements AutoCloseable {
    protected PictureInPictureRenderer(MultiBufferSource.BufferSource bufferSource) {}

    public abstract Class<T> getRenderStateClass();

    protected abstract void renderToTexture(T state, PoseStack poseStack);

    protected abstract String getTextureLabel();

    @Override
    public void close() {}
}
