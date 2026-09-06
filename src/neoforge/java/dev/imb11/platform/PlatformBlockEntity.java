package dev.imb11.platform;

import dev.imb11.client.GlassClient;
import dev.imb11.sync.ChannelManagerPersistence;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class PlatformBlockEntity extends BlockEntity {
    protected PlatformBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            ChannelManagerPersistence.onBlockEntityLoad(this, serverLevel);
        } else if (level != null && level.isClientSide) {
            ClientHooks.loaded(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            ClientHooks.unloaded(this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && level.isClientSide) {
            ClientHooks.unloaded(this);
        }
        super.onChunkUnloaded();
    }

    private static final class ClientHooks {
        private static void loaded(BlockEntity blockEntity) {
            GlassClient.onBlockEntityLoad(blockEntity, (ClientLevel) blockEntity.getLevel());
        }

        private static void unloaded(BlockEntity blockEntity) {
            GlassClient.onBlockEntityUnload(blockEntity, (ClientLevel) blockEntity.getLevel());
        }
    }
}
