package com.nestworld.video.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Bounded, non-chunk-loading scan shared by preview and authoritative server validation. */
public record ScreenLayout(BlockPos origin, int cols, int rows, Set<BlockPos> tiles, String error) {
    public static final int MAX_SIZE = 100;
    public boolean valid() { return error.isEmpty(); }
    private static ScreenLayout failure(String error) {
        return new ScreenLayout(BlockPos.ZERO, 0, 0, Set.of(), error);
    }

    public static ScreenLayout detect(Level level, BlockPos start) {
        if (!level.hasChunkAt(start) || !(level.getBlockEntity(start) instanceof VideoScreenBlockEntity))
            return failure("missing");
        Direction facing = level.getBlockState(start).getValue(VideoScreenBlock.FACING);
        Direction right = facing.getCounterClockWise();
        Direction[] edges = {right, right.getOpposite(), Direction.UP, Direction.DOWN};
        Set<BlockPos> tiles = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        int minX = 0, maxX = 0, minY = start.getY(), maxY = start.getY();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (!visited.add(pos)) continue;
            if (level.isOutsideBuildHeight(pos)) continue;
            if (!level.hasChunkAt(pos)) return failure("unloaded");
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof VideoScreenBlock)) continue;
            if (state.getValue(VideoScreenBlock.FACING) != facing) return failure("facing");
            if (!(level.getBlockEntity(pos) instanceof VideoScreenBlockEntity)) return failure("missing");
            tiles.add(pos);
            int x = (pos.getX() - start.getX()) * right.getStepX()
                    + (pos.getZ() - start.getZ()) * right.getStepZ();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            if (maxX - minX + 1 > MAX_SIZE || maxY - minY + 1 > MAX_SIZE) return failure("large");
            for (Direction edge : edges) queue.addLast(pos.relative(edge));
        }
        int cols = maxX - minX + 1, rows = maxY - minY + 1;
        if (tiles.size() != cols * rows) return failure("holes");
        return new ScreenLayout(start.relative(right, minX).atY(minY), cols, rows, Set.copyOf(tiles), "");
    }
}
