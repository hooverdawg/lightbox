package net.hooverdawg.lightbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class Actions {
    private Actions() {}

    // confirm, then delete. cancelTo - where cancel goes, afterDel - where land afterwards
    public static void askDelete(Minecraft mc, Path file, Screen cancelTo, Screen afterDelete) {
        String name = file.getFileName().toString();
        mc.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    Files.deleteIfExists(file);
                    MetaStore.remove(name);
                } catch (IOException e) {
                    // couldn't delete, just return
                }
                mc.gui.setScreen(afterDelete); // reload list
            } else {
                mc.gui.setScreen(cancelTo);
            }
        },
                Component.translatable("lightbox.confirm.delete.title"),
                Component.translatable("lightbox.confirm.delete.message", name)));
    }

    // opens rename screen. back - where cancel goes, onDone - new path
    public static void rename(Minecraft mc, Screen back, Path file, Consumer<Path> onDone) {
        mc.gui.setScreen(new RenameScreen(back, file, onDone));
    }
}
