package net.hooverdawg.lightbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public class GalleryScreen extends Screen {
    private static final int CELL_W = 96, CELL_H = 54, GAP = 8, TOP = 30, BTM = 36, MENU_W = 140, ROW_H = 14;
    private int cellX(int i) { return left() + (i % cols()) * (CELL_W + GAP); }
    private int cellY(int i) { return TOP + GAP + (i / cols()) * (CELL_H + GAP) - scroll; }

    private final Screen parent;
    private List<Path> files = List.of();
    private int scroll = 0;

    private record Entry(Component label, boolean enabled, Runnable action) {}
    private List<Entry> menu = List.of(); // empty = closed
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
        } catch (IOException e) {
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
        return Math.max(0, rows * (CELL_H + GAP) + GAP - (height - TOP - BTM));
    }

    private int indexAt(double mx,  double my) {
        if (my <= TOP || my >= height - BTM) return -1;
        for (int i = 0; i < files.size(); i++) {
            int x = cellX(i),  y = cellY(i);
            if (mx >= x && mx < x + CELL_W && my >= y && my < y + CELL_H) return i;
        }
        return -1;
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
        menuX = Math.min(mx, width - MENU_W - 2); // keep on screen
        menuY = Math.min(my, height - h - 2);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        selectBtn.setMessage(Component.translatable(selectMode ? "lightbox.button.bulk.stop" : "lightbox.button.bulk"));
        deleteBtn.visible = selectMode;
        deleteBtn.active = !selected.isEmpty();
        deleteBtn.setMessage(Component.translatable("lightbox.button.delete.selected", selected.size()));
        doneBtn.setX(selectMode ? width / 2 - 132 : width / 2 - 65);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF, true);

        int left = left();
        int cols = cols();
        graphics.enableScissor(0, TOP, width, height - BTM);
        for (int i = 0; i < files.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (y + CELL_H < TOP || y > height - BTM) continue; // only load visible

            Tex t = getTex(files.get(i));
            if (t.id() != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0,
                        CELL_W, CELL_H, t.w(), t.h(), t.w(), t.h());
            }
            graphics.outline(x, y, CELL_W, CELL_H, 0xFF000000);

            if (selectMode) {
                String fname = files.get(i).getFileName().toString();
                if (Favourites.isFav(fname)) {
                    graphics.fill(x, y, x + CELL_W, y + CELL_H, 0x90000000); //dimmed
                } else {
                    boolean on = selected.contains(fname);
                    if (on) graphics.fill(x, y, x + CELL_W, y + CELL_H, 0x604080FF);
                    graphics.outline(x + 3, y + 3, 10, 10, 0xFFFFFFFF);
                    if (on) graphics.fill(x +5, y + 5, x + 11, y + 11, 0xFF55FF55);
                }
            }

            if (Favourites.isFav(files.get(i).getFileName().toString())) {
                int sx = x + CELL_W - 18, sy = y + 2;
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0x80000000); // dark backing - visibility
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
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        menu = List.of();
        scroll = Mth.clamp(scroll - (int) (scrollY * 20), 0, maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!menu.isEmpty()) { // open menu absorbs click
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
            return true; // no viewer - no right click menu (when select)
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

    private void toggleSelectMode() {
        selectMode = !selectMode;
        selected.clear();
        menu = List.of();
        rebuildWidgets(); // re-runs init() so tooltip updates. image isn't reloaded
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
    private final Map<Path, Tex> textures = new HashMap<>();
    private int counter = 0;

    private Tex getTex(Path file) {
        return textures.computeIfAbsent(file, p -> {
            try (InputStream in = Files.newInputStream(p)) {
                NativeImage img = NativeImage.read(in);
                Identifier id = Identifier.fromNamespaceAndPath("lightbox", "shot/" + counter++);
                minecraft.getTextureManager().register(id,
                        new DynamicTexture(() -> "lightbox screenshot", img));
                return new Tex(id, img.getWidth(), img.getHeight());
            } catch (IOException e) {
                return new Tex(null, 0, 0); // rem. fails so we don't retry every frame
            }
        });
    }

    @Override
    public void removed() {
        for (Tex t : textures.values()) {
            if (t.id() != null) minecraft.getTextureManager().release(t.id());
        }
        textures.clear();
    }

    @Override
    public void onClose() {
        if (!menu.isEmpty()) { menu = List.of(); return; }
        if (selectMode) { toggleSelectMode(); return; }
        minecraft.gui.setScreen(parent);
    }

}