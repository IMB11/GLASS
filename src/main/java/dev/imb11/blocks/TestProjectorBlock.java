//package dev.imb11.blocks;
//
//import net.minecraft.core.BlockPos;
//import net.minecraft.world.level.Level;
//import net.minecraft.world.level.block.BaseEntityBlock;
//import net.minecraft.world.level.block.entity.BlockEntity;
//import net.minecraft.world.level.block.entity.BlockEntityTicker;
//import net.minecraft.world.level.block.entity.BlockEntityType;
//import net.minecraft.world.level.block.state.BlockState;
//import org.jetbrains.annotations.Nullable;
//
//public class TestProjectorBlock extends BaseEntityBlock {
//    public TestProjectorBlock(Properties properties) {
//        super(properties);
//    }
//
//    @Override
//    public @Nullable BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
//        return new TestProjectorBlockEntity(blockPos, blockState);
//    }
//
//    @Override
//    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
//        return (BlockEntityTicker<T>) TestProjectorBlockEntity::tick;
//    }
//}
