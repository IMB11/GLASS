//package dev.imb11.client.renderer;
//
//import com.mojang.blaze3d.systems.RenderSystem;
//import dev.imb11.blocks.TestProjectorBlockEntity;
//import com.mojang.blaze3d.pipeline.TextureTarget;
//import com.mojang.blaze3d.vertex.BufferBuilder;
//import com.mojang.blaze3d.vertex.BufferUploader;
//import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//import com.mojang.blaze3d.vertex.PoseStack;
//import com.mojang.blaze3d.vertex.Tesselator;
//import com.mojang.blaze3d.vertex.VertexFormat;
//import net.minecraft.client.Minecraft;
//import net.minecraft.client.renderer.FogRenderer;
//import net.minecraft.client.renderer.GameRenderer;
//import net.minecraft.client.renderer.MultiBufferSource;
//import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
//import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
//import org.jetbrains.annotations.NotNull;
//import org.joml.Matrix4f;
//
//public class TestProjectorBlockEntityRenderer implements BlockEntityRenderer<TestProjectorBlockEntity>, BlockEntityRendererProvider<TestProjectorBlockEntity> {
//    private TextureTarget targetFramebuffer = null;
//    private boolean isRendering = false;
//    @Override
//    public void render(TestProjectorBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferProvider, int packedLight, int packedOverlay) {
//        if(isRendering) return;
//        isRendering = true;
//        var client = Minecraft.getInstance();
//        if(targetFramebuffer == null) {
//            targetFramebuffer = new TextureTarget(client.getWindow().getWidth(), client.getWindow().getHeight(), true, Minecraft.ON_OSX);
//        }
//
//        ProjectorRenderingHelper.captureWorld(blockEntity.getBlockPos().offset(15, 15, 15), targetFramebuffer, client);
//
//        poseStack.pushPose();
//        Matrix4f positionMatrix = poseStack.last().pose();
//        Tesselator tessellator = Tesselator.getInstance();
//
//        // Draw a full screen white quad to prevent behind the block showing through
//        float backgroundR = FogRenderer.fogRed;
//        float backgroundG = FogRenderer.fogGreen;
//        float backgroundB = FogRenderer.fogBlue;
//
//        BufferBuilder backgroundBuffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
//        backgroundBuffer.addVertex(positionMatrix, 0, 1, 0).setColor(backgroundR, backgroundG, backgroundB, 1f);
//        backgroundBuffer.addVertex(positionMatrix, 0, 0, 0).setColor(backgroundR, backgroundG, backgroundB, 1f);
//        backgroundBuffer.addVertex(positionMatrix, 1, 0, 0).setColor(backgroundR, backgroundG, backgroundB, 1f);
//        backgroundBuffer.addVertex(positionMatrix, 1, 1, 0).setColor(backgroundR, backgroundG, backgroundB, 1f);
//        RenderSystem.setShader(GameRenderer::getPositionColorShader);
//        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
//        RenderSystem.disableCull();
//        BufferUploader.drawWithShader(backgroundBuffer.buildOrThrow());
//        RenderSystem.enableCull();
//
//        BufferBuilder buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
//        buffer.addVertex(positionMatrix, 0, 1, 0).setUv(0f, 1f);
//        buffer.addVertex(positionMatrix, 0, 0, 0).setUv(0f, 0f);
//        buffer.addVertex(positionMatrix, 1, 0, 0).setUv(1f, 0f);
//        buffer.addVertex(positionMatrix, 1, 1, 0).setUv(1f, 1f);
//
//        RenderSystem.setShader(GameRenderer::getPositionTexShader);
//        RenderSystem.setShaderTexture(0, targetFramebuffer.getColorTextureId());
//        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
//        RenderSystem.disableCull();
//        BufferUploader.drawWithShader(buffer.buildOrThrow());
//        RenderSystem.enableCull();
//
//        isRendering = false;
//
//        poseStack.popPose();
//    }
//
//    @Override
//    public @NotNull BlockEntityRenderer<TestProjectorBlockEntity> create(BlockEntityRendererProvider.Context context) {
//        return this;
//    }
//}
