package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.lwjgl.system.MemoryUtil;

/** Captures Minecraft 1.20.6 vertices in the compact software-rasterizer format. */
public final class ReuseVertexConsumer implements VertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 24;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;
    private final int globalOrMetadata;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    public boolean anyDiscard;

    public ReuseVertexConsumer() {
        this(0);
    }

    public ReuseVertexConsumer(int globalOrMetadata) {
        this.globalOrMetadata = globalOrMetadata;
        this.reset();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    public int getDefaultMeta() {
        return this.defaultMeta;
    }

    @Override
    public ReuseVertexConsumer vertex(double x, double y, double z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE;
        this.count++;
        this.meta(this.defaultMeta | this.globalOrMetadata);
        MemoryUtil.memPutFloat(this.ptr, (float) x);
        MemoryUtil.memPutFloat(this.ptr + 4, (float) y);
        MemoryUtil.memPutFloat(this.ptr + 8, (float) z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata & 1) != 0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer color(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public ReuseVertexConsumer uv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer normal(float x, float y, float z) {
        return this;
    }

    @Override
    public void endVertex() {
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
    }

    @Override
    public void unsetDefaultColor() {
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid) {
        int metadata = this.defaultMeta;
        if (forceSolid) {
            metadata &= ~1;
        }
        if (quad.isTinted()) {
            metadata |= 4;
        }
        return this.quad(quad, metadata);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            this.vertex(Float.intBitsToFloat(vertices[base]),
                    Float.intBitsToFloat(vertices[base + 1]),
                    Float.intBitsToFloat(vertices[base + 2]));
            this.uv(Float.intBitsToFloat(vertices[base + 4]), Float.intBitsToFloat(vertices[base + 5]));
            this.meta(metadata | this.globalOrMetadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr - this.buffer.address;
        var newBuffer = new MemoryBuffer((((int) (this.buffer.size * 2) + VERTEX_FORMAT_SIZE - 1)
                / VERTEX_FORMAT_SIZE) * VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyDiscard = false;
        this.defaultMeta = 0;
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;
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
        if (this.count % 4 != 0) {
            throw new IllegalStateException();
        }
        return this.count / 4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
