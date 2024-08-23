package dev.imb11.blocks;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ProjectorBlock extends BlockWithEntity {

    public static BooleanProperty POWERED = BooleanProperty.of("powered");

    private Direction _tempFacing = Direction.UP;

    public ProjectorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(POWERED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        ProjectorBlockEntity entity = new ProjectorBlockEntity(pos, state);
        entity.facing = _tempFacing;
        return entity;
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if(world.isReceivingRedstonePower(pos)) {
            setDefaultState(state.with(POWERED, true));
        } else {
            setDefaultState(state.with(POWERED, false));
        }

        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
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

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        _tempFacing = Direction.getLookDirectionForAxis(Objects.requireNonNull(ctx.getPlayer()), ctx.getPlayerLookDirection().getAxis()).getOpposite();
        return super.getPlacementState(ctx);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return BlockWithEntity.checkType(type, ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntity::tick);
    }


}