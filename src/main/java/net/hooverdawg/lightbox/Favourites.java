package net.hooverdawg.lightbox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class Favourites {
    public static final Identifier STAR =
            Identifier.fromNamespaceAndPath("lightbox", "textures/gui/sprites/icon/star.png");

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Set<String>>() {}.getType();
    private static Set<String> data;

    private Favourites() {}

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("lightbox_favourites.json");
    }

    private static Set<String> load() {
        if (data == null) {
            try (Reader r = Files.newBufferedReader(file())) {
                data = GSON.fromJson(r, TYPE);
            } catch (IOException e) {
                // no file yet
            }
            if (data == null) data = new HashSet<>();
        }
        return data;
    }

    public static synchronized boolean isFav(String filename) {
        return load().contains(filename);
    }

    public static synchronized void toggle(String filename) {
        Set<String> s = load();
        if (!s.remove(filename)) s.add(filename);
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(data, TYPE));
        } catch (IOException e) {
            // best effort
        }
    }

    public static synchronized void rename(String oldName, String newName) {
        Set<String> s = load();
        if (s.remove(oldName)) {
            s.add(newName);
            save();
        }
    }
}
