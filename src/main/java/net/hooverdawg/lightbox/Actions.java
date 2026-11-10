package net.hooverdawg.lightbox;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import javax.tools.Tool;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class Actions {
    private Actions() {}

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
            MetaStore.remove(f.getFileName().toString());
        } catch (IOException e) {
            // skip, keep going
        }
    }

    // confirm, delete many. favourites are skipped
    public static void askDeleteMany(Minecraft mc, List<Path> files, Screen returnTo, Runnable afterDelete) {
        List<Path> targets = files.stream()
                .filter(f -> !Favourites.isFav(f.getFileName().toString()))
                .toList();
        if (targets.isEmpty()) return;

        mc.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                targets.forEach(Actions::deleteQuietly);
                afterDelete.run();
            }
            mc.gui.setScreen(returnTo);
        }, Component.translatable("lightbox.confirm.delete.many.title", targets.size()),
                Component.translatable("lightbox.confirm.delete.many.message")));
    }

    // confirm, then delete. favourites are skipped
    public static void askDelete(Minecraft mc, Path file, Screen cancelTo, Screen afterDelete) {
        String name = file.getFileName().toString();
        if (Favourites.isFav(name)) return;

        mc.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) deleteQuietly(file);
            mc.gui.setScreen(confirmed ? afterDelete : cancelTo);
        },
            Component.translatable("lightbox.confirm.delete.title"),
            Component.translatable("lightbox.confirm.delete.message", name)));
    }

    // opens rename screen
    public static void rename(Minecraft mc, Screen back, Path file, Consumer<Path> onDone) {
        mc.gui.setScreen(new RenameScreen(back, file, onDone));
    }

    public static Button iconButton(String icon, String langKey, int x, int y, Button.OnPress press) {
        Component tip = Component.translatable(langKey);
        Button b = SpriteIconButton.builder(tip, press, true)
                .width(20)
                .sprite(Identifier.fromNamespaceAndPath("lightbox", "icon/" + icon), 16, 16)
                .build();
        b.setPosition(x, y);
        b.setTooltip(Tooltip.create(tip));
        return b;
    }

    public static void openFolder() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        try {
            Files.createDirectories(dir); // in case of no screenshots
        } catch (IOException e) {
            // fall through
        }
        Util.getPlatform().openPath(dir);
    }
}
