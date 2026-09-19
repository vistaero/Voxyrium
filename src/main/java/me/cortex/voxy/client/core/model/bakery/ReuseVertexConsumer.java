package me.cortex.voxy.client.core.model.bakery;


import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class ReuseVertexConsumer implements VertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 24;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;

    public ReuseVertexConsumer() {
        this.reset();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float f) {
        return null;
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.shade();
        this.anyDarkendTex |= quad.sprite().contents().mipmapStrategy == MipmapStrategy.DARK_CUTOUT;
        this.ensureCanPut();
        for (int i = 0; i < 4; i++) {
            var pos = quad.position(i);
            this.addVertex(pos.x(), pos.y(), pos.z());
            long puv = quad.packedUV(i);
            this.setUv(UVPair.unpackU(puv),UVPair.unpackV(puv));

            this.meta(metadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
