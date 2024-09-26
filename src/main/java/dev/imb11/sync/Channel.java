package dev.imb11.sync;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public record Channel(String name, @Nullable BlockPos linkedBlock) {
    @Override
    public String toString() {
        return "{Name:" + name + ",LinkedBlock" + linkedBlock + "}";
    }

    public Channel removeLinkedBlock() {
        return new Channel(name, null);
    }
}