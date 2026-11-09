package net.hooverdawg.lightbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final int CELLW = 96, CELLH = 54, GAP = 8, TOP = 30, BTM = 36;
    private int cellX(int i) { return left() + (i % cols()) * (CELLW + GAP); }
    private int cellY(int i) { return TOP + GAP + (i / cols()) * (CELLH + GAP) - scroll; }

    private final Screen parent;
    private List<Path> files = List.of();
    private int scroll = 0;

    public GalleryScreen(Screen parent) {
        super(Component.translatable("lightbox.screen.gallery"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadFiles();
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, _ -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());
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
        int grid_W = cols * CELLW + (cols - 1) * GAP;
        return (width - grid_W) / 2;
    }

    private int cols() {
        return Math.max(1, (width - GAP) / (CELLW + GAP));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, 10, 10, 0xFFFFFF, true);

        int left = left();
        int cols = cols();
        graphics.enableScissor(0, TOP, width, height - BTM);
        for (int i = 0; i < files.size(); i++) {
            int x = cellX(i);
            int y = cellY(i);
            if (y + CELLH < TOP || y > height - BTM) continue; // only load visible

            Tex t = getTex(files.get(i));
            if (t.id() != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0,
                        CELLW, CELLH, t.w(), t.h(), t.w(), t.h());
            }
            graphics.outline(x, y, CELLW, CELLH, 0xFF404040);
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int rows = (files.size() + cols() - 1) / cols();
        int contentH = rows * (CELLH + GAP) + GAP;
        int maxScroll = Math.max(0, contentH - (height - TOP - BTM));
        scroll = Mth.clamp(scroll - (int) (scrollY * 20), 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // let done button go first
        if (event.button() == 0 && event.y() > TOP && event.y() < height - BTM) {
            for (int i = 0; i < files.size(); i++) {
                int x = cellX(i), y = cellY(i);
                if (event.x() >= x && event.x() < x + CELLW && event.y() >= y && event.y() < y + CELLH ) {
                    minecraft.gui.setScreen(new ViewerScreen(this, files.get(i)));
                    return true;
                }
            }
        }
        return false;
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
        minecraft.gui.setScreen(parent);
    }

}