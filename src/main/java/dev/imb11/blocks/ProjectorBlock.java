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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class ProjectorBlock extends BlockWithEntity {

    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final DirectionProperty FACING = Properties.FACING;

    public ProjectorBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(POWERED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProjectorBlockEntity(pos, state);
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isReceivingRedstonePower(pos)) {
            world.playSound(pos.getX(), pos.getY(), pos.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.5f, 0.2f, true);
            setDefaultState(state.with(POWERED, true));
        } else {
            // Play a sound
            world.playSound(pos.getX(), pos.getY(), pos.getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 1.5f, 0.2f, true);
            setDefaultState(state.with(POWERED, false));
        }

        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        // You need a Block.createScreenHandlerFactory implementation that delegates to the block entity,
        // such as the one from BlockWithEntity

        if (world.isClient) {
            return ActionResult.PASS;
        }

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
        return this.getDefaultState().with(FACING, ctx.getPlayerLookDirection().getOpposite());
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return BlockWithEntity.checkType(type, ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntity::tick);
    }


}