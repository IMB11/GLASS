package dev.imb11.sync;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public record Channel(String name, @Nullable BlockPos linkedBlock, RegistryKey<World> dimension) {

    public static final Codec<Channel> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(Channel::name),
            BlockPos.CODEC.optionalFieldOf("linked_pos", null).forGetter(Channel::linkedBlock),
            World.CODEC.optionalFieldOf("dimension", World.OVERWORLD).forGetter(Channel::dimension)
    ).apply(instance, Channel::new));

    @Override
    public String toString() {
        return "{Name:" + name + ",LinkedBlock" + linkedBlock + "}";
    }
}