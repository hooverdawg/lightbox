package net.hooverdawg.lightbox;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;


public final class MetaStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, ScreenshotMeta>>() {}.getType();
    private static Map<String, ScreenshotMeta> data;

    private MetaStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("lightbox.json");
    }

    private static Map<String, ScreenshotMeta> load() {
        if (data == null) {
            try (Reader r = Files.newBufferedReader(file())) {
                data = GSON.fromJson(r, TYPE);
            } catch (IOException e) {
                // no file yet
            }
            if (data == null) data = new HashMap<>();
        }
        return data;
    }

    public static synchronized ScreenshotMeta get(String filename) {
        return load().get(filename);
    }

    public static synchronized void put(String filename, ScreenshotMeta meta) {
        load().put(filename, meta);
        save();
    }

    public static synchronized void remove(String filename) {
        if (load().remove(filename) != null) save();
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
        ScreenshotMeta m = load().remove(oldName);
        if (m != null) {
            load().put(newName, m);
            save();
        }
    }

    // snapshot of where player is. null if not in world.
    public static ScreenshotMeta capture(Minecraft mc) {
        if (mc.player == null) return null;
        String world = "Unknown";
        var server = mc.getSingleplayerServer();
        if (server != null) world = server.getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) world = mc.getCurrentServer().name;

        return new ScreenshotMeta(world, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot(), System.currentTimeMillis());
    }
}
