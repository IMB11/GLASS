//package dev.imb11.client.renderer;
//
//import com.mojang.blaze3d.systems.RenderSystem;
//import dev.imb11.blocks.TestProjectorBlockEntity;
//import net.minecraft.client.MinecraftClient;
//import net.minecraft.client.gl.SimpleFramebuffer;
//import net.minecraft.client.render.*;
//import net.minecraft.client.render.block.entity.BlockEntityRenderer;
//import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
//import net.minecraft.client.util.math.MatrixStack;
//import org.jetbrains.annotations.NotNull;
//import org.joml.Matrix4f;
//
//public class TestProjectorBlockEntityRenderer implements BlockEntityRenderer<TestProjectorBlockEntity>, BlockEntityRendererFactory<TestProjectorBlockEntity> {
//    private SimpleFramebuffer targetFramebuffer = null;
//    private boolean isRendering = false;
//    @Override
//    public void render(TestProjectorBlockEntity blockEntity, float partialTick, MatrixStack poseStack, VertexConsumerProvider bufferProvider, int packedLight, int packedOverlay) {
//        if(isRendering) return;
//        isRendering = true;
//        var client = MinecraftClient.getInstance();
//        if(targetFramebuffer == null) {
//            targetFramebuffer = new SimpleFramebuffer(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight(), true, MinecraftClient.IS_SYSTEM_MAC);
//        }
//
//        ProjectorRenderingHelper.captureWorld(blockEntity.getPos().add(15, 15, 15), targetFramebuffer, client);
//
//        poseStack.push();
//        Matrix4f positionMatrix = poseStack.peek().getPositionMatrix();
//        Tessellator tessellator = Tessellator.getInstance();
//
//        // Draw a full screen white quad to prevent behind the block showing through
//        float backgroundR = BackgroundRenderer.red;
//        float backgroundG = BackgroundRenderer.green;
//        float backgroundB = BackgroundRenderer.blue;
//
//        BufferBuilder backgroundBuffer = tessellator.getBuffer();
//        backgroundBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
//        backgroundBuffer.vertex(positionMatrix, 0, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
//        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
//        backgroundBuffer.vertex(positionMatrix, 1, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
//        backgroundBuffer.vertex(positionMatrix, 1, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
//        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
//        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
//        RenderSystem.disableCull();
//        tessellator.draw();
//        RenderSystem.enableCull();
//
//        BufferBuilder buffer = tessellator.getBuffer();
//
//        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
//        buffer.vertex(positionMatrix, 0, 1, 0).texture(0f, 1f).next();
//        buffer.vertex(positionMatrix, 0, 0, 0).texture(0f, 0f).next();
//        buffer.vertex(positionMatrix, 1, 0, 0).texture(1f, 0f).next();
//        buffer.vertex(positionMatrix, 1, 1, 0).texture(1f, 1f).next();
//
//        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
//        RenderSystem.setShaderTexture(0, targetFramebuffer.getColorAttachment());
//        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
//        RenderSystem.disableCull();
//        tessellator.draw();
//        RenderSystem.enableCull();
//
//        isRendering = false;
//
//        poseStack.pop();
//    }
//
//    @Override
//    public @NotNull BlockEntityRenderer<TestProjectorBlockEntity> create(Context context) {
//        return this;
//    }
//}
