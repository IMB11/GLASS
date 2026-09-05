package dev.imb11.mixins;

import dev.imb11.client.compat.SodiumCompatibility;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Shadow
    private List<SpriteContents> sprites;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void glass$animateProjectionTextures(CallbackInfo callbackInfo) {
        SodiumCompatibility.activateProjectionTextures(sprites);
    }
}
