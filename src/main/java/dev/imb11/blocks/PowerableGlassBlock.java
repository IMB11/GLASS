package dev.imb11.blocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

public class PowerableGlassBlock extends TransparentBlock {
    public static final MapCodec<PowerableGlassBlock> CODEC = simpleCodec(PowerableGlassBlock::new);

    public PowerableGlassBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return adjacentState.is(GBlocks.PROJECTOR) || super.skipRendering(state, adjacentState, direction);
    }
}
