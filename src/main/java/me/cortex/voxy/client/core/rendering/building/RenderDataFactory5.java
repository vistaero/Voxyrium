package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.Mesher2D;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;


public class RenderDataFactory5 {
    private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");

    private final WorldEngine world;
    private final ModelFactory modelMan;

    //private final long[] sectionData = new long[32*32*32*2];
    private final long[] sectionData = new long[32*32*32*2];

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];


    //TODO: emit directly to memory buffer instead of long arrays

    //Each axis gets a max quad count of 2^16 (65536 quads) since that is the max the basic geometry manager can handle
    private final MemoryBuffer directionalQuadBuffer = new MemoryBuffer(6*(8*(1<<16)));
    private final long directionalQuadBufferPtr = this.directionalQuadBuffer.address;
    private final int[] directionalQuadCounters = new int[6];//Maybe change to short? (or long /w raw pointers)


    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;


    //Wont work for double sided quads
    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            RenderDataFactory5.this.quadCount++;

            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }

            }
            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                //Need to swizzle the data if on x axis
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            int faceSide = auxData&1;
            data &= ~(data&((1<<26)-1));

            final int axis = this.axis;
            int face = (axis<<1)|faceSide;
            int encodedPosition = face;

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += this.doAuxiliaryFaceOffset?(1-faceSide):0;//Shift

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + faceSide);
                }
            }

            encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);

            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            encodedPosition |= auxPos << (axis==0?16:(axis==1?11:21));

            long quad = data | encodedPosition;


            MemoryUtil.memPutLong(RenderDataFactory5.this.directionalQuadBufferPtr + (RenderDataFactory5.this.directionalQuadCounters[face]++)*8L + face*8L*(1<<16), quad);
        }
    }


    private final Mesher blockMesher = new Mesher();
    private final Mesher partiallyOpaqueMesher = new Mesher();

    public RenderDataFactory5(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this.world = world;
        this.modelMan = modelManager;
    }


    //TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then can just pull that directly
    // instead of needing to regen the entire thing


    //Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code path for it
    // since fluid states are explicitly overlays over the base block
    // can do funny stuff like double rendering

    private static final boolean USE_UINT64 = Capabilities.INSTANCE.INT64_t;
    public static final int QUADS_PER_MESHLET = 14;
    private static void writePos(long ptr, long pos) {
        if (USE_UINT64) {
            MemoryUtil.memPutLong(ptr, pos);
        } else {
            MemoryUtil.memPutInt(ptr, (int) (pos>>32));
            MemoryUtil.memPutInt(ptr + 4, (int)pos);
        }
    }

    private void prepareSectionData() {
        final var sectionData = this.sectionData;
        int opaque = 0;
        int notEmpty = 0;

        int neighborAcquireMsk = 0;
        for (int i = 0; i < 32*32*32;) {
            long block = sectionData[i + 32 * 32 * 32];//Get the block mapping

            int modelId = this.modelMan.getModelId(Mapper.getBlockId(block));
            long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

            sectionData[i * 2] = modelId | ((long) (Mapper.getLightId(block)) << 16) | (ModelQueries.isBiomeColoured(modelMetadata)?(((long) (Mapper.getBiomeId(block))) << 24):0);
            sectionData[i * 2 + 1] = modelMetadata;

            boolean isFullyOpaque = ModelQueries.isFullyOpaque(modelMetadata);
            opaque |= (isFullyOpaque ? 1:0) << (i & 31);
            notEmpty |= (modelId!=0 ? 1:0) << (i & 31);

            //TODO: here also do bitmask of what neighboring sections are needed to compute (may be getting rid of this in future)

            //Do increment here
            i++;

            if ((i & 31) == 0) {
                this.opaqueMasks[(i >> 5) - 1] = opaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = notEmpty^opaque;
                opaque = 0;
                notEmpty = 0;
            }
        }
    }


    private void generateYZFaces() {
        for (int axis = 0; axis < 2; axis++) {
            this.blockMesher.axis = axis;
            for (int layer = 0; layer < 31; layer++) {
                this.blockMesher.auxiliaryPosition = layer;
                for (int other = 0; other < 32; other++) {//TODO: need to do the faces that border sections
                    int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                    int skipAmount = axis==0?32:1;

                    int current = this.opaqueMasks[pidx];
                    int next = this.opaqueMasks[pidx + skipAmount];

                    int msk = current ^ next;
                    if (msk == 0) {
                        this.blockMesher.skip(32);
                        continue;
                    }

                    //TODO: For boarder sections, should NOT EMIT neighbors faces
                    int faceForwardMsk = msk & current;
                    int cIdx = -1;
                    while (msk != 0) {
                        int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                        int delta = index - cIdx - 1;
                        cIdx = index; //index--;
                        if (delta != 0) this.blockMesher.skip(delta);
                        msk &= ~Integer.lowestOneBit(msk);

                        int facingForward = ((faceForwardMsk >> index) & 1);

                        {
                            int idx = index + (pidx*32);

                            //TODO: swap this out for something not getting the next entry
                            long A = this.sectionData[idx * 2];
                            long B = this.sectionData[(idx + skipAmount * 32) * 2];

                            //Flip data with respect to facing direction
                            long selfModel = facingForward == 1 ? A : B;
                            long nextModel = facingForward == 1 ? B : A;

                            //Example thing thats just wrong but as example
                            this.blockMesher.putNext(((long) facingForward) |//Facing
                                    ((selfModel & 0xFFFF) << 26) | //ModelId
                                    (((nextModel>>16)&0xFF) << 55) |//Lighting
                                    ((selfModel&(0x1FFL<<24))<<(46-24))//biomeId
                            );
                        }
                    }
                    this.blockMesher.endRow();
                }
                this.blockMesher.finish();
            }

            if (true) {
                this.blockMesher.doAuxiliaryFaceOffset = false;
                //Hacky generate section side faces (without check neighbor section)
                for (int side = 0; side < 2; side++) {
                    int layer = side == 0 ? 0 : 31;
                    this.blockMesher.auxiliaryPosition = layer;
                    for (int other = 0; other < 32; other++) {
                        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                        int msk = this.opaqueMasks[pidx];
                        if (msk == 0) {
                            this.blockMesher.skip(32);
                            continue;
                        }

                        int cIdx = -1;
                        while (msk != 0) {
                            int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                            int delta = index - cIdx - 1;
                            cIdx = index; //index--;
                            if (delta != 0) this.blockMesher.skip(delta);
                            msk &= ~Integer.lowestOneBit(msk);

                            {
                                int idx = index + (pidx * 32);

                                //TODO: swap this out for something not getting the next entry
                                long A = this.sectionData[idx * 2];

                                //Example thing thats just wrong but as example
                                this.blockMesher.putNext((long) (side == 0 ? 0L : 1L) |
                                        ((A & 0xFFFFL) << 26) |
                                        (((0xFFL) & 0xFF) << 55) |
                                        ((A&(0x1FFL<<24))<<(46-24))
                                );
                            }
                        }
                        this.blockMesher.endRow();
                    }

                    this.blockMesher.finish();
                }
                this.blockMesher.doAuxiliaryFaceOffset = true;
            }


            {//Non fully opaque geometry
                //Note: think is ok to just reuse.. blockMesher
                this.blockMesher.axis = axis;
                for (int layer = 0; layer < 32; layer++) {
                    this.blockMesher.auxiliaryPosition = layer;
                    for (int other = 0; other < 32; other++) {//TODO: need to do the faces that border sections
                        int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);

                        int msk = this.nonOpaqueMasks[pidx];

                        if (msk == 0) {
                            this.blockMesher.skip(32);
                            continue;
                        }


                        int cIdx = -1;
                        while (msk != 0) {
                            int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                            int delta = index - cIdx - 1;
                            cIdx = index; //index--;
                            if (delta != 0) this.blockMesher.skip(delta);
                            msk &= ~Integer.lowestOneBit(msk);

                            {
                                int idx = index + (pidx * 32);

                                //TODO: swap this out for something not getting the next entry
                                long A = this.sectionData[idx * 2];

                                //Example thing thats just wrong but as example
                                this.blockMesher.putNext((long) (false ? 0L : 1L) |
                                        ((A & 0xFFFFL) << 26) |
                                        (((0xFFL) & 0xFF) << 55) |
                                        ((A&(0x1FFL<<24))<<(46-24))
                                );
                            }
                        }
                        this.blockMesher.endRow();
                    }
                    this.blockMesher.finish();
                }
            }


        }
    }


    private final Mesher[] xAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;//X axis
            this.xAxisMeshers[i] = mesher;
        }
    }

    private static final long X_I_MSK = 0x4210842108421L;
    private void generateXFaces() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                /*
                {//Dont need this as can just increment everything then -1 in mask
                    //Compute and increment skips for indexes
                    long imsk = Integer.toUnsignedLong(~msk);// we only want to increment where there isnt a face
                    sumA += Long.expand(imsk, X_I_MSK);
                    sumB += Long.expand(imsk>>11, X_I_MSK);
                    sumC += Long.expand(imsk>>22, X_I_MSK);
                }*/

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry
                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[(idx + 1) * 2];

                        //Flip data with respect to facing direction
                        long selfModel = facingForward==1?A:B;
                        long nextModel = facingForward==1?B:A;

                        //Example thing thats just wrong but as example
                        mesher.putNext(((long) facingForward) |//Facing
                                ((selfModel & 0xFFFF) << 26) | //ModelId
                                (((nextModel>>16)&0xFF) << 55) |//Lighting
                                ((selfModel&(0x1FFL<<24))<<(46-24))//biomeId
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }

        //Generate the side faces, hackily, using 0 and 1 mesher
        if (true) {
            var ma = this.xAxisMeshers[0];
            var mb = this.xAxisMeshers[31];
            ma.finish();
            mb.finish();
            ma.doAuxiliaryFaceOffset = false;
            mb.doAuxiliaryFaceOffset = false;
            for (int y = 0; y < 32; y++) {
                int skipA = 0;
                int skipB = 0;
                for (int z = 0; z < 32; z++) {
                    int i = y*32+z;
                    int msk = this.opaqueMasks[i];
                    if ((msk & 1) != 0) {
                        ma.skip(skipA); skipA = 0;

                        long A = this.sectionData[(i<<5) * 2];

                        ma.putNext(0L | ((A&0xFFFF)<<26) | (((0xFFL)&0xFF)<<55)|((A&(0x1FFL<<24))<<(46-24)));
                    } else {skipA++;}

                    if ((msk & (1<<31)) != 0) {
                        mb.skip(skipB); skipB = 0;

                        long A = this.sectionData[(i*32+31) * 2];

                         mb.putNext(1L | ((A&0xFFFF)<<26) | (((0xFFL)&0xFF)<<55)|((A&(0x1FFL<<24))<<(46-24)));
                    } else {skipB++;}
                }
                ma.skip(skipA);
                mb.skip(skipB);
            }
            ma.finish();
            mb.finish();
            ma.doAuxiliaryFaceOffset = true;
            mb.doAuxiliaryFaceOffset = true;
        }

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
    }

    /*
    private static long createQuad() {
        ((long)clientModelId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(metadata, face)?self:facingState))<<16) | ((((long) Mapper.getBiomeId(self))<<24) * (ModelQueries.isBiomeColoured(metadata)?1:0)) | otherFlags


            long data = Integer.toUnsignedLong(array[i*3+1]);
            data |= ((long) array[i*3+2])<<32;
            long encodedQuad = Integer.toUnsignedLong(QuadEncoder.encodePosition(face, otherAxis, quad)) | ((data&0xFFFF)<<26) | (((data>>16)&0xFF)<<55) | (((data>>24)&0x1FF)<<46);
    }*/

    //section is already acquired and gets released by the parent
    public BuiltSection generateMesh(WorldSection section) {
        //Copy section data to end of array so that can mutate array while reading safely
        section.copyDataTo(this.sectionData, 32*32*32);

        this.quadCount = 0;

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        Arrays.fill(this.directionalQuadCounters, (short) 0);


        this.world.acquire(section.lvl, section.x+1, section.y, section.z).release();
        this.world.acquire(section.lvl, section.x-1, section.y, section.z).release();

        this.world.acquire(section.lvl, section.x, section.y+1, section.z).release();
        this.world.acquire(section.lvl, section.x, section.y-1, section.z).release();

        this.world.acquire(section.lvl, section.x, section.y, section.z+1).release();
        this.world.acquire(section.lvl, section.x, section.y, section.z-1).release();



        //Prepare everything
        this.prepareSectionData();


        this.generateYZFaces();
        this.generateXFaces();


        //TODO:NOTE! when doing face culling of translucent blocks,
        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc

        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        //TODO: FIXME AND OPTIMIZE, get rid of the stupid quad collector bullshit

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;

        for (int face = 0; face < 6; face++) {
            offsets[face + 2] = coff;
            int size = this.directionalQuadCounters[face];
            UnsafeUtil.memcpy(this.directionalQuadBufferPtr + (face*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            coff += size;
        }


        int aabb = 0;
        aabb |= 0;
        aabb |= 0<<5;
        aabb |= 0<<10;
        aabb |= (31)<<15;
        aabb |= (31)<<20;
        aabb |= (31)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets);

        /*
            buff = new MemoryBuffer(bufferSize * 8L);
            long ptr = buff.address;
            int coff = 0;

            //Ordering is: translucent, double sided quads, directional quads
            offsets[0] = coff;
            int size = this.translucentQuadCollector.size();
            LongArrayList arrayList = this.translucentQuadCollector;
            for (int i = 0; i < size; i++) {
                long data = arrayList.getLong(i);
                MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
            }

            offsets[1] = coff;
            size = this.doubleSidedQuadCollector.size();
            arrayList = this.doubleSidedQuadCollector;
            for (int i = 0; i < size; i++) {
                long data = arrayList.getLong(i);
                MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
            }

            for (int face = 0; face < 6; face++) {
                offsets[face + 2] = coff;
                final LongArrayList faceArray = this.directionalQuadCollectors[face];
                size = faceArray.size();
                for (int i = 0; i < size; i++) {
                    long data = faceArray.getLong(i);
                    MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
                }
            }

        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        aabb |= (this.maxX-this.minX)<<15;
        aabb |= (this.maxY-this.minY)<<20;
        aabb |= (this.maxZ-this.minZ)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets);
         */
    }

    public void free() {
        this.directionalQuadBuffer.free();
    }














    //Returns true if a face was placed
    private boolean putFluidFaceIfCan(Mesher2D mesher, int face, int opposingFace, long self, long metadata, int selfClientModelId, int selfBlockId, long facingState, long facingMetadata, int facingClientModelId, int a, int b) {
        int selfFluidClientId = this.modelMan.getFluidClientStateId(selfClientModelId);
        long selfFluidMetadata = this.modelMan.getModelMetadataFromClientId(selfFluidClientId);

        int facingFluidClientId = -1;
        if (ModelQueries.containsFluid(facingMetadata)) {
            facingFluidClientId = this.modelMan.getFluidClientStateId(facingClientModelId);
        }

        //If both of the states are the same, then dont render the fluid face
        if (selfFluidClientId == facingFluidClientId) {
            return false;
        }

        if (facingFluidClientId != -1) {
            //TODO: OPTIMIZE
            if (this.world.getMapper().getBlockStateFromBlockId(selfBlockId).getBlock() == this.world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(facingState)).getBlock()) {
               return false;
            }
        }


        if (ModelQueries.faceOccludes(facingMetadata, opposingFace)) {
            return false;
        }

        //if the model has a fluid state but is not a liquid need to see if the solid state had a face rendered and that face is occluding, if so, dont render the fluid state face
        if ((!ModelQueries.isFluid(metadata)) && ModelQueries.faceOccludes(metadata, face)) {
            return false;
        }



        //TODO:FIXME SOMEHOW THIS IS CRITICAL!!!!!!!!!!!!!!!!!!
        // so there is one more issue need to be fixed, if water is layered ontop of eachother, the side faces depend on the water state ontop
        // this has been hackfixed in the model texture bakery but a proper solution that doesnt explode the sides of the water textures needs to be done
        // the issue is that the fluid rendering depends on the up state aswell not just the face state which is really really painful to account for
        // e.g the sides of a full water is 8 high or something, not the full block height, this results in a gap between water layers



        long otherFlags = 0;
        otherFlags |= ModelQueries.isTranslucent(selfFluidMetadata)?1L<<33:0;
        otherFlags |= ModelQueries.isDoubleSided(selfFluidMetadata)?1L<<34:0;
        mesher.put(a, b, ((long)selfFluidClientId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(selfFluidMetadata, face)?self:facingState))<<16) | ((((long) Mapper.getBiomeId(self))<<24) * (ModelQueries.isBiomeColoured(selfFluidMetadata)?1:0)) | otherFlags);
        return true;
    }

    //Returns true if a face was placed
    private boolean putFaceIfCan(Mesher2D mesher, int face, int opposingFace, long metadata, int clientModelId, int selfBiome, long facingModelId, long facingMetadata, int selfLight, int facingLight, int a, int b) {
        if (ModelQueries.cullsSame(metadata) && clientModelId == facingModelId) {
            //If we are facing a block, and we are both the same state, dont render that face
            return false;
        }

        //If face can be occluded and is occluded from the facing block, then dont render the face
        if (ModelQueries.faceCanBeOccluded(metadata, face) && ModelQueries.faceOccludes(facingMetadata, opposingFace)) {
            return false;
        }

        long otherFlags = 0;
        otherFlags |= ModelQueries.isTranslucent(metadata)?1L<<33:0;
        otherFlags |= ModelQueries.isDoubleSided(metadata)?1L<<34:0;
        mesher.put(a, b, ((long)clientModelId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(metadata, face)?selfLight:facingLight))<<16) | (ModelQueries.isBiomeColoured(metadata)?(((long) Mapper.getBiomeId(selfBiome))<<24):0) | otherFlags);
        return true;
    }

    private static int getMeshletHoldingCount(int quads, int quadsPerMeshlet, int meshletSize) {
        return ((quads+(quadsPerMeshlet-1))/quadsPerMeshlet)*meshletSize;
    }

    public static int alignUp(int n, int alignment) {
        return (n + alignment - 1) & -alignment;
    }
}