package net.hooverdawg.lightbox;

import net.minecraft.network.chat.Component;

public record ScreenshotMeta(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long time,
        String dim,
        String biome) {

    public Component dimName() {
        return name("lightbox.dimension", dim);
    }

    public Component biomeName() {
        return name("biome", biome);
    }

    private static Component name(String prefix, String id) {
        if (id == null) return null;
        int c = id.indexOf(":");
        String ns = c < 0 ? "minecraft" : id.substring(0, c);
        String path = c < 0 ? id : id.substring(c + 1);
        return Component.translatableWithFallback(prefix + "." + ns + "." + path, pretty(path));
    }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("[_/]")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}