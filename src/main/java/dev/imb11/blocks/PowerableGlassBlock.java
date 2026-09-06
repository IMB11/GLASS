package dev.imb11.blocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.TransparentBlock;

public class PowerableGlassBlock extends TransparentBlock {
    public static final MapCodec<PowerableGlassBlock> CODEC = simpleCodec(PowerableGlassBlock::new);

    public PowerableGlassBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }
}
