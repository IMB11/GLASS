package dev.imb11.blocks;

import com.mojang.serialization.MapCodec;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class TerminalBlock extends BaseEntityBlock {
    public TerminalBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return null;
    }

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
        super.playerWillDestroy(world, pos, state, player);

        if (world.isClientSide) {
            return state;
        }

        ChannelManagerPersistence persistence = ChannelManagerPersistence.get(world);

        var iterator = persistence.CHANNELS.values().iterator();
        while (iterator.hasNext()) {
            Channel channel = iterator.next();
            if (channel.linkedBlock() != null && channel.linkedBlock().asLong() != pos.asLong()) {
                iterator.remove();
            }
        }
        return state;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {

        if (world.isClientSide) {
            return InteractionResult.PASS;
        }

        ExtendedScreenHandlerFactory handlerFactory = (ExtendedScreenHandlerFactory) state.getMenuProvider(world, pos);

        player.openMenu(handlerFactory);

        return InteractionResult.SUCCESS;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection());
    }
}
