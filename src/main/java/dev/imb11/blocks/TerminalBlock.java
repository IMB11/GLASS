package dev.imb11.blocks;

import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class TerminalBlock extends BlockWithEntity {
    public TerminalBlock(Settings settings) {
        super(settings);
    }

    private Direction _tempFacing = Direction.UP;

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        var entity = new TerminalBlockEntity(pos, state);
        entity.facing = _tempFacing;
        return entity;
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        super.onBreak(world, pos, state, player);

        if(world.isClient) return;

        ChannelManagerPersistence persistence = ChannelManagerPersistence.get(world);

        for (Channel channel : persistence.CHANNELS) {
            if(channel.linkedBlock() != null) {
                if(channel.linkedBlock().asLong() == pos.asLong()) {
                    persistence.remove(channel);

                    // Unlink if break.
                    persistence.add(new Channel(channel.name(), null));
                }
            }
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        // You need a Block.createScreenHandlerFactory implementation that delegates to the block entity,
        // such as the one from BlockWithEntity

        if(world.isClient) return ActionResult.PASS;

        ExtendedScreenHandlerFactory handlerFactory = (ExtendedScreenHandlerFactory) state.createScreenHandlerFactory(world, pos);

        player.openHandledScreen(handlerFactory);

        return ActionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        _tempFacing = Direction.getLookDirectionForAxis(Objects.requireNonNull(ctx.getPlayer()), ctx.getPlayerLookDirection().getAxis());
        return super.getPlacementState(ctx);
    }
}