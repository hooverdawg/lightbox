package net.hooverdawg.lightbox;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ViewerScreen extends Screen {
    private static final int TOP = 36, BTM = 40;

    private final Screen parent;
    private final Path file;
    private Identifier texId;
    private int imgW, imgH;

    public ViewerScreen(Screen parent, Path file) {
        super(Component.translatable("lightbox.screen.viewer"));
        this.parent = parent;
        this.file = file;
    }

    @Override
    protected void init() {
        // exit on top
        addRenderableWidget(Button.builder(Component.translatable("lightbox.button.back"), _ -> onClose())
                .bounds(width / 2 - 50, 8, 100, 20).build());

        // actions along bottom (unwired)
        String[] icons = {"rename", "favourite", "edit", "delete"};
        int gap = 4;
        int startX = (width - (icons.length * 20 + (icons.length - 1) * gap)) / 2;
        for (int i = 0; i < icons.length; i++) {
            Component tip = Component.translatable("lightbox.button." + icons[i]);
            Button b = SpriteIconButton.builder(tip, _ -> {}, true)
                    .width(20)
                    .sprite(Identifier.fromNamespaceAndPath("lightbox", "icon/" + icons[i]), 16, 16)
                    .build();
            b.setPosition(startX + i * (20 + gap), height - 28);
            b.setTooltip(Tooltip.create(tip));
            b.active = false; // until wired up
            addRenderableWidget(b);
        }

        if (texId == null) loadTexture(); // init also runs on resize
    }

    private void loadTexture() {
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(in);
            texId = Identifier.fromNamespaceAndPath("lightbox", "viewer");
            minecraft.getTextureManager().register(texId,
                    new DynamicTexture(() -> "lightbox viewer", img));
            imgW = img.getWidth();
            imgH = img.getHeight();
        } catch (IOException e) {
            texId = null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (texId == null) return;

        int availW = width - 20, availH = height - TOP - BTM;
        float scale = Math.min((float) availW / imgW, (float) availH / imgH);
        int w = (int) (imgW * scale), h = (int) (imgH * scale);
        int x = (width - w) / 2, y = TOP + (availH - h) / 2;

        graphics.blit(RenderPipelines.GUI_TEXTURED, texId, x, y, 0, 0, w, h, imgW, imgH, imgW, imgH);
        graphics.outline(x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);
        //will draw timestamp etc here
    }

    @Override
    public void removed() {
        if (texId != null) minecraft.getTextureManager().release(texId);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}

