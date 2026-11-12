package net.hooverdawg.lightbox;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;

public class GalleryScreen extends Screen {
    private static final int CELL_W = 96, CELL_H = 54, GAP = 8, TOP = 30, BTM = 36, MENU_W = 140, ROW_H = 14, LABEL_H = 11;
    private static final int PITCH = CELL_H + LABEL_H + GAP;
    private static final int THUMB_W = 384;
    private int cellX(int i) { return left() + (i % cols()) * (CELL_W + GAP); }
    private int cellY(int i) { return TOP + GAP + (i / cols()) * PITCH - scroll; }

    private final Screen parent;
    private List<Path> files = List.of();
    private int scroll = 0;

    private record Entry(Component label, boolean enabled, Runnable action) {}
    private List<Entry> menu = List.of();
    private int menuX, menuY;

    private boolean selectMode = false;
    private final Set<String> selected = new HashSet<>();
    private Button selectBtn, deleteBtn, doneBtn;

    public GalleryScreen(Screen parent) {
        super(Component.translatable("lightbox.screen.gallery"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadFiles();
        scroll = Mth.clamp(scroll, 0, maxScroll());

        doneBtn = Button.builder(CommonComponents.GUI_DONE, _ -> onClose())
                .bounds(width / 2 - 65, height - 28, 130, 20).build();
        addRenderableWidget(doneBtn);

        selectBtn = Button.builder(Component.translatable("lightbox.button.bulk"), _ -> toggleSelectMode())
                .bounds(width - 80, 6, 70, 20).build();
        addRenderableWidget(selectBtn);

        addRenderableWidget(Actions.iconButton("folder", "lightbox.button.folder",
                width - 80 - 24, 6, _ -> Actions.openFolder()));

        deleteBtn = Button.builder(Component.empty(), _ -> askDeleteSelected())
                .bounds(width / 2 + 6, height - 28, 130, 20).build();
        addRenderableWidget(deleteBtn);
    }

    private void loadFiles() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".png"))
                    .sorted(Comparator.reverseOrder()) // names are dates - newest first
                    .toList();
        } catch (NoSuchFileException e) {
            files = List.of();
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't list {}", dir, e);
            files = List.of();
        }
    }

    private int left() {
        int cols = cols();
        int grid_W = cols * CELL_W + (cols - 1) * GAP;
        return (width - grid_W) / 2;
    }

    private int cols() {
        return Math.max(1, (width - GAP) / (CELL_W + GAP));
    }

    private int maxScroll() {
        int rows = (files.size() + cols() - 1) / cols();
        return Math.max(0, rows * PITCH + GAP - (height - TOP - BTM));
    }

    private int indexAt(double mx,  double my) {
        if (my <= TOP || my >= height - BTM) return -1;
        for (int i = 0; i < files.size(); i++) {
            int x = cellX(i),  y = cellY(i);
            if (mx >= x && mx < x + CELL_W && my >= y && my < y + CELL_H) return i;
        }
        return -1;
    }

    private String fileLabel(Path p) {
        String n = p.getFileName().toString();
        if (n.endsWith(".png")) n = n.substring(0, n.length() - 4);
        if (font.width(n) <= CELL_W) return n;
        String dots = "...";
        return font.plainSubstrByWidth(n, CELL_W - font.width(dots)) + dots;
    }

    private int menuRowAt(double mx, double my) {
        int h = menu.size() * ROW_H + 4;
        if (mx < menuX || mx >= menuX + MENU_W || my < menuY + 2 || my >= menuY + h - 2) return -1;
        return (int) ((my - menuY - 2) / ROW_H);
    }

    private void openMenu(int index, int mx, int my) {
        Path f = files.get(index);
        String name =  f.getFileName().toString();
        boolean fav = Favourites.isFav(name);

        menu = List.of(
                new Entry(Component.translatable(fav ? "lightbox.button.unfavourite" : "lightbox.button.favourite"),
                        true, () -> Favourites.toggle(name)),
                new Entry(Component.translatable("lightbox.button.rename"), true,
                        () -> Actions.rename(minecraft, this, f, _ -> minecraft.gui.setScreen(this))),
                new Entry(Component.translatable("lightbox.button.edit"), true,
                        () -> minecraft.gui.setScreen(new EditorScreen(this, f, _ -> minecraft.gui.setScreen(this)))),
                new Entry(Component.translatable(fav ? "lightbox.button.delete.locked" : "lightbox.button.delete"),
                        !fav, () -> Actions.askDelete(minecraft, f, this, this))
        );

        int h = menu.size() * ROW_H + 4;
        menuX = Math.min(mx, width - MENU_W - 2);
        menuY = Math.min(my, height - h - 2);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        drainLoaded();

        selectBtn.setMessage(Component.translatable(selectMode ? "lightbox.button.bulk.stop" : "lightbox.button.bulk"));
        deleteBtn.visible = selectMode;
        deleteBtn.active = !selected.isEmpty();
        deleteBtn.setMessage(Component.translatable("lightbox.button.delete.selected", selected.size()));
        doneBtn.setX(selectMode ? width / 2 - 132 : width / 2 - 65);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF, true);

        graphics.enableScissor(0, TOP, width, height - BTM);
        for (int i = 0; i < files.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (y + CELL_H + LABEL_H < TOP || y > height - BTM) continue; // only load visible

            Tex t = getTex(files.get(i));
            if (t != null && t.id() != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0,
                        CELL_W, CELL_H, t.w(), t.h(), t.w(), t.h());
            } else {
                graphics.fill(x, y, x + CELL_W, y + CELL_H, t == null ? 0xFF2A2A2A : 0xFF3A2020);
            }
            graphics.outline(x, y, CELL_W, CELL_H, 0xFF000000);

            String label = fileLabel(files.get(i));
            graphics.text(font, label, x + (CELL_W - font.width(label)) / 2, y + CELL_H + 2, 0xFFCCCCCC, true);

            if (selectMode) {
                String fname = files.get(i).getFileName().toString();
                if (Favourites.isFav(fname)) {
                    graphics.fill(x, y, x + CELL_W, y + CELL_H, 0x90000000);
                } else {
                    boolean on = selected.contains(fname);
                    if (on) graphics.fill(x, y, x + CELL_W, y + CELL_H, 0x604080FF);
                    graphics.outline(x + 3, y + 3, 10, 10, 0xFFFFFFFF);
                    if (on) graphics.fill(x +5, y + 5, x + 11, y + 11, 0xFF55FF55);
                }
            }

            if (Favourites.isFav(files.get(i).getFileName().toString())) {
                int sx = x + CELL_W - 18, sy = y + 2;
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0x80000000);
                graphics.blit(RenderPipelines.GUI_TEXTURED, Favourites.STAR, sx, sy, 0, 0, 16, 16, 16, 16);
            }
        }
        graphics.disableScissor();

        if (!menu.isEmpty()) {
            int h = menu.size() * ROW_H + 4;
            graphics.fill(menuX, menuY, menuX + MENU_W, menuY + h, 0xF0101010);
            graphics.outline(menuX, menuY, MENU_W, h, 0xFF888888);

            int hover = menuRowAt(mouseX, mouseY);
            for (int r = 0; r < menu.size(); r++) {
                Entry e = menu.get(r);
                int ry = menuY + 2 + r * ROW_H;
                if (r == hover && e.enabled()) {
                    graphics.fill(menuX + 2, ry, menuX + MENU_W - 2, ry + ROW_H, 0xFF404040);
                }
                graphics.text(font, e.label(), menuX + 6, ry + 3, e.enabled() ? 0xFFFFFFFF : 0xFF777777, false);
            }
        }
        if (!menu.isEmpty()) {
            int r = menuRowAt(mouseX, mouseY);
            if (r >= 0 && menu.get(r).enabled()) graphics.requestCursor(CursorTypes.POINTING_HAND);
        } else {
            int hov = indexAt(mouseX, mouseY);
            if (hov >= 0 && !(selectMode && Favourites.isFav(files.get(hov).getFileName().toString()))) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        menu = List.of();
        scroll = Mth.clamp(scroll - (int) (scrollY * 20), 0, maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!menu.isEmpty()) {
            int row = menuRowAt(event.x(), event.y());
            List<Entry> m = menu;
            menu = List.of();
            if (row >= 0 && event.button() == 0 && m.get(row).enabled()) m.get(row).action().run();
            return true;
        }

        if (super.mouseClicked(event, doubleClick)) return true;

        int i = indexAt(event.x(), event.y());
        if (i < 0) return false;
        if (selectMode) {
            if (event.button() == 0) {
                String fname = files.get(i).getFileName().toString();
                if (!Favourites.isFav(fname) && !selected.remove(fname)) selected.add(fname);
            }
            return true;
        }
        if (event.button() == 0) {
            minecraft.gui.setScreen(new ViewerScreen(this, files.get(i)));
            return true;
        }
        if (event.button() == 1) {
            openMenu(i, (int) event.x(), (int) event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (LightboxClient.galleryKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void toggleSelectMode() {
        selectMode = !selectMode;
        selected.clear();
        menu = List.of();
        rebuildWidgets();
    }

    private void askDeleteSelected() {
        List<Path> picked = files.stream()
                .filter(f -> selected.contains(f.getFileName().toString()))
                .toList();
        Actions.askDeleteMany(minecraft, picked, this, () -> {
            selected.clear();
            selectMode = false;
        });
    }

    private record Tex(Identifier id, int w, int h) {}
    private record Loaded(Path path, byte[] png) {}

    private final Map<Path, Tex> textures = new HashMap<>();
    private final Queue<Loaded> loaded = new ConcurrentLinkedQueue<>();
    private final Set<Path> loading = new HashSet<>();
    private int counter = 0;

    private Tex getTex(Path file) {
        Tex t = textures.get(file);
        if (t == null && loading.add(file)) {
            Util.ioPool().execute(() -> loaded.add(new Loaded(file, makeThumb(file))));
        }
        return t;
    }

    private static byte[] makeThumb(Path file) {
        try {
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) {
                Lightbox.LOGGER.warn("{} isn't a readable image", file);
                return null;
            }
            BufferedImage out = src;
            if (src.getWidth() > THUMB_W) {
                int th = Math.max(1, Math.round(src.getHeight() * (THUMB_W / (float) src.getWidth())));
                out = scaleTo(src, THUMB_W, th);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Lightbox.LOGGER.warn("Couldn't make a thumbnail for {}", file, t);
            return null;
        }
    }

    private static BufferedImage scaleTo(BufferedImage src, int tw, int th) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resize(cur, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private void drainLoaded() {
        for (int n = 0; n < 4; n++) {
            Loaded l = loaded.poll();
            if (l == null) return;
            loading.remove(l.path());
            if (textures.containsKey(l.path())) continue;

            Tex t = new Tex(null, 0, 0);
            if (l.png() != null) {
                try {
                    NativeImage img = NativeImage.read(new ByteArrayInputStream(l.png()));
                    Identifier id = Lightbox.id("shot/" + counter++);
                    minecraft.getTextureManager().register(id, new DynamicTexture(() -> "lightbox screenshot", img));
                    t = new Tex(id, img.getWidth(), img.getHeight());
                } catch (Exception e) {
                    Lightbox.LOGGER.warn("Couldn't upload thumbnail for {}", l.path(), e);
                }
            }
            textures.put(l.path(), t);
        }
    }

    @Override
    public void removed() {
        for (Tex t : textures.values()) {
            if (t.id() != null) minecraft.getTextureManager().release(t.id());
        }
        textures.clear();
        loading.clear();
        loaded.clear();
    }

    @Override
    public void onClose() {
        if (!menu.isEmpty()) { menu = List.of(); return; }
        if (selectMode) { toggleSelectMode(); return; }
        minecraft.gui.setScreen(parent);
    }

}