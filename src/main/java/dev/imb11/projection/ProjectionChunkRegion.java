package dev.imb11.projection;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;

public record ProjectionChunkRegion(ChunkPos center, int radius) {
    public static final int NEIGHBOR_PADDING = 1;

    public static ChunkPos cameraCenter(Vec3 position) {
        return new ChunkPos(SectionPos.posToSectionCoord(position.x), SectionPos.posToSectionCoord(position.z));
    }

    public static double gridAnchor(double coordinate) {
        return SectionPos.sectionToBlockCoord(SectionPos.posToSectionCoord(coordinate)) + 8.0D;
    }

    public boolean contains(int x, int z) {
        return Math.abs((long) x - center.x) <= radius && Math.abs((long) z - center.z) <= radius;
    }

    public ProjectionChunkRegion withNeighbors() {
        return new ProjectionChunkRegion(center, radius + NEIGHBOR_PADDING);
    }

    public Set<ChunkPos> chunks() {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }
}
