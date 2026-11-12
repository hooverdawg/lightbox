package net.hooverdawg.lightbox;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Actions {
    private Actions() {}

    private static final Pattern NAME_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2})");
    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private static long takenTime(Path p) {
        String name = p.getFileName().toString();

        ScreenshotMeta m = MetaStore.get(name);
        if (m != null) return m.time();

        Matcher mt = NAME_TIME.matcher(name);
        if (mt.find()) {
            try {
                return LocalDateTime.parse(mt.group(1), NAME_FMT)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // no date
            }
        }

        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
            MetaStore.remove(f.getFileName().toString());
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't delete {}", f, e);
        }
    }

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

    public static void rename(Minecraft mc, Screen back, Path file, Consumer<Path> onDone) {
        mc.gui.setScreen(new RenameScreen(back, file, onDone));
    }

    public static Button iconButton(String icon, String langKey, int x, int y, Button.OnPress press) {
        Component tip = Component.translatable(langKey);
        Button b = SpriteIconButton.builder(tip, press, true)
                .width(20)
                .sprite(Lightbox.id( "icon/" + icon), 16, 16)
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
            Lightbox.LOGGER.warn("Couldn't create {}", dir, e);
        }
        Util.getPlatform().openPath(dir);
    }

    public static List<Path> listScreenshots() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>(s.filter(p -> p.toString().endsWith(".png")).toList());

            Map<Path, Long> taken = new HashMap<>();
            for (Path p : out) taken.put(p, takenTime(p));

            out.sort(Comparator.<Path>comparingLong(taken::get).reversed()
                    .thenComparing(Comparator.<Path>reverseOrder()));
            return out;
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't list {}", dir, e);
            return List.of();
        }
    }
}
