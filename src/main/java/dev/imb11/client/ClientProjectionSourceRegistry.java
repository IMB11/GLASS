package dev.imb11.client;

import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientProjectionSourceRegistry {
    private static final long EMPTY_REVISION = -1L;
    private static volatile Snapshot snapshot = Snapshot.empty();

    private ClientProjectionSourceRegistry() {
    }

    public static long registryRevision() {
        return snapshot.registryRevision();
    }

    public static Map<String, Channel> channels() {
        return snapshot.channels();
    }

    public static Map<String, ProjectionSource> sourcesByChannel() {
        return snapshot.sourcesByChannel();
    }

    public static ProjectionSource resolve(String channel) {
        return channel == null ? null : snapshot.sourcesByChannel().get(channel);
    }

    public static synchronized boolean applySnapshot(S2CChannelSnapshotPacket packet) {
        return applySnapshot(packet.registryRevision(), packet.channels());
    }

    public static synchronized boolean applySnapshot(long registryRevision, List<Channel> channels) {
        Snapshot current = snapshot;
        if (registryRevision < current.registryRevision()) {
            return false;
        }

        Map<String, Channel> replacements = new LinkedHashMap<>();
        Map<String, ProjectionSource> sources = new LinkedHashMap<>();
        for (Channel channel : channels) {
            if (channel == null || channel.name() == null) {
                continue;
            }
            replacements.put(channel.name(), channel);
            ProjectionSource source = channel.source();
            if (source != null && channel.name().equals(source.channel())) {
                sources.put(channel.name(), source);
            }
        }

        Snapshot replacement = new Snapshot(registryRevision, replacements, sources);
        snapshot = replacement;
        ProjectionRenderManager.onRegistryReplaced(replacement.sourcesByChannel());
        return true;
    }

    public static synchronized void clear() {
        Snapshot replacement = Snapshot.empty();
        snapshot = replacement;
        ProjectionRenderManager.onRegistryReplaced(replacement.sourcesByChannel());
    }

    public record Snapshot(
            long registryRevision,
            Map<String, Channel> channels,
            Map<String, ProjectionSource> sourcesByChannel
    ) {
        public Snapshot {
            channels = Map.copyOf(channels);
            sourcesByChannel = Map.copyOf(sourcesByChannel);
        }

        private static Snapshot empty() {
            return new Snapshot(EMPTY_REVISION, Map.of(), Map.of());
        }
    }
}
