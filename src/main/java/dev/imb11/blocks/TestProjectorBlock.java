//package dev.imb11.blocks;
//
//import net.minecraft.block.BlockState;
//import net.minecraft.block.BlockWithEntity;
//import net.minecraft.block.entity.BlockEntity;
//import net.minecraft.block.entity.BlockEntityTicker;
//import net.minecraft.block.entity.BlockEntityType;
//import net.minecraft.util.math.BlockPos;
//import net.minecraft.world.World;
//import org.jetbrains.annotations.Nullable;
//
//public class TestProjectorBlock extends BlockWithEntity {
//    public TestProjectorBlock(Settings properties) {
//        super(properties);
//    }
//
//    @Override
//    public @Nullable BlockEntity createBlockEntity(BlockPos blockPos, BlockState blockState) {
//        return new TestProjectorBlockEntity(blockPos, blockState);
//    }
//
//    @Override
//    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
//        return (BlockEntityTicker<T>) TestProjectorBlockEntity::tick;
//    }
//}
