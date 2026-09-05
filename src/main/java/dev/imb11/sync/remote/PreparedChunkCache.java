package dev.imb11.sync.remote;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class PreparedChunkCache {
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_BYTES = 32L * 1024L * 1024L;
    private static final int RETENTION_TICKS = 600;
    private final Map<Key, PreparedChunk> chunks = new LinkedHashMap<>(16, 0.75F, true);
    private long bytes;

    PreparedChunk find(ServerLevel level, LevelChunk chunk) {
        Key key = new Key(level.dimension(), chunk.getPos().toLong());
        PreparedChunk prepared = chunks.get(key);
        if (prepared != null && (prepared.chunk.get() != chunk
                || (!chunk.getBlockEntities().isEmpty() && prepared.createdTick != level.getServer().getTickCount())
                || level.getServer().getTickCount() - prepared.lastUsedTick > RETENTION_TICKS)) {
            invalidate(level.dimension(), chunk.getPos());
            return null;
        }
        if (prepared != null) {
            prepared.lastUsedTick = level.getServer().getTickCount();
        }
        return prepared;
    }

    PreparedChunk prepare(ServerLevel level, LevelChunk chunk) {
        PreparedChunk existing = find(level, chunk);
        if (existing != null) {
            return existing;
        }
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        int size;
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            size = buffer.readableBytes();
            if (size > S2CRemoteChunkPacket.MAX_BYTES) {
                throw new IllegalArgumentException("Projection chunk exceeds packet limit");
            }
        } finally {
            buffer.release();
        }
        PreparedChunk prepared = new PreparedChunk(chunk, packet, size, level.getServer().getTickCount());
        chunks.put(new Key(level.dimension(), chunk.getPos().toLong()), prepared);
        bytes += prepared.bytes();
        trim(level.getServer().getTickCount());
        return prepared;
    }

    void invalidate(ResourceKey<Level> dimension, ChunkPos pos) {
        PreparedChunk removed = chunks.remove(new Key(dimension, pos.toLong()));
        if (removed != null) {
            bytes -= removed.bytes();
        }
    }

    void unload(ResourceKey<Level> dimension) {
        Iterator<Map.Entry<Key, PreparedChunk>> iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, PreparedChunk> entry = iterator.next();
            if (entry.getKey().dimension().equals(dimension)) {
                bytes -= entry.getValue().bytes();
                iterator.remove();
            }
        }
    }

    void trim(int now) {
        Iterator<PreparedChunk> iterator = chunks.values().iterator();
        while (iterator.hasNext()) {
            PreparedChunk chunk = iterator.next();
            if (now - chunk.lastUsedTick > RETENTION_TICKS || chunk.chunk.get() == null
                    || chunks.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
                bytes -= chunk.bytes();
                iterator.remove();
            }
        }
    }

    private record Key(ResourceKey<Level> dimension, long chunk) {
    }

    static final class PreparedChunk {
        private final WeakReference<LevelChunk> chunk;
        private final ClientboundLevelChunkWithLightPacket data;
        private final int size;
        private final int createdTick;
        private int lastUsedTick;

        private PreparedChunk(LevelChunk chunk, ClientboundLevelChunkWithLightPacket data, int size, int now) {
            this.chunk = new WeakReference<>(chunk);
            this.data = data;
            this.size = size;
            this.createdTick = now;
            this.lastUsedTick = now;
        }

        S2CRemoteChunkPacket packet(RemoteSubscriptionId subscription, long sequence, ChunkPos pos) {
            return new S2CRemoteChunkPacket(subscription, sequence, data, size);
        }

        private long bytes() {
            return size;
        }
    }
}
