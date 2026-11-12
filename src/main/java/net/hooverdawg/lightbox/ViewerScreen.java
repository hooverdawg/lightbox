package net.hooverdawg.lightbox;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ViewerScreen extends Screen {
    private static final int TOP = 46, BTM = 66;
    private final java.util.List<Component> info = new java.util.ArrayList<>();

    private final Screen parent;
    private final Path file;
    private Identifier texId;
    private int imgW, imgH;

    private static final float MAX_ZOOM = 16f;
    private float zoom = 1f;
    private double panX = 0, panY = 0;
    private boolean panning = false;
    private double lastMx, lastMy;

    private final List<Path> all;
    private final int index;
    private final Component counter;

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

        this.all = Actions.listScreenshots();
        this.index = findIndex(all, file);
        this.counter = index >= 0
                ? Component.translatable("lightbox.viewer.counter", index + 1, all.size())
                : Component.empty();
    }

    @Override
    protected void init() {
        // exit and folderBtn on top
        addRenderableWidget(Button.builder(Component.translatable("lightbox.button.back"), _ -> onClose())
                .bounds(10, 8, 100, 20).build());

        addRenderableWidget(Actions.iconButton("folder", "lightbox.button.folder",
                width - 30, 8, _ -> Actions.openFolder()));

        // actions along bottom
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
            if (ediBtn) press = _ -> minecraft.gui.setScreen(new EditorScreen(this, file,
                    saved -> minecraft.gui.setScreen(new ViewerScreen(parent, saved))));

            int arrowY = TOP + (height - TOP - BTM) / 2 - 10;
            Button prev = Actions.iconButton("prev", "lightbox.button.prev", 8, arrowY, _ -> go(-1));
            Button next = Actions.iconButton("next", "lightbox.button.next", width - 28, arrowY, _ -> go(1));
            prev.active = index > 0;
            next.active = index >= 0 && index < all.size() - 1;
            addRenderableWidget(prev);
            addRenderableWidget(next);

            Button b = SpriteIconButton.builder(tip, press, true)
                    .width(20)
                    .sprite(Lightbox.id( "icon/" + icons[i]), 16, 16)
                    .build();
            b.setPosition(startX + i * (20 + gap), height - 28);
            b.setTooltip(Tooltip.create(tip));
            b.active = favBtn || renBtn || ediBtn || (delBtn && !isFav); // delete greyed out for favourites
            addRenderableWidget(b);
        }

        if (texId == null) loadTexture();
    }

    private void loadTexture() {
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(in);
            texId = Lightbox.id( "viewer");
            minecraft.getTextureManager().register(texId, new DynamicTexture(() -> "lightbox viewer", img));
            imgW = img.getWidth();
            imgH = img.getHeight();
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't load {}", file, e);
            texId = null;
        }
    }

    private float fit() {
        return Math.min((float) (width - 60) / imgW, (float) (height - TOP - BTM) / imgH);
    }

    private int vw() { return Math.round(imgW * fit()); }
    private int vh() { return Math.round(imgH * fit()); }
    private int vx() { return (width - vw()) / 2; }
    private int vy() { return TOP + ((height - TOP - BTM) - vh()) / 2; }

    private void clampPan() {
        if (imgW <= 0 || imgH <= 0) return;
        float s = fit() * zoom;
        double maxX = Math.max(0, (imgW * s - vw()) / 2.0);
        double maxY = Math.max(0, (imgH * s - vh()) / 2.0);
        panX = Math.clamp(panX, -maxX, maxX);
        panY = Math.clamp(panY, -maxY, maxY);
    }

    private boolean inViewport(double mx, double my) {
        return mx >= vx() && mx < vx() + vw() && my >= vy() && my < vy() + vh();
    }

    private static int findIndex(List<Path> list, Path file) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getFileName().equals(file.getFileName())) return i;
        }
        return -1;
    }

    private void go(int delta) {
        int n = index + delta;
        if (index < 0 || n < 0 || n >= all.size()) return;
        minecraft.gui.setScreen(new ViewerScreen(parent, all.get(n)));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_LEFT | event.key() == GLFW.GLFW_KEY_A) { go(-1); return true; }
        if (event.key() == GLFW.GLFW_KEY_RIGHT | event.key() == GLFW.GLFW_KEY_D) { go(1); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (texId == null || !inViewport(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        float newZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * (float) Math.pow(1.25, scrollY)));
        float f = newZoom / zoom;
        double cx = vx() + vw() / 2.0, cy = vy() + vh() / 2.0;
        panX = (mouseX - cx) - (mouseX - cx - panX) * f;
        panY = (mouseY - cy) - (mouseY - cy - panY) * f;
        zoom = newZoom;
        clampPan();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0 && texId != null && inViewport(event.x(), event.y())) {
            if (doubleClick) {
                zoom = 1f;
                panX = 0;
                panY = 0;
            } else {
                panning = true;
                lastMx = event.x();
                lastMy = event.y();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (panning) {
            panX += event.x() - lastMx;
            panY += event.y() - lastMy;
            lastMx = event.x();
            lastMy = event.y();
            clampPan();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panning = false;
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, counter, (width - font.width(counter)) / 2, 14, 0xFF555555, true);

        String name = file.getFileName().toString();
        graphics.text(font, name, (width - font.width(name)) / 2, 32, 0xFFFFFFFF, true);
        if (Favourites.isFav(name)) {
            int sx = (width + font.width(name)) / 2 + 4;
            graphics.blit(RenderPipelines.GUI_TEXTURED, Favourites.STAR, sx, 28, 0, 0, 16, 16, 16, 16);
        }

        if (texId == null) return;

        clampPan();
        float scale = fit() * zoom;
        int w = Math.round(imgW * scale), h = Math.round(imgH * scale);
        int vx = vx(), vy = vy(), vw = vw(), vh = vh();
        int x = Math.round(vx + vw / 2f + (float) panX - w / 2f);
        int y = Math.round(vy + vh / 2f + (float) panY - h / 2f);

        graphics.enableScissor(vx, vy, vx + vw, vy + vh);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texId, x, y, 0, 0, w, h, imgW, imgH, imgW, imgH);
        graphics.disableScissor();

        graphics.outline(vx - 1, vy - 1, vw + 2, vh + 2, 0xFFFFFFFF);

        if (zoom > 1.01f) {
            graphics.text(font, String.format("x%.1f", zoom), vx + 4, vy + 4, 0xFFFFFFFF, true);
        }

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
        rebuildWidgets();
    }

    @Override
    public void removed() {
        if (texId != null) {
            minecraft.getTextureManager().release(texId);
            texId = null;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}

