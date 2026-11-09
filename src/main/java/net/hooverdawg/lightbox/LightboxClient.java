package net.hooverdawg.lightbox;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class LightboxClient implements ClientModInitializer {
    private static final int BTN = 20;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PauseScreen || screen instanceof TitleScreen) {
                addGalleryButton(client, screen);
            }
        });
    }

    private static void addGalleryButton(Minecraft client, Screen screen) {
        List<AbstractWidget> widgets = Screens.getWidgets(screen);

        //rightmost vanilla icon button decides row
        AbstractWidget ref = null;
        for (AbstractWidget wd : widgets) {
            if (wd instanceof SpriteIconButton && (ref == null || wd.getRight() > ref.getRight())) {
                ref = wd;
            }
        }

        if (ref == null) { //no icon found; fallback to corner
            widgets.add(makeButton(client, screen, 10, 10));
            return;
        }

        //collect icon buttons in row
        List<AbstractWidget> row = new ArrayList<>();
        for (AbstractWidget wd : widgets) {
            if (wd instanceof SpriteIconButton && wd.getY() == ref.getY()) row.add(wd);
        }

        //join on right
        Button gallery = makeButton(client, screen, ref.getRight() + 4, ref.getY());
        row.add(gallery);

        // row shifts to stay centered
        int shift = (BTN + 4) / 2;
        for (AbstractWidget wd : row) wd.setX(wd.getX() - shift);

        widgets.add(gallery);
    }

    private static Button makeButton(Minecraft client, Screen screen, int x, int y) {
        Component label = Component.translatable("lightbox.button.gallery");
        Button b = SpriteIconButton.builder(label,
                _ -> client.gui.setScreen(new GalleryScreen(screen)), true)
                .width(BTN)
                .sprite(Identifier.fromNamespaceAndPath("lightbox", "icon/gallery"), 16, 16)
                .build();
        b.setPosition(x, y);
        b.setTooltip(Tooltip.create(label));
        return b;
    }
}
