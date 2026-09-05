package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;

public record RemoteWorldState(long gameTime, long dayTime, boolean tickDayTime, float rainLevel, float thunderLevel, int skyFlashTime) {
    public RemoteWorldState {
        rainLevel = Math.clamp(rainLevel, 0.0F, 1.0F);
        thunderLevel = Math.clamp(thunderLevel, 0.0F, 1.0F);
        skyFlashTime = Math.max(0, skyFlashTime);
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(gameTime);
        buf.writeLong(dayTime);
        buf.writeBoolean(tickDayTime);
        buf.writeFloat(rainLevel);
        buf.writeFloat(thunderLevel);
        buf.writeVarInt(skyFlashTime);
    }

    public static RemoteWorldState read(RegistryFriendlyByteBuf buf) {
        return new RemoteWorldState(buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt());
    }
}
