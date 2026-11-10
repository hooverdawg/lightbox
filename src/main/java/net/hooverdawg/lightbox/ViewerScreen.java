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
    private static final int TOP = 46, BTM = 66;
    private final java.util.List<Component> info = new java.util.ArrayList<>();

    private final Screen parent;
    private final Path file;
    private Identifier texId;
    private int imgW, imgH;

    public ViewerScreen(Screen parent, Path file) {
        super(Component.translatable("lightbox.screen.viewer"));
        this.parent = parent;
        this.file = file;

        // build info lines
        ScreenshotMeta meta = MetaStore.get(file.getFileName().toString());
        long ms;
        try { ms = meta != null ? meta.time() : Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) {ms = 0;}
        String when = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(ms));

        if (meta != null) {
            info.add(Component.translatable("lightbox.viewer.taken", when, meta.world()));
            info.add(Component.translatable("lightbox.viewer.coords",
                    String.format("%.1f", meta.x()), String.format("%.1f", meta.y()), String.format("%.1f", meta.z()),
                    String.format("%.1f", meta.yaw()), String.format("%.1f", meta.pitch())));
        } else {
            info.add(Component.literal(when));
            info.add(Component.translatable("lightbox.viewer.nodata"));
        }
    }

    @Override
    protected void init() {
        // exit on top
        addRenderableWidget(Button.builder(Component.translatable("lightbox.button.back"), _ -> onClose())
                .bounds(width / 2 - 50, 8, 100, 20).build());

        // actions along bottom (unwired)
        String[] icons = {"rename", "favourite", "edit", "delete"};
        boolean isFav = Favourites.isFav(file.getFileName().toString());
        int gap = 4;
        int startX = (width - (icons.length * 20 + (icons.length - 1) * gap)) / 2;
        for (int i = 0; i < icons.length; i++) {
            boolean favBtn = icons[i].equals("favourite");
            boolean delBtn = icons[i].equals("delete");
            boolean renBtn = icons[i].equals("rename");
            boolean ediBtn = icons[i].equals("edit");

            Component tip = Component.translatable(
                favBtn && isFav ? "lightbox.button.unfavourite"
                : delBtn && isFav ? "lightbox.button.delete.locked"
                : "lightbox.button." + icons[i]
            );

            Button.OnPress press = _ -> {};
            if (favBtn) press = _ -> toggleFav();
            if (delBtn) press = _ -> Actions.askDelete(minecraft, file, this, parent);
            if (renBtn) press = _ -> Actions.rename(minecraft, this, file, t -> minecraft.gui.setScreen(new ViewerScreen(parent, t)));
            if (ediBtn) press = _ -> minecraft.gui.setScreen(new EditorScreen(this, file));

            Button b = SpriteIconButton.builder(tip, press, true)
                    .width(20)
                    .sprite(Identifier.fromNamespaceAndPath("lightbox", "icon/" + icons[i]), 16, 16)
                    .build();
            b.setPosition(startX + i * (20 + gap), height - 28);
            b.setTooltip(Tooltip.create(tip));
            b.active = favBtn || renBtn || ediBtn || (delBtn && !isFav); // delete greyed out for favourites
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

        String name = file.getFileName().toString();
        graphics.text(font, name, (width - font.width(name)) / 2, 32, 0xFFFFFFFF, true);
        if (Favourites.isFav(name)) {
            int sx = (width + font.width(name)) / 2 + 4;
            graphics.blit(RenderPipelines.GUI_TEXTURED, Favourites.STAR, sx, 28, 0, 0, 16, 16, 16, 16);
        }

        if (texId == null) return;

        int availW = width - 20, availH = height - TOP - BTM;
        float scale = Math.min((float) availW / imgW, (float) availH / imgH);
        int w = (int) (imgW * scale), h = (int) (imgH * scale);
        int x = (width - w) / 2, y = TOP + (availH - h) / 2;

        graphics.blit(RenderPipelines.GUI_TEXTURED, texId, x, y, 0, 0, w, h, imgW, imgH, imgW, imgH);
        graphics.outline(x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);

        // translucent strip behind info for readability
        int stripTop = height - BTM + 2;
        graphics.fill(0, stripTop, width, stripTop + info.size() * 12 + 4, 0x90000000);

        int ty = height - BTM + 6;
        for (Component line : info) {
            graphics.text(font, line, (width - font.width(line)) / 2, ty, 0xFFFFFFFF, true);
            ty += 12;
        }
    }

    private void toggleFav() {
        Favourites.toggle(file.getFileName().toString());
        rebuildWidgets(); // re-runs init() so tooltip updates. image isn't reloaded
    }

    @Override
    public void removed() {
        if (texId != null) {
            minecraft.getTextureManager().release(texId);
            texId = null; // reloaded if return
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}

