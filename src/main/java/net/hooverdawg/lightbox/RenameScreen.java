package net.hooverdawg.lightbox;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;

public class RenameScreen extends Screen {
    private static final String BAD_CHARS = "[\\\\/:*?\"<>|]";

    private final Screen back;
    private final Consumer<Path> onDone;
    private final Path file;
    private EditBox box;
    private Button ok;
    private Component error = Component.empty();

    public RenameScreen(Screen back, Path file, Consumer<Path> onDone) {
        super(Component.translatable("lightbox.rename.title"));
        this.back = back;
        this.file = file;
        this.onDone = onDone;
    }

    private String baseName() {
        String n = file.getFileName().toString();
        return n.endsWith(".png") ? n.substring(0, n.length() - 4) : n;
    }

    @Override
    protected void init() {
        String keep = box != null ? box.getValue() : baseName();

        box = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, title);
        box.setMaxLength(100);
        box.setValue(keep);
        addRenderableWidget(box);
        setInitialFocus(box);

        ok = Button.builder(Component.translatable("lightbox.button.rename"), _ -> doRename())
                .bounds(width / 2 - 100, height / 2 + 20, 98, 20)
                .build();
        addRenderableWidget(ok);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, _ -> onClose())
                .bounds(width / 2 + 2, height / 2 + 20, 98, 20).build());

        box.setResponder(s -> {
            String clean = s.replaceAll(BAD_CHARS, "");
            if (!clean.equals(s)) { // illegal char typed/pasted: strip it
                box.setValue(clean);
                validate();
                return;
            }
            validate();
        });
        validate();
    }

    private void validate() {
        error = Component.empty();
        String n = box.getValue().trim();
        String neu = n + ".png";
        String old = file.getFileName().toString();
        boolean valid = !n.isEmpty() && !n.endsWith(".") && !neu.equals(old);

        if (valid) {
            try {
                Path target = file.resolveSibling(neu);
                if (Files.exists(target) && !neu.equalsIgnoreCase(old)) {
                    valid = false;
                    error = Component.translatable("lightbox.rename.exists");
                }
            } catch (InvalidPathException e) {
                valid = false;
            }
        }
        ok.active = valid;
    }

    private void doRename() {
        String old = file.getFileName().toString();
        String neu = box.getValue().trim() + ".png";
        Path target = file.resolveSibling(neu);
        try {
            Files.move(file, target);
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't rename {} to {}", old, neu, e);
            error = Component.translatable("lightbox.rename.failed");
            return;
        }
        MetaStore.rename(old, neu);
        Favourites.rename(old, neu);
        onDone.accept(target); // reopens with new name
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 30, 0xFFFFFFFF, true);
        graphics.text(font, ".png", width / 2 + 104, height / -4, 0xFFAAAAAA, true);
        if (!error.getString().isEmpty()) {
            graphics.text(font, error, (width - font.width(error)) / 2, height / 2 + 46, 0xFFFF5555, true);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(back);
    }
}