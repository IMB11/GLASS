package dev.imb11.sync;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class ChannelManagerPersistence extends SavedData implements Collection<Channel> {
    public static SavedData.Factory<ChannelManagerPersistence> TYPE = new SavedData.Factory<ChannelManagerPersistence>(ChannelManagerPersistence::new, ChannelManagerPersistence::gather, null);

    public static ChannelManagerPersistence get(Level world) {
        return ((ServerLevel) world).getDataStorage().get(TYPE, "glass_channels");
    }

    private static final Logger LOGGER = LogManager.getLogger("ChannelManagerPersistence");

    public final Map<String, Channel> CHANNELS = new HashMap<>();

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        ListTag channels = new ListTag();

        for (Channel channel : CHANNELS.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("name", channel.name());
            if (channel.linkedBlock() != null)
                item.putIntArray("linked_pos", getIntArrayFromBlockPos(channel.linkedBlock()));
            channels.add(item);
        }

        nbt.put("channels", channels);

        return nbt;
    }

    @Override
    public void setDirty(boolean dirty) {
        LOGGER.info("Marked Dirty - " + CHANNELS);
        super.setDirty(dirty);
    }

    public static int[] getIntArrayFromBlockPos(BlockPos z) {
        return new int[]{z.getX(), z.getY(), z.getZ()};
    }

    public static BlockPos getFromIntArrayNBT(String key, CompoundTag compound) {
        int[] ints = compound.getIntArray(key);
        return new BlockPos(ints[0], ints[1], ints[2]);
    }

    public static ChannelManagerPersistence gather(CompoundTag compound, HolderLookup.Provider registryLookup) {
        ChannelManagerPersistence persistence = new ChannelManagerPersistence();

        ListTag channels = compound.getList("channels", Tag.TAG_COMPOUND);

        for (int i = 0; i < channels.size(); i++) {
            CompoundTag channel = channels.getCompound(i);

            @Nullable BlockPos bpos = (!channel.contains("linked_pos")) ? null : getFromIntArrayNBT("linked_pos", channel);
            Channel channel1 = new Channel(channel.getString("name"), bpos);
            persistence.CHANNELS.put(channel1.name(), channel1);
            LOGGER.info("Loaded Channel: " + channel1);
        }

        return persistence;
    }

    public static void init() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            SavedData state = world.getDataStorage().computeIfAbsent(TYPE, "glass_channels");
            LOGGER.info("Loaded ChannelManagerPersistence for: " + world.dimension().location() + " at " + world);
        });
    }

    @Override
    public int size() {
        return CHANNELS.size();
    }

    @Override
    public boolean isEmpty() {
        return CHANNELS.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return CHANNELS.containsValue(o);
    }

    public boolean containsName(String channelName) {
        return CHANNELS.containsKey(channelName);
    }

    @NotNull
    @Override
    public Iterator<Channel> iterator() {
        return CHANNELS.values().iterator();
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return CHANNELS.values().toArray();
    }

    @NotNull
    @Override
    public <T> T[] toArray(@NotNull T[] ts) {
        return CHANNELS.values().toArray(ts);
    }

    @Override
    public boolean add(Channel channel) {
        CHANNELS.put(channel.name(), channel);
        this.setDirty();
        return true;
    }

    @Override
    public boolean remove(Object o) {
        boolean e = CHANNELS.values().remove(o);
        this.setDirty();
        return e;
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> collection) {
        return CHANNELS.values().containsAll(collection);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Channel> collection) {
        for (Channel channel : collection) {
            CHANNELS.put(channel.name(), channel);
        }
        this.setDirty();
        return true;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        boolean e = CHANNELS.values().removeAll(collection);
        this.setDirty();
        return e;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        boolean e = CHANNELS.values().retainAll(collection);
        this.setDirty();
        return e;
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return CHANNELS.values().toArray(generator);
    }

    @Override
    public boolean removeIf(Predicate<? super Channel> filter) {
        boolean e = CHANNELS.values().removeIf(filter);
        this.setDirty();
        return e;
    }

    @Override
    public Spliterator<Channel> spliterator() {
        return CHANNELS.values().spliterator();
    }

    @Override
    public Stream<Channel> stream() {
        return CHANNELS.values().stream();
    }

    @Override
    public Stream<Channel> parallelStream() {
        return CHANNELS.values().parallelStream();
    }

    /**
     * @Deprecated Do not use.
     */
    @Override
    public void clear() {
        CHANNELS.clear();
        this.setDirty();
    }
}