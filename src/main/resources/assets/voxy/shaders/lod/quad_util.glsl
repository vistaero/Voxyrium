#import <voxy:lod/pos_util.glsl>
#import <voxy:lod/lighting.glsl>
//Common utility functions for decoding and operating on quads

vec3 swizzelDataAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy,data.xzy,bvec3(axis==0)),data,bvec3(axis==1));
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.00005f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    //Expand the quads by a very small amount (because of the subtraction after this also becomes an implicit add)
    faceOffsetsSizes.xz -= vec2(EPSILON);

    //Make the end relative to the start
    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}


vec2 taaOffset = vec2(0);//TODO: compute this

struct QuadData {
    uvec4 attributeData;

    float lodScale;
    uint axis;
    //Used for computing the 4 corners of the quad
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
    vec2 uvSizeAddin;
    uint face;
    uint fluidCornerHeights;
};

uint makeQuadFlags(uint faceData, uint modelId, ivec2 quadSize, const in BlockModel model, uint face) {
    //bit: 0-use cuttout, 1-dont use mipmaps, 2|3-tint state, 4|6-face, 8|11-width, 12|15-height, 16|31-model id
    uint flags = 0;

    flags |= modelId<<16;//Model id
    flags |= (uint(quadSize.x-1)<<8)|(uint(quadSize.y-1)<<12);//quad size

    {//Cuttout
        flags |= faceHasAlphaCuttout(faceData);
        flags |= uint(any(greaterThan(quadSize, ivec2(1)))) & faceHasAlphaCuttoutOverride(faceData);
    }

    //TODO: remove, there is no non mip code path anymore
    //flags |= uint(!modelHasMipmaps(model))<<1;//Not mipmaps

    flags |= faceTintState(faceData)<<2;
    flags |= face<<4;//Face

    return flags;
}

uint packVec4(vec4 vec) {
    uvec4 vec_=uvec4(vec*255)<<uvec4(24,16,8,0);
    return vec_.x|vec_.y|vec_.z|vec_.w;
}


#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face);
#endif

