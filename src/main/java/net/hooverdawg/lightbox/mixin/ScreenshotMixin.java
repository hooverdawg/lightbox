package net.hooverdawg.lightbox.mixin;

import net.hooverdawg.lightbox.MetaStore;
import net.hooverdawg.lightbox.ScreenshotMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
public class ScreenshotMixin {

	@ModifyVariable(
			method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
			at = @At("HEAD"),
			argsOnly = true)
	private static Consumer<Component> lightbox$wrapCallback(Consumer<Component> callback) {
		ScreenshotMeta meta = MetaStore.capture(Minecraft.getInstance()); // location
		if (meta == null) return callback;

		return message -> {
			if (message.getContents() instanceof TranslatableContents tc
					&& tc.getKey().equals("screenshot.success")
					&& tc.getArgs().length > 0
					&& tc.getArgs()[0] instanceof Component name) {
				MetaStore.put(name.getString(), meta); // name = saved file's name
			}
			callback.accept(message);
		};
	}
}