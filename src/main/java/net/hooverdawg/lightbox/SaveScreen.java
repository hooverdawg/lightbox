package net.hooverdawg.lightbox;

import javax.imageio.ImageIO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class SaveScreen extends Screen {
    private final Screen editor;
    private final Path original;
    private final BufferedImage image;
    private final Consumer<Path> onSaved;
    private final Path copyTarget;
    private Component error = Component.empty();


    public SaveScreen(Screen editor, Path original, BufferedImage image, Consumer<Path> onSaved) {
        super(Component.translatable("lightbox.save.title"));
        this.editor = editor;
        this.original = original;
        this.image = image;
        this.onSaved = onSaved;
        this.copyTarget = freeCopyName(original);
    }

    // adds suffix to path name if taken
    private static Path freeCopyName(Path original) {
        String n = original.getFileName().toString();
        String stem = n.endsWith(".png") ? n.substring(0, n.length() - 4) : n;
        stem = stem.replaceFirst("_edited(_\\d+)?$", "");
        Path target = original.resolveSibling(stem + "_edited.png");
        for (int i = 2; Files.exists(target); i++) {
            target = original.resolveSibling(stem + "_edited_" + i + ".png");
        }
        return target;
    }

    @Override
    protected void init() {
        int y = height / 2 - 20;
        addRenderableWidget(Button.builder(Component.translatable("lightbox.save.copy"), _ -> doCopy())
                .bounds(width / 2 - 100, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lightbox.save.overwrite"), _ -> doOverwrite())
                .bounds(width / 2 - 100, y + 24, 200, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, _ -> onClose())
                .bounds(width / 2 - 100, y + 56, 200, 20).build());
    }

    private void write(Path target) throws IOException {
        // screenshots have no transparency - save as RGB (smaller file)
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        //write to temp file (failure won't damage original)
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!ImageIO.write(rgb, "png", tmp.toFile())) throw new IOException("no PNG writer available");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void doCopy() {
        try {
            write(copyTarget);
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't save {}", copyTarget, e);
            error = Component.translatable("lightbox.save.failed");
            return;
        }
        ScreenshotMeta meta = MetaStore.get(original.getFileName().toString());
        if (meta != null) MetaStore.put(copyTarget.getFileName().toString(), meta);
        onSaved.accept(copyTarget);
    }

    private void doOverwrite() {
        try {
            write(original);
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't save {}", copyTarget, e);
            error = Component.translatable("lightbox.save.failed");
            return;
        }
        onSaved.accept(original);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 46, 0xFFFFFFFF, true);

        String note = "→ " + copyTarget.getFileName();
        graphics.text(font, note, (width - font.width(note)) / 2, height / 2 - 32, 0xFFAAAAAA, false);

        if (!error.getString().isEmpty()) {
            graphics.text(font, error, (width - font.width(error)) / 2, height / 2 + 84, 0xFFFF5555, true);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(editor);
    }
}
