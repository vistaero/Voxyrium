package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.util.GlobalCleaner;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore implements IModelStore {
    public static final int MODEL_SIZE = IModelStore.MODEL_SIZE;
    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        // The 1.20.6 atlas does not expose its mip count. Voxy's 16x16 model
        // tiles have four generated mip levels, matching the legacy renderer.
        int mipLvl = 4;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    @Override
    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    @Override
    public me.cortex.voxy.client.core.rendering.util.IDeviceBuffer modelBufferHandle() {
        return this.modelBuffer;
    }

    @Override
    public me.cortex.voxy.client.core.rendering.util.IDeviceBuffer colourBufferHandle() {
        return this.modelColourBuffer;
    }

    @Override
    public void beginTextureUploads() {
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    @Override
    public void uploadModelTexture(int modelId, me.cortex.voxy.common.util.MemoryBuffer texture) {
        final int TS = ModelFactory.MODEL_TEXTURE_SIZE;
        int X = (modelId&0xFF) * TS*3;
        int Y = ((modelId>>8)&0xFF) * TS*2;
        long cAddr = texture.address;
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D(this.textures.id, lvl, X >> lvl, Y >> lvl,
                    (TS*3) >> lvl, (TS*2) >> lvl,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, cAddr);
            cAddr += (TS*TS*3*2*4)>>(lvl<<1);
        }
    }

    @Override
    public void endTextureUploads() {
    }

    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
