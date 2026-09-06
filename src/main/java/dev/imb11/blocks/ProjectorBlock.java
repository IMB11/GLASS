package dev.imb11.blocks;

import com.mojang.serialization.MapCodec;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.platform.ExtendedMenuProvider;
import dev.imb11.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class ProjectorBlock extends BaseEntityBlock {

    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public ProjectorBlock(Properties settings) {
        super(settings);
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProjectorBlockEntity(pos, state);
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (!world.isClientSide) {
            boolean powered = world.hasNeighborSignal(pos);
            if (state.getValue(POWERED) != powered) {
                world.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
            }
        }

        super.neighborChanged(state, world, pos, sourceBlock, sourcePos, notify);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {

        if (world.isClientSide) {
            return InteractionResult.PASS;
        }

        ExtendedMenuProvider<?> handlerFactory = (ExtendedMenuProvider<?>) state.getMenuProvider(world, pos);

        Platform.openMenu(player, handlerFactory);

        return InteractionResult.SUCCESS;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return adjacentState.is(this) || adjacentState.is(GBlocks.POWERABLE_GLASS)
                || super.skipRendering(state, adjacentState, direction);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState()
                .setValue(FACING, ctx.getNearestLookingDirection().getOpposite())
                .setValue(POWERED, ctx.getLevel().hasNeighborSignal(ctx.getClickedPos()));
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return BaseEntityBlock.createTickerHelper(type, ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntity::tick);
    }
}
