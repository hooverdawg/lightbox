package net.hooverdawg.lightbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
}
