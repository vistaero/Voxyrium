package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getTexture();
        if (tex.getFormat() != TextureFormat.RGBA8) {
            throw new IllegalStateException("Block atlas not rgba8");
        }

        int targetMipLevel = 0;// Math.min(tex.getMipLevels(), 4)-1;//todo: we want to target the mip layer that has the 16x16 sized textures

        int width = tex.getWidth(targetMipLevel);
        int height = tex.getHeight(targetMipLevel);

        //Just do it ourselves as doing it with b3d has some issues, (doing it ourselves is also just much much much shorter)
        var texture = new int[width * height];

        glFlush();
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ROW_LENGTH, width);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glGetTextureImage(((GlTexture) tex).glId(), 0, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        this.rasterizer.setSamplerTexture(texture, width, height);
    }

    public static int getMetaFromLayer(ChunkSectionLayer layer) {
        boolean hasDiscard = layer == ChunkSectionLayer.CUTOUT ||
                layer == ChunkSectionLayer.TRANSLUCENT||
                layer == ChunkSectionLayer.TRIPWIRE;

        int meta = hasDiscard?1:0;
        meta |= true?2:0;
        return meta;
    }

    private void bakeBlockModel(BlockState state, ChunkSectionLayer layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = getMetaFromLayer(layer);

        for (var part : model.collectParts(new SingleThreadedRandomSource(42L))) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    this.vc.quad(quad, meta|(quad.isTinted()?4:0));
                }
            }
        }
    }


    private void bakeFluidState(BlockState state, ChunkSectionLayer layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getUnitVec3i();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.vc.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);


        boolean isBlock = true;
        ChunkSectionLayer layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = ChunkSectionLayer.SOLID;
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        {
            this.rasterizer.setBlending(layer == ChunkSectionLayer.TRANSLUCENT);

            //var tex = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")).getTexture();
            //blockTextureId = ((com.mojang.blaze3d.opengl.GlTexture)tex).glId();
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();

                    this.rasterizer.raster(VIEWS[i], this.vc);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (this.vc.isEmpty()) continue;
                isAnyShaded |= this.vc.anyShaded;
                isAnyDarkend |= this.vc.anyDarkendTex;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);
                this.rasterizer.clear();

                //The projection matrix
                this.rasterizer.raster(VIEWS[i], this.vc);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }


        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
