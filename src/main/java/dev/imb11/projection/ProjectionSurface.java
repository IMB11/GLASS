package dev.imb11.projection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class ProjectionSurface {
    public static final int REVEAL_EDGE_WIDTH = 2;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final BlockPos origin;
    private final Direction facing;
    private final Direction uDirection;
    private final Direction vDirection;
    private final Bounds bounds;
    private final List<Cell> cells;
    private final List<Cell> glassCells;
    private final Set<BlockPos> frontier;
    private final int furthestRevealDistance;
    private final long topologyHash;
    private final long version;

    private ProjectionSurface(
            BlockPos origin,
            Direction facing,
            Direction uDirection,
            Direction vDirection,
            Bounds bounds,
            List<Cell> cells,
            List<Cell> glassCells,
            Set<BlockPos> frontier,
            int furthestRevealDistance,
            long topologyHash,
            long version
    ) {
        this.origin = origin;
        this.facing = facing;
        this.uDirection = uDirection;
        this.vDirection = vDirection;
        this.bounds = bounds;
        this.cells = cells;
        this.glassCells = glassCells;
        this.frontier = frontier;
        this.furthestRevealDistance = furthestRevealDistance;
        this.topologyHash = topologyHash;
        this.version = version;
    }

    public static ProjectionSurface rebuild(
            BlockPos origin,
            Direction facing,
            long version,
            Predicate<BlockPos> isGlass
    ) {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(facing);
        Objects.requireNonNull(isGlass);

        Direction uDirection = uDirection(facing);
        Direction vDirection = vDirection(facing);
        Direction[] traversalDirections = {
                uDirection,
                uDirection.getOpposite(),
                vDirection,
                vDirection.getOpposite()
        };

        Map<BlockPos, Integer> distances = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos immutableOrigin = origin.immutable();
        distances.put(immutableOrigin, 0);
        queue.add(immutableOrigin);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            int nextDistance = distances.get(current) + 1;
            for (Direction direction : traversalDirections) {
                BlockPos neighbor = current.relative(direction);
                if (distances.containsKey(neighbor) || !isGlass.test(neighbor)) {
                    continue;
                }
                BlockPos immutableNeighbor = neighbor.immutable();
                distances.put(immutableNeighbor, nextDistance);
                queue.addLast(immutableNeighbor);
            }
        }

        List<RawCell> rawCells = new ArrayList<>(distances.size());
        int minU = Integer.MAX_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int maxV = Integer.MIN_VALUE;
        int furthestRevealDistance = 0;

        for (Map.Entry<BlockPos, Integer> entry : distances.entrySet()) {
            BlockPos position = entry.getKey();
            int u = coordinate(immutableOrigin, position, uDirection);
            int v = coordinate(immutableOrigin, position, vDirection);
            int revealDistance = entry.getValue();
            boolean projectorCell = position.equals(immutableOrigin);
            rawCells.add(new RawCell(position, u, v, revealDistance, projectorCell));
            minU = Math.min(minU, u);
            minV = Math.min(minV, v);
            maxU = Math.max(maxU, u + 1);
            maxV = Math.max(maxV, v + 1);
            furthestRevealDistance = Math.max(furthestRevealDistance, revealDistance);
        }

        rawCells.sort(Comparator.comparingInt(RawCell::u).thenComparingInt(RawCell::v));
        Bounds bounds = new Bounds(minU, minV, maxU, maxV);
        List<Cell> cells = new ArrayList<>(rawCells.size());
        List<Cell> glassCells = new ArrayList<>(Math.max(0, rawCells.size() - 1));

        for (RawCell rawCell : rawCells) {
            Cell cell = new Cell(
                    rawCell.position(),
                    rawCell.u(),
                    rawCell.v(),
                    rawCell.revealDistance(),
                    (float) (rawCell.u() - bounds.minU()) / bounds.width(),
                    (float) (rawCell.u() + 1 - bounds.minU()) / bounds.width(),
                    (float) (rawCell.v() - bounds.minV()) / bounds.height(),
                    (float) (rawCell.v() + 1 - bounds.minV()) / bounds.height(),
                    rawCell.projectorCell()
            );
            cells.add(cell);
            if (!cell.projectorCell()) {
                glassCells.add(cell);
            }
        }

        Set<BlockPos> frontier = new HashSet<>();
        for (BlockPos position : distances.keySet()) {
            for (Direction direction : traversalDirections) {
                BlockPos neighbor = position.relative(direction);
                if (!distances.containsKey(neighbor)) {
                    frontier.add(neighbor.immutable());
                }
            }
        }

        List<Cell> immutableCells = List.copyOf(cells);
        return new ProjectionSurface(
                immutableOrigin,
                facing,
                uDirection,
                vDirection,
                bounds,
                immutableCells,
                List.copyOf(glassCells),
                Set.copyOf(frontier),
                furthestRevealDistance,
                topologyHash(facing, immutableCells),
                version
        );
    }

    public boolean isTopologyValid(Direction facing, Predicate<BlockPos> isGlass) {
        Objects.requireNonNull(facing);
        Objects.requireNonNull(isGlass);
        if (this.facing != facing) {
            return false;
        }
        for (Cell cell : glassCells) {
            if (!isGlass.test(cell.position())) {
                return false;
            }
        }
        for (BlockPos position : frontier) {
            if (isGlass.test(position)) {
                return false;
            }
        }
        return true;
    }

    public BlockPos origin() {
        return origin;
    }

    public Direction facing() {
        return facing;
    }

    public Direction uDirection() {
        return uDirection;
    }

    public Direction vDirection() {
        return vDirection;
    }

    public Bounds bounds() {
        return bounds;
    }

    public List<Cell> cells() {
        return cells;
    }

    public List<Cell> glassCells() {
        return glassCells;
    }

    public int furthestRevealDistance() {
        return furthestRevealDistance;
    }

    public int completedRevealDistance() {
        return furthestRevealDistance + REVEAL_EDGE_WIDTH;
    }

    public long topologyHash() {
        return topologyHash;
    }

    public long version() {
        return version;
    }

    private static Direction uDirection(Direction facing) {
        return switch (facing) {
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            case SOUTH, DOWN, UP -> Direction.EAST;
            case NORTH -> Direction.WEST;
        };
    }

    private static Direction vDirection(Direction facing) {
        return switch (facing) {
            case WEST, EAST, SOUTH, NORTH -> Direction.UP;
            case DOWN -> Direction.SOUTH;
            case UP -> Direction.NORTH;
        };
    }

    private static int coordinate(BlockPos origin, BlockPos position, Direction direction) {
        int x = position.getX() - origin.getX();
        int y = position.getY() - origin.getY();
        int z = position.getZ() - origin.getZ();
        return x * direction.getStepX() + y * direction.getStepY() + z * direction.getStepZ();
    }

    private static long topologyHash(Direction facing, List<Cell> cells) {
        long hash = mix(FNV_OFFSET_BASIS, facing.get3DDataValue());
        hash = mix(hash, cells.size());
        for (Cell cell : cells) {
            hash = mix(hash, cell.u());
            hash = mix(hash, cell.v());
            hash = mix(hash, cell.revealDistance());
            hash = mix(hash, cell.projectorCell() ? 1 : 0);
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        long result = hash;
        int remaining = value;
        for (int index = 0; index < Integer.BYTES; index++) {
            result ^= remaining & 0xffL;
            result *= FNV_PRIME;
            remaining >>>= Byte.SIZE;
        }
        return result;
    }

    public record Bounds(int minU, int minV, int maxU, int maxV) {
        public Bounds {
            if (maxU <= minU || maxV <= minV) {
                throw new IllegalArgumentException("Projection surface bounds must have positive area");
            }
        }

        public int width() {
            return maxU - minU;
        }

        public int height() {
            return maxV - minV;
        }
    }

    public record Cell(
            BlockPos position,
            int u,
            int v,
            int revealDistance,
            float u0,
            float u1,
            float v0,
            float v1,
            boolean projectorCell
    ) {
        public Cell {
            position = position.immutable();
        }
    }

    private record RawCell(BlockPos position, int u, int v, int revealDistance, boolean projectorCell) {
    }
}
