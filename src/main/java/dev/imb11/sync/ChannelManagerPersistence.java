package dev.imb11.sync;

import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * @deprecated DO NOT USE IN CLIENT SITUATIONS WITH CLIENTWORLD, ALWAYS USE SERVERWORLD
 */
public class ChannelManagerPersistence extends PersistentState implements Collection<Channel> {

    public static ChannelManagerPersistence get(World world) {
        return (ChannelManagerPersistence) ((ServerWorld) world).getPersistentStateManager().get(ChannelManagerPersistence::gather, "glass_channels");
    }

    private static final Logger LOGGER = LogManager.getLogger("ChannelManagerPersistence");

    public final Map<String, Channel> CHANNELS = new HashMap<>();

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {

        NbtList channels = new NbtList();

        for (Channel channel : CHANNELS.values()) {
            var result = Channel.CODEC.encodeStart(NbtOps.INSTANCE, channel);
            result.resultOrPartial(LOGGER::warn).ifPresent(channels::add);
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

    public static BlockPos getFromIntArrayNBT(String key, NbtCompound compound) {
        int[] ints = compound.getIntArray(key);
        return new BlockPos(ints[0], ints[1], ints[2]);
    }

    public static PersistentState gather(NbtCompound compound) {

        ChannelManagerPersistence persistence = new ChannelManagerPersistence();

        NbtList channels = compound.getList("channels", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < channels.size(); i++) {
            NbtCompound channelNbt = channels.getCompound(i);

            Channel.CODEC.parse(NbtOps.INSTANCE, channelNbt)
                    .resultOrPartial(LOGGER::warn)
                    .ifPresent(channel -> {
                        persistence.CHANNELS.put(channel.name(), channel);
                        LOGGER.info("Loaded Channel: {}", channel);
                    });
        }

        return persistence;
    }

    public static void init() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            PersistentState state = world.getPersistentStateManager().getOrCreate(ChannelManagerPersistence::gather, ChannelManagerPersistence::new, "glass_channels");
            LOGGER.info("Loaded ChannelManagerPersistence for: " + world.getDimensionKey().getValue() + " at " + world);
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
        this.markDirty();
        return true;
    }

    @Override
    public boolean remove(Object o) {
        boolean e = CHANNELS.values().remove(o);
        this.markDirty();
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
        this.markDirty();
        return true;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        boolean e = CHANNELS.values().removeAll(collection);
        this.markDirty();
        return e;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        boolean e = CHANNELS.values().retainAll(collection);
        this.markDirty();
        return e;
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return CHANNELS.values().toArray(generator);
    }

    @Override
    public boolean removeIf(Predicate<? super Channel> filter) {
        boolean e = CHANNELS.values().removeIf(filter);
        this.markDirty();
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
        this.markDirty();
    }
}