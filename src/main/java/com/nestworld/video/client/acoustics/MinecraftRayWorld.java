package com.nestworld.video.client.acoustics;

import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The loaded blocks as a {@link RayWorld}: walks the cells along a ray and clips the blocks' real collision shapes. */
public final class MinecraftRayWorld implements RayWorld {
    private static final int MAX_STEPS = 200;
    private final Level level;

    public MinecraftRayWorld(Level level) { this.level = level; }

    private interface Visitor { /** True to stop walking. */ boolean visit(BlockPos pos, BlockState state, Vec3 from, Vec3 to, double t); }

    /** Amanatides-Woo walk through the block cells between {@code from} and {@code to}. */
    private void walk(Vec3 from, Vec3 to, Visitor visitor) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int sx = Integer.signum((int) Math.signum(dx)), sy = Integer.signum((int) Math.signum(dy)), sz = Integer.signum((int) Math.signum(dz));
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx), tdy = sy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy),
                tdz = sz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? x + 1 - from.x : from.x - x) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? y + 1 - from.y : from.y - y) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? z + 1 - from.z : from.z - z) * tdz;
        double t = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int step = 0; step < MAX_STEPS; step++) {
            pos.set(x, y, z);
            if (level.isLoaded(pos) && level.isInWorldBounds(pos)) {
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && visitor.visit(pos.immutable(), state, from, to, t)) return;
            }
            if (tx < ty && tx < tz) { if (tx > 1.0) return; t = tx; x += sx; tx += tdx; }
            else if (ty < tz) { if (ty > 1.0) return; t = ty; y += sy; ty += tdy; }
            else { if (tz > 1.0) return; t = tz; z += sz; tz += tdz; }
        }
    }

    /** The screen's own blocks never block its own sound. */
    private boolean isScreen(BlockPos pos) { return level.getBlockEntity(pos) instanceof VideoScreenBlockEntity; }

    private static Vec3 vec(V3 v) { return new Vec3(v.x(), v.y(), v.z()); }

    @Override public Hit cast(V3 origin, V3 dir, double maxDist) {
        Vec3 from = vec(origin), to = vec(origin.add(dir.scale(maxDist)));
        Hit[] found = new Hit[1];
        walk(from, to, (pos, state, a, b, t) -> {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) return false;
            BlockHitResult hit = shape.clip(a, b, pos);
            if (hit == null || isScreen(pos)) return false;
            Vec3 p = hit.getLocation();
            var n = hit.getDirection().getNormal();
            found[0] = new Hit(new V3(p.x, p.y, p.z), new V3(n.getX(), n.getY(), n.getZ()), p.distanceTo(a), AcousticMaterials.of(state).reflectivity());
            return true;
        });
        return found[0];
    }

    @Override public double occlusion(V3 a, V3 b, double cap) {
        double[] total = {0};
        walk(vec(a), vec(b), (pos, state, from, to, t) -> {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty() || shape.clip(from, to, pos) == null || isScreen(pos)) return false;
            // A thin or partial block (door, slab, fence) lets part of the sound through.
            total[0] += AcousticMaterials.of(state).occlusion() * (state.isCollisionShapeFullBlock(level, pos) ? 1.0 : 0.5);
            return total[0] >= cap;
        });
        return Math.min(total[0], cap);
    }
}
