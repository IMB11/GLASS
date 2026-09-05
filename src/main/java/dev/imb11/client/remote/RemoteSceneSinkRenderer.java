package dev.imb11.client.remote;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

final class RemoteSceneSinkRenderer extends LevelRenderer {
    private RemoteClientScene scene;

    RemoteSceneSinkRenderer(Minecraft minecraft, RenderBuffers renderBuffers) {
        super(
                minecraft,
                minecraft.getEntityRenderDispatcher(),
                minecraft.getBlockEntityRenderDispatcher(),
                renderBuffers
        );
    }

    void bind(RemoteClientScene scene) {
        this.scene = scene;
    }

    @Override
    public void blockChanged(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        if (scene != null) {
            scene.dirtyBlock(pos, oldState, newState);
        }
    }

    @Override
    public void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (scene != null) {
            scene.dirtyBlocks(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    @Override
    public void setBlockDirty(BlockPos pos, BlockState oldState, BlockState newState) {
        if (scene != null) {
            scene.dirtyBlock(pos, oldState, newState);
        }
    }

    @Override
    public void setSectionDirtyWithNeighbors(int sectionX, int sectionY, int sectionZ) {
        if (scene != null) {
            scene.dirtySectionWithNeighbors(sectionX, sectionY, sectionZ);
        }
    }

    @Override
    public void setSectionDirty(int sectionX, int sectionY, int sectionZ) {
        if (scene != null) {
            scene.dirtySection(sectionX, sectionY, sectionZ);
        }
    }

    @Override
    public void onChunkLoaded(ChunkPos pos) {
        if (scene != null) {
            scene.chunkLoaded(pos);
        }
    }
}
