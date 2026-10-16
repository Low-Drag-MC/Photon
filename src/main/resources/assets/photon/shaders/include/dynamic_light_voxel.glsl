// The voxel copy of the blocks around the shadowed lights, and rays through it. Positions are in blocks relative
// to the voxel region's min corner.

uniform usampler2D VoxelPages;
uniform usampler2D VoxelBricks;
uniform mat4 IViewMat;
uniform vec3 CameraFrac;     // camera position minus its block position
uniform ivec3 VoxelOrigin;   // voxel region min corner in blocks, relative to the camera block

const ivec3 REGION_SECTIONS = ivec3(32, 16, 32);
const ivec3 REGION_CELLS = REGION_SECTIONS * 32;

// a view-space position in the voxel region; the full matrix, not mat3: under a shader pack Iris folds view bobbing,
// translation included, into the model-view
vec3 voxelPos(vec3 view) {
    return (IViewMat * vec4(view, 1.0)).xyz + CameraFrac - vec3(VoxelOrigin);
}

// pages: 0 unknown, 1 empty, 2 solid, 3+ brick slot; bricks: one byte per block, a 2x2x2 occupancy mask
uint pageAt(ivec3 section) {
    int index = section.x + REGION_SECTIONS.x * (section.z + REGION_SECTIONS.z * section.y);
    return texelFetch(VoxelPages, ivec2(index & 127, index >> 7), 0).r;
}

uint blockMask(ivec3 cell, uint page) {
    int slot = int(page - 3u);
    ivec3 local = (cell >> 1) & 15;
    ivec2 texel = ivec2((slot & 31) * 64 + local.x + 16 * (local.y & 3), (slot >> 5) * 64 + local.z + 16 * (local.y >> 2));
    return texelFetch(VoxelBricks, texel, 0).r;
}

// How far a ray goes before it enters a solid half-block cell, or maxBlocks if it gets there first. DDA through
// half-block cells; empty sections and empty blocks are crossed in one jump each, and the starting cell is never
// its own occluder. Outside the region the path is unknown and counts as clear.
float voxelTrace(vec3 fromBlock, vec3 dir, float maxBlocks) {
    vec3 p0 = fromBlock * 2.0;
    float stopAt = maxBlocks * 2.0;
    vec3 safe = vec3(abs(dir.x) < 1e-6 ? 1e-6 : dir.x, abs(dir.y) < 1e-6 ? 1e-6 : dir.y, abs(dir.z) < 1e-6 ? 1e-6 : dir.z);
    vec3 invDir = 1.0 / safe;
    ivec3 stepDir = ivec3(sign(safe));
    vec3 tDelta = abs(invDir);
    vec3 forward = step(vec3(0.0), safe);
    ivec3 cell = ivec3(floor(p0));
    vec3 tMax = (vec3(cell) + forward - p0) * invDir;
    float t = 0.0;
    bool started = false;
    ivec3 cachedSection = ivec3(-1);
    uint page = 0u;
    for (int i = 0; i < 160; i++) {
        if (started) {
            if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, REGION_CELLS))) return maxBlocks;
            ivec3 section = cell >> 5;
            if (section != cachedSection) {
                cachedSection = section;
                page = pageAt(section);
            }
            if (page == 2u) return t * 0.5;
            // size of the empty box to jump over, in cells: a whole section, or a whole block
            float jump = 0.0;
            if (page < 2u) {
                jump = 32.0;
            } else {
                uint bits = blockMask(cell, page);
                if (bits == 0u) {
                    jump = 2.0;
                } else {
                    ivec3 sub = cell & 1;
                    if (((bits >> uint(sub.x + 2 * sub.y + 4 * sub.z)) & 1u) != 0u) return t * 0.5;
                }
            }
            if (jump > 0.0) {
                vec3 boxMin = floor(vec3(cell) / jump) * jump;
                vec3 exitT = (boxMin + forward * jump - p0) * invDir;
                float tExit = min(exitT.x, min(exitT.y, exitT.z));
                if (tExit >= stopAt) return maxBlocks;
                t = tExit + 1e-3;
                cell = ivec3(floor(p0 + dir * t));
                tMax = (vec3(cell) + forward - p0) * invDir;
                continue;
            }
        }
        started = true;
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x; tMax.x += tDelta.x; cell.x += stepDir.x;
        } else if (tMax.y < tMax.z) {
            t = tMax.y; tMax.y += tDelta.y; cell.y += stepDir.y;
        } else {
            t = tMax.z; tMax.z += tDelta.z; cell.z += stepDir.z;
        }
        if (t >= stopAt) return maxBlocks;
    }
    return maxBlocks;
}

// 1 = lit. Stops short of the light: whatever draws the source is not its own occluder.
float voxelShadow(vec3 fromBlock, vec3 toBlock) {
    vec3 delta = toBlock - fromBlock;
    float len = length(delta);
    float reach = len - 0.3;
    if (reach <= 0.0) return 1.0;
    return voxelTrace(fromBlock, delta / len, reach) < reach ? 0.0 : 1.0;
}
