package me.cortex.voxy.client.core.model.bakery;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

//OpenGL block-atlas readback: glGetTextureImage of the atlas' mip 0. This is
// the original in-line path from SoftwareModelTextureBakery, unchanged; loaded
// only on the GL backend (see IAtlasTextureReader).
final class GlAtlasTextureReader extends IAtlasTextureReader {
    @Override
    public int[] read(int textureId, int width, int height) {
        //Just do it ourselves as doing it with b3d has some issues, (doing it ourselves is also just much much much shorter)
        var texture = new int[width * height];
        glFlush();
        glFinish();
        int previousPixelPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int previousPackImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int previousPackSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int previousPackSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int previousPackAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ROW_LENGTH, width);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glGetTextureImage(textureId, 0, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, previousPackRowLength);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, previousPackImageHeight);
            glPixelStorei(GL_PACK_SKIP_ROWS, previousPackSkipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
            glPixelStorei(GL_PACK_ALIGNMENT, previousPackAlignment);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        }
        return texture;
    }
}
