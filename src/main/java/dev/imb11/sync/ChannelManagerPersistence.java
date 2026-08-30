package dev.imb11.sync;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ChannelManagerPersistence extends SavedData {
    public static final int MAX_CHANNEL_NAME_LENGTH = 48;
    public static final int MAX_WIRE_CHANNEL_NAME_LENGTH = 64;
    public static final int MAX_CHANNELS = 256;
    public static final String DEFAULT_CHANNEL = "Default";
    public static final SavedData.Factory<ChannelManagerPersistence> TYPE = new SavedData.Factory<>(ChannelManagerPersistence::new, ChannelManagerPersistence::gather, null);
    private static final SavedData.Factory<LegacyChannelData> LEGACY_TYPE = new SavedData.Factory<>(LegacyChannelData::new, LegacyChannelData::gather, null);
    private static final String DATA_ID = "glass_channels";

    private Map<String, Channel> channels = Map.of();
    private Map<String, PendingLink> pendingLinks = Map.of();
    private Set<ResourceKey<Level>> importedLegacyDimensions = Set.of();
    private long registryRevision;
    private transient MinecraftServer server;

    public static ChannelManagerPersistence get(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Channel state is server-only");
        }
        return get(serverLevel.getServer());
    }

    public static ChannelManagerPersistence get(MinecraftServer server) {
        ChannelManagerPersistence persistence = server.overworld().getDataStorage().computeIfAbsent(TYPE, DATA_ID);
        persistence.server = server;
        persistence.ensureDefaultChannel();
        return persistence;
    }

    public static void init() {
        ServerWorldEvents.LOAD.register((server, world) -> defer(server, () -> {
            if (server.overworld() != null) {
                get(server).importLegacy(world);
            }
        }));
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof TerminalBlockEntity terminal) {
                defer(world.getServer(), () -> {
                    if (world.hasChunkAt(terminal.getBlockPos()) && world.getBlockEntity(terminal.getBlockPos()) == terminal) {
                        get(world).reconcileTerminal(terminal);
                    }
                });
            }
        });
    }

    public long registryRevision() {
        return registryRevision;
    }

    public List<Channel> snapshot() {
        return List.copyOf(channels.values());
    }

    public S2CChannelSnapshotPacket snapshotPacket() {
        return new S2CChannelSnapshotPacket(registryRevision, snapshot());
    }

    public Optional<Channel> channel(String name) {
        String canonical = canonicalChannelName(name);
        return canonical == null ? Optional.empty() : Optional.ofNullable(channels.get(canonical));
    }

    public Optional<ProjectionSource> resolve(String name) {
        return channel(name).map(Channel::source);
    }

    public boolean containsName(String name) {
        return channel(name).isPresent();
    }

    public boolean createChannel(String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || channels.containsKey(canonical) || channels.size() >= MAX_CHANNELS) {
            return false;
        }
        Map<String, Channel> next = mutableChannels();
        next.put(canonical, new Channel(canonical, null));
        commit(next, mutablePendingLinks(), nextRevision());
        return true;
    }

    public boolean deleteChannel(String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || DEFAULT_CHANNEL.equals(canonical)) {
            return false;
        }
        Channel removed = channels.get(canonical);
        PendingLink pending = pendingLinks.get(canonical);
        if (removed == null) {
            return false;
        }
        Map<String, Channel> next = mutableChannels();
        Map<String, PendingLink> nextPending = mutablePendingLinks();
        next.remove(canonical);
        nextPending.remove(canonical);
        commit(next, nextPending, nextRevision());
        if (removed.source() != null) {
            clearLoadedTerminal(removed.source().dimension(), removed.source().pos(), canonical);
        }
        if (pending != null) {
            clearLoadedTerminal(pending.dimension(), pending.pos(), canonical);
        }
        return true;
    }

    public boolean claimTerminal(ServerLevel level, BlockPos pos, Direction facing, String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || !channels.containsKey(canonical)) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();
        Channel selected = channels.get(canonical);
        ProjectionSource current = selected.source();
        if (current != null && !sameLocation(current.dimension(), current.pos(), level.dimension(), immutablePos)) {
            return false;
        }
        PendingLink selectedPending = pendingLinks.get(canonical);
        if (selectedPending != null && !sameLocation(selectedPending.dimension(), selectedPending.pos(), level.dimension(), immutablePos)) {
            return false;
        }

        Map<String, Channel> next = mutableChannels();
        Map<String, PendingLink> nextPending = mutablePendingLinks();
        boolean changed = false;

        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            ProjectionSource source = entry.getValue().source();
            if (source != null && sameLocation(source.dimension(), source.pos(), level.dimension(), immutablePos) && !entry.getKey().equals(canonical)) {
                next.put(entry.getKey(), new Channel(entry.getKey(), null));
                changed = true;
            }
        }

        boolean sourceMatches = current != null
                && sameLocation(current.dimension(), current.pos(), level.dimension(), immutablePos)
                && current.facing() == facing;
        if (!sourceMatches) {
            changed = true;
        }

        if (nextPending.entrySet().removeIf(entry -> entry.getKey().equals(canonical)
                || sameLocation(entry.getValue().dimension(), entry.getValue().pos(), level.dimension(), immutablePos))) {
            changed = true;
        }

        if (!changed) {
            return true;
        }

        long revision = nextRevision();
        next.put(canonical, new Channel(canonical, new ProjectionSource(canonical, level.dimension(), immutablePos, facing, revision)));
        commit(next, nextPending, revision);
        return true;
    }

    public boolean unlinkTerminal(ServerLevel level, BlockPos pos) {
        BlockPos immutablePos = pos.immutable();
        Map<String, Channel> next = mutableChannels();
        Map<String, PendingLink> nextPending = mutablePendingLinks();
        boolean changed = false;

        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            ProjectionSource source = entry.getValue().source();
            if (source != null && sameLocation(source.dimension(), source.pos(), level.dimension(), immutablePos)) {
                next.put(entry.getKey(), new Channel(entry.getKey(), null));
                changed = true;
            }
        }
        if (nextPending.entrySet().removeIf(entry -> sameLocation(entry.getValue().dimension(), entry.getValue().pos(), level.dimension(), immutablePos))) {
            changed = true;
        }
        if (changed) {
            commit(next, nextPending, nextRevision());
        }
        return changed;
    }

    public void reconcileTerminal(TerminalBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = terminal.getBlockPos();
        if (!level.hasChunkAt(pos)) {
            return;
        }
        var state = level.getBlockState(pos);
        if (!state.is(GBlocks.TERMINAL) || !state.hasProperty(TerminalBlock.FACING)) {
            return;
        }

        String desired = sourceChannelAt(level.dimension(), pos);
        if (desired == null) {
            desired = pendingChannelAt(level.dimension(), pos);
        }

        if (desired == null) {
            terminal.setChannelFromServer("");
            return;
        }

        if (claimTerminal(level, pos, state.getValue(TerminalBlock.FACING), desired)) {
            terminal.setChannelFromServer(desired);
        } else {
            terminal.setChannelFromServer("");
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putLong("registry_revision", registryRevision);
        ListTag channelTags = new ListTag();
        for (Channel channel : channels.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("name", channel.name());
            ProjectionSource source = channel.source();
            PendingLink pending = pendingLinks.get(channel.name());
            if (source != null) {
                writeLocation(item, source.dimension(), source.pos());
                item.putString("linked_facing", source.facing().getSerializedName());
                item.putLong("source_revision", source.revision());
            } else if (pending != null) {
                writeLocation(item, pending.dimension(), pending.pos());
            }
            channelTags.add(item);
        }
        tag.put("channels", channelTags);

        ListTag imported = new ListTag();
        for (ResourceKey<Level> dimension : importedLegacyDimensions) {
            imported.add(StringTag.valueOf(dimension.location().toString()));
        }
        tag.put("legacy_imported_dimensions", imported);
        return tag;
    }

    public static ChannelManagerPersistence gather(CompoundTag tag, HolderLookup.Provider registryLookup) {
        ChannelManagerPersistence persistence = new ChannelManagerPersistence();
        persistence.registryRevision = Math.max(0L, tag.getLong("registry_revision"));
        Map<String, Channel> loadedChannels = new LinkedHashMap<>();
        Map<String, PendingLink> loadedPending = new LinkedHashMap<>();
        ListTag channelTags = tag.getList("channels", Tag.TAG_COMPOUND);
        int count = Math.min(channelTags.size(), MAX_CHANNELS);
        for (int i = 0; i < count; i++) {
            CompoundTag item = channelTags.getCompound(i);
            String name = canonicalChannelName(item.getString("name"));
            if (name == null) {
                continue;
            }
            BlockPos pos = getFromIntArrayNBT("linked_pos", item);
            ResourceKey<Level> dimension = readDimension(item, "linked_dimension");
            Direction facing = readFacing(item.getString("linked_facing"));
            if (pos != null && dimension != null && facing != null) {
                long sourceRevision = Math.max(0L, item.getLong("source_revision"));
                persistence.registryRevision = Math.max(persistence.registryRevision, sourceRevision);
                loadedChannels.put(name, new Channel(name, new ProjectionSource(name, dimension, pos, facing, sourceRevision)));
                loadedPending.remove(name);
            } else {
                loadedChannels.put(name, new Channel(name, null));
                if (pos != null && dimension != null) {
                    loadedPending.put(name, new PendingLink(dimension, pos));
                } else {
                    loadedPending.remove(name);
                }
            }
        }
        persistence.channels = immutableMap(loadedChannels);
        persistence.pendingLinks = immutableMap(loadedPending);

        Set<ResourceKey<Level>> imported = new LinkedHashSet<>();
        ListTag importedTags = tag.getList("legacy_imported_dimensions", Tag.TAG_STRING);
        for (int i = 0; i < importedTags.size(); i++) {
            ResourceKey<Level> dimension = dimensionKey(ResourceLocation.tryParse(importedTags.getString(i)));
            if (dimension != null) {
                imported.add(dimension);
            }
        }
        persistence.importedLegacyDimensions = Collections.unmodifiableSet(imported);
        return persistence;
    }

    public static int[] getIntArrayFromBlockPos(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    @Nullable
    public static BlockPos getFromIntArrayNBT(String key, CompoundTag compound) {
        int[] values = compound.getIntArray(key);
        return values.length < 3 ? null : new BlockPos(values[0], values[1], values[2]);
    }

    @Nullable
    public static String canonicalChannelName(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder(normalized.length());
        boolean previousWhitespace = false;
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (Character.isISOControl(character)) {
                return null;
            }
            if (Character.isWhitespace(character)) {
                if (!previousWhitespace) {
                    result.append(' ');
                }
                previousWhitespace = true;
            } else {
                result.append(character);
                previousWhitespace = false;
            }
        }
        String canonical = result.toString();
        return canonical.length() > MAX_CHANNEL_NAME_LENGTH ? null : canonical;
    }

    private void ensureDefaultChannel() {
        if (!channels.containsKey(DEFAULT_CHANNEL)) {
            Map<String, Channel> ordered = new LinkedHashMap<>();
            ordered.put(DEFAULT_CHANNEL, new Channel(DEFAULT_CHANNEL, null));
            ordered.putAll(channels);
            commit(ordered, mutablePendingLinks(), nextRevision());
        }
    }

    private void importLegacy(ServerLevel world) {
        ResourceKey<Level> dimension = world.dimension();
        if (importedLegacyDimensions.contains(dimension)) {
            return;
        }
        if (dimension.equals(Level.OVERWORLD)) {
            markLegacyImported(dimension);
            return;
        }

        LegacyChannelData legacy = world.getDataStorage().computeIfAbsent(LEGACY_TYPE, DATA_ID);
        Map<String, Channel> next = mutableChannels();
        Map<String, PendingLink> nextPending = mutablePendingLinks();
        boolean registryChanged = false;
        for (LegacyEntry entry : legacy.entries()) {
            if (!next.containsKey(entry.name()) && next.size() < MAX_CHANNELS) {
                next.put(entry.name(), new Channel(entry.name(), null));
                registryChanged = true;
            }
            Channel channel = next.get(entry.name());
            if (entry.pos() != null && channel != null && channel.source() == null && !nextPending.containsKey(entry.name())) {
                nextPending.put(entry.name(), new PendingLink(dimension, entry.pos()));
                registryChanged = true;
            }
        }

        markLegacyImported(dimension);
        if (registryChanged) {
            commit(next, nextPending, nextRevision());
        }
    }

    private void markLegacyImported(ResourceKey<Level> dimension) {
        if (importedLegacyDimensions.contains(dimension)) {
            return;
        }
        Set<ResourceKey<Level>> next = new LinkedHashSet<>(importedLegacyDimensions);
        next.add(dimension);
        importedLegacyDimensions = Collections.unmodifiableSet(next);
        setDirty();
    }

    private void commit(Map<String, Channel> nextChannels, Map<String, PendingLink> nextPending, long revision) {
        channels = immutableMap(nextChannels);
        pendingLinks = immutableMap(nextPending);
        registryRevision = revision;
        setDirty();
        if (server != null) {
            GNetworking.broadcastSnapshot(server, this);
        }
    }

    private long nextRevision() {
        return registryRevision == Long.MAX_VALUE ? Long.MAX_VALUE : registryRevision + 1L;
    }

    private Map<String, Channel> mutableChannels() {
        return new LinkedHashMap<>(channels);
    }

    private Map<String, PendingLink> mutablePendingLinks() {
        return new LinkedHashMap<>(pendingLinks);
    }

    private String sourceChannelAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (Channel channel : channels.values()) {
            ProjectionSource source = channel.source();
            if (source != null && sameLocation(source.dimension(), source.pos(), dimension, pos)) {
                return channel.name();
            }
        }
        return null;
    }

    private String pendingChannelAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (Map.Entry<String, PendingLink> entry : pendingLinks.entrySet()) {
            PendingLink pending = entry.getValue();
            Channel channel = channels.get(entry.getKey());
            if (channel != null && channel.source() == null && sameLocation(pending.dimension(), pending.pos(), dimension, pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void clearLoadedTerminal(ResourceKey<Level> dimension, BlockPos pos, String channel) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal && channel.equals(terminal.getChannel())) {
            terminal.setChannelFromServer("");
        }
    }

    private static void writeLocation(CompoundTag tag, ResourceKey<Level> dimension, BlockPos pos) {
        tag.putIntArray("linked_pos", getIntArrayFromBlockPos(pos));
        tag.putString("linked_dimension", dimension.location().toString());
    }

    @Nullable
    private static ResourceKey<Level> readDimension(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return Level.OVERWORLD;
        }
        return dimensionKey(ResourceLocation.tryParse(tag.getString(key)));
    }

    @Nullable
    private static ResourceKey<Level> dimensionKey(@Nullable ResourceLocation location) {
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    @Nullable
    private static Direction readFacing(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    private static boolean sameLocation(ResourceKey<Level> firstDimension, BlockPos firstPos, ResourceKey<Level> secondDimension, BlockPos secondPos) {
        return firstDimension.equals(secondDimension) && firstPos.equals(secondPos);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static void defer(MinecraftServer server, Runnable runnable) {
        server.tell(new TickTask(server.getTickCount(), runnable));
    }

    private record PendingLink(ResourceKey<Level> dimension, BlockPos pos) {
        private PendingLink {
            pos = pos.immutable();
        }
    }

    private record LegacyEntry(String name, @Nullable BlockPos pos) {
        private LegacyEntry {
            if (pos != null) {
                pos = pos.immutable();
            }
        }
    }

    private static final class LegacyChannelData extends SavedData {
        private final List<LegacyEntry> entries;

        private LegacyChannelData() {
            entries = List.of();
        }

        private LegacyChannelData(List<LegacyEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        private List<LegacyEntry> entries() {
            return entries;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registryLookup) {
            return tag;
        }

        private static LegacyChannelData gather(CompoundTag tag, HolderLookup.Provider registryLookup) {
            List<LegacyEntry> entries = new ArrayList<>();
            ListTag channels = tag.getList("channels", Tag.TAG_COMPOUND);
            int count = Math.min(channels.size(), MAX_CHANNELS);
            for (int i = 0; i < count; i++) {
                CompoundTag channel = channels.getCompound(i);
                String name = canonicalChannelName(channel.getString("name"));
                BlockPos pos = getFromIntArrayNBT("linked_pos", channel);
                if (name != null) {
                    entries.add(new LegacyEntry(name, pos));
                }
            }
            return new LegacyChannelData(entries);
        }
    }
}
