package dev.imb11.mixins;

import dev.imb11.client.renderer.projection.ProjectionRenderRegion;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderChunkRegion.class)
abstract class RenderChunkRegionMixin implements ProjectionRenderRegion {
    @Unique
    private BlockPos glass$hiddenTerrainBlock;

    @Override
    public void glass$setHiddenTerrainBlock(BlockPos position) {
        glass$hiddenTerrainBlock = position == null ? null : position.immutable();
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void glass$hideProjectionTerminalState(
            BlockPos position,
            CallbackInfoReturnable<BlockState> callbackInfo
    ) {
        if (glass$isHiddenTerrainBlock(position)) {
            callbackInfo.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void glass$hideProjectionTerminalFluid(
            BlockPos position,
            CallbackInfoReturnable<FluidState> callbackInfo
    ) {
        if (glass$isHiddenTerrainBlock(position)) {
            callbackInfo.setReturnValue(Blocks.AIR.defaultBlockState().getFluidState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void glass$hideProjectionTerminalBlockEntity(
            BlockPos position,
            CallbackInfoReturnable<BlockEntity> callbackInfo
    ) {
        if (glass$isHiddenTerrainBlock(position)) {
            callbackInfo.setReturnValue(null);
        }
    }

    @Unique
    private boolean glass$isHiddenTerrainBlock(BlockPos position) {
        return glass$hiddenTerrainBlock != null && glass$hiddenTerrainBlock.equals(position);
    }
}
