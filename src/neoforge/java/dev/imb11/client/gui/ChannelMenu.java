package dev.imb11.client.gui;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;

public abstract class ChannelMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final List<Channel> initialChannels;
    public final long initialRevision;
    private final ContainerLevelAccess access;
    private final Block block;

    protected ChannelMenu(MenuType<?> type, int id, Inventory inventory, BlockPos pos, CompoundTag data, Block block) {
        super(type, id);
        this.pos = pos.immutable();
        this.block = block;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);
        ChannelManagerPersistence snapshot = ChannelManagerPersistence.gather(data, inventory.player.registryAccess());
        this.initialChannels = snapshot.snapshot();
        this.initialRevision = snapshot.registryRevision();
    }

    public boolean isFor(BlockPos pos) {
        return this.pos.equals(pos);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, block);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }
}
