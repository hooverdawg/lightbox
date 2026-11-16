package net.hooverdawg.lightbox;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

public class Palette {
    public static final int RECENT_MAX = 8, SAVED_MAX = 7;

    private static final Gson GSON = new Gson();
    private record Data(List<Integer> recent, List<Integer> saved) {}
    private static List<Integer> recent, saved;

    private Palette() {}

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("lightbox_colours.json");
    }

    private static void load() {
        if (recent != null) return;
        recent = new ArrayList<>();
        saved = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file())) {
            Data d = GSON.fromJson(r, Data.class);
            if (d != null) {
                if (d.recent() != null) recent.addAll(d.recent());
                if (d.saved() != null) saved.addAll(d.saved());
            }
        } catch (NoSuchFileException e) {
            // nothing saved yet
        } catch (IOException | JsonParseException e) {
            Lightbox.LOGGER.warn("Couldn't read {}, starting fresh", file(), e);
        }
    }

    private static void store() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(new Data(recent, saved)));
        } catch (IOException e) {
            Lightbox.LOGGER.warn("Couldn't save {}", file(), e);
        }
    }

    public static synchronized List<Integer> recent() { load(); return List.copyOf(recent); }
    public static synchronized List<Integer> saved() { load(); return List.copyOf(saved); }

    public static synchronized void used(int rgb) {
        load();
        if (!recent.isEmpty() && recent.getFirst() == rgb) return;
        recent.remove(Integer.valueOf(rgb));
        recent.addFirst(rgb);
        while (recent.size() > RECENT_MAX) recent.removeLast();
        store();
    }

    public static synchronized void addSaved(int rgb) {
        load();
        if (saved.contains(rgb)) return;
        saved.add(rgb);
        while (saved.size() > SAVED_MAX) saved.removeFirst();
        store();
    }

    public static synchronized void removeSaved(int index) {
        load();
        if (index >= 0 && index < saved.size()) {
            saved.remove(index);
            store();
        }
    }
}