uvec3 makeRemainingAttributes(const in BlockModel model, const in Quad quad, uint lodLevel, uint face) {
    uvec3 attributes = uvec3(0);

    uint lighting = extractLightId(quad);

    //Apply model colour tinting
    uint tintColour = model.colourTint;

    if (modelHasBiomeLUT(model)) {
        tintColour = colourData[tintColour + extractBiomeId(quad)];
    }

    #ifdef PATCHED_SHADER
    attributes.x = lighting;
    attributes.y = tintColour;
    #else
    bool isTranslucent = modelIsTranslucent(model);

    //afak, these are the same variable in vanilla, (i.e. shaded == ao)
    bool isShaded = modelIsShaded(model);
    bool hasAO = isShaded;

    vec4 tinting = getLighting(lighting);

    uint conditionalTinting = 0;
    if (tintColour != uint(-1)) {
        conditionalTinting = tintColour;
    }

    uint addin = 0;
    if (!isTranslucent) {
        tinting.w = 0.0;
        //Encode the face, the lod level and
        uint encodedData = 0;
        encodedData |= face;
        encodedData |= (lodLevel<<3);
        encodedData |= uint(hasAO)<<6;
        addin = encodedData;
    }

    tinting.rgb *= computeDirectionalFaceTint(isShaded, face);

    attributes.x = packVec4(tinting);
    attributes.y = conditionalTinting;
    attributes.z = addin|(face<<8);
    #endif

    return attributes;
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos, bool generateAttributes) {
    uint lodLevel = getLoDLevel(sPos);
    float lodScale = 1<<lodLevel;
    ivec3 baseSection = (getLoDPosition(sPos)<<lodLevel) - baseSectionPos;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];
    bool isFluid = modelIsFluid(model);
    ivec2 quadSize = isFluid ? ivec2(1) : extractSize(rawQuad);

    if (generateAttributes) {
        quad.attributeData.x = makeQuadFlags(faceData, modelId, quadSize, model, face);
        quad.attributeData.yzw = makeRemainingAttributes(model, rawQuad, lodLevel, face);
    }

    vec4 textureFaceSize = getFaceSize(faceData);
    vec4 geometryFaceSize = textureFaceSize;

    //The texture remains cropped to the pixels baked by FluidRenderer. Fluid
    //geometry itself uses exact cell boundaries; its top vertices are adjusted
    //to the four neighbour-derived heights in getQuadCornerPos.
    if (isFluid) {
        geometryFaceSize = vec4(0.0, 1.0, 0.0, 1.0);
    }

    #ifdef USE_SINGLE_TRI
    textureFaceSize *= 2;
    geometryFaceSize *= 2;
    #endif
    vec3 quadStart = extractPos(rawQuad);
    float depthOffset = isFluid ? 0.0 : extractFaceIndentation(faceData);
    quadStart += swizzelDataAxis(face>>1, vec3(geometryFaceSize.xz, mix(depthOffset, 1-depthOffset, float(face&1u))));

    quad.lodScale = lodScale;
    quad.axis = face>>1;
    quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5);
    #ifdef USE_SINGLE_TRI
    quad.quadSizeAddin = (geometryFaceSize.yw + (quadSize - 1)*2);
    quad.uvSizeAddin = (textureFaceSize.yw + (quadSize - 1)*2);
    #else
    quad.quadSizeAddin = geometryFaceSize.yw + quadSize - 1;
    quad.uvSizeAddin = textureFaceSize.yw + quadSize - 1;
    #endif
    quad.uvCorner = textureFaceSize.xz;
    quad.face = face;
    quad.fluidCornerHeights = isFluid ? extractFluidCornerHeights(rawQuad) : uint(-1);
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {
    uvec2 cornerBits = uvec2((cornerId>>1)&1u, cornerId&1u);
    vec2 cornerMask = vec2(cornerBits)*quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis,vec3(quad.quadSizeAddin*cornerMask,0));

    if (quad.fluidCornerHeights != uint(-1)) {
        uint heightIndex = 0u;
        bool applyHeight = false;
        if (quad.face == 1u) {
            //Top face: its two in-plane axes are world X and Z.
            heightIndex = (cornerBits.x<<1)|cornerBits.y;
            applyHeight = true;
        } else if (quad.axis == 1u && cornerBits.y == 1u) {
            //North/south face: X varies horizontally and Z is the face side.
            heightIndex = (cornerBits.x<<1)|(quad.face&1u);
            applyHeight = true;
        } else if (quad.axis == 2u && cornerBits.x == 1u) {
            //West/east face: X is the face side and Z varies horizontally.
            heightIndex = ((quad.face&1u)<<1)|cornerBits.y;
            applyHeight = true;
        }

        if (applyHeight) {
            float height = float(((quad.fluidCornerHeights>>(heightIndex*3u))&7u)+1u)/8.0;
            point.y += (height-1.0)*quad.lodScale;
        }
    }

    vec4 pos = MVP * vec4(point, 1.0f);
    pos.xy += taaOffset*pos.w;
    return pos;
}

#ifndef USE_NV_BARRY
vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.uvSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u);
}
#endif

#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face) {
    //Apply face tint
    if (isShaded) {
        //just index on a const array with the face as an index, will be much faster
        // or use a vector and select/sum
        // but per face might be easier?


        if ((face>>1) == 1) {//NORTH, SOUTH
            return Z_AXIS_FACE_TINT;
        } else if ((face>>1) == 2) {//EAST, WEST
            return X_AXIS_FACE_TINT;
        } else if (face == 1) {//UP
            return UP_FACE_TINT;
        }
        //DOWN
        return DOWN_FACE_TINT;
    } else {
        return NO_SHADE_FACE_TINT;
    }
}
#endif
