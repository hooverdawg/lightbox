package net.hooverdawg.lightbox;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class EditorScreen extends Screen {
    private static final int SIDE = 40, TOP = 36, BTM = 70; // room for toolbar/topbar/sliders
    private static final int WHEEL_D = 100, WHEEL_TEX = 200, PANEL_W = 116, PANEL_H = 140;

    //every edit is one operation, or 'Op'
    private interface Op {}
    private record Stroke(int rgb, float size, List<double[]> pts) implements Op {}

    private final Screen parent;
    private final Path file;

    private BufferedImage base; // original image
    private BufferedImage current; // shown image (same as og for now)
    private boolean loadFailed = false;

    private final List<Op> ops = new ArrayList<>();
    private final List<Op> redoStack = new ArrayList<>();

    private Identifier texId, wheelId;
    private int imgW, imgH;

    private float scale;
    private int ox, oy, dw, dh;

    //tool state
    private float hue = 0f, sat = 0.76f, val = 1f;
    private double brush = 5;
    private List<double[]> drawing; // stroke being drawn or null
    private boolean paletteOpen = false, pickingWheel = false, pickingBright = false;

    private Button undoBtn, redoBtn;

    public EditorScreen(Screen parent, Path file) {
        super(Component.translatable("lightbox.screen.editor"));
        this.parent = parent;
        this.file = file;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("lightbox.button.back"), _ -> onClose())
                .bounds(width / 2 - 50, 8, 100, 20).build());

        // toolbar down left side
        addIcon("pen", 10, TOP, true, () -> {});
        addIcon("text", 10, TOP + 24, false, () -> {});
        addIcon("rectangle", 10, TOP + 48, false, () -> {});
        addIcon("arrow", 10, TOP + 72, false, () -> {});
        addIcon("crop", 10, TOP + 96, false, () -> {});

        int ay = TOP + 5 * 24 + 12;
        undoBtn = addIcon("undo", 10, ay, false, this::undo);
        redoBtn = addIcon("redo", 10, ay + 24, false, this::redo);
        addIcon("save", 10, ay + 48, false, () -> {});

        // bottom bar - colour swatch + size slider
        addRenderableWidget(new ValueSlider(x0() + 104, height - 30, 204, 20,
                "lightbox.editor.size", 1, 60, brush, v -> brush = v));

        if (wheelId == null) buildWheel();
        if (base == null && !loadFailed) loadBase();
        if (texId == null && current != null) refreshTexture(current);
    }

    private int x0() { return (width - 308) / 2; }

    private Button addIcon(String icon, int x, int y, boolean active, Runnable action) {
        Component tip = Component.translatable("lightbox.button." + icon);
        Button b = SpriteIconButton.builder(tip, _ -> action.run(), true)
                .width(20)
                .sprite(Identifier.fromNamespaceAndPath("lightbox", "icon/" + icon), 16, 16)
                .build();
        b.setPosition(x, y);
        b.setTooltip(Tooltip.create(tip));
        b.active = active;
        addRenderableWidget(b);
        return b;
    }

    private int rgb() {
        return Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
    }

    private void buildWheel() {
        int n = WHEEL_TEX;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double c = n / 2.0, rad = n / 2.0 - 1;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.hypot(dx, dy);
                if (r > rad + 1) continue; // outside circle, stays transparent
                float h = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1.0) % 1.0);
                float s = (float) Math.min(1.0, r / rad);
                int a = r <= rad ? 255 : (int) Math.max(0, 255 * (rad + 1 - r)); //soft edge
                img.setRGB(x, y, (a << 24) | (Color.HSBtoRGB(h, s, 1f) & 0xFFFFFF));
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            NativeImage ni = NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
            wheelId = Identifier.fromNamespaceAndPath("lightbox", "wheel");
            minecraft.getTextureManager().register(wheelId,
                    new DynamicTexture(() -> "lightbox wheel", ni));
        } catch (IOException e) {
            wheelId = null;
        }
    }

    private int panelX() { return x0(); }
    private int panelY() { return height - 30 - PANEL_H - 4; }
    private int wheelCx() { return panelX() + PANEL_W / 2; }
    private int wheelCy() { return panelY() + 8 + WHEEL_D / 2; }
    private int barX() { return panelX() + 8; }
    private int barY() { return panelY() + 8 + WHEEL_D + 4; }

    private boolean inSwatch(double mx, double my) {
        return mx >= x0() && mx < x0() + 100 && my >= height - 30 && my < height - 10;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= panelX() && mx < panelX() + PANEL_W && my >= panelY() && my < panelY() + PANEL_H;
    }

    private boolean inWheel(double mx, double my) {
        return Math.hypot(mx - wheelCx(), my - wheelCy()) <= WHEEL_D / 2.0;
    }

    private boolean inBar(double mx, double my) {
        return mx >= barX() && mx < barX() + 100 && my >= barY() && my < barY() + 20;
    }

    private void pickWheel(double mx, double my) {
        double dx = mx - wheelCx(), dy = my - wheelCy();
        double r = Math.hypot(dx,dy);
        if (r > 0.5) hue = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1.0) % 1.0);
        sat = (float) Math.min(1.0, r / (WHEEL_D / 2.0));
    }

    private void pickBright(double mx) {
        val = (float) Math.clamp((mx - barX()) / 99.0, 0.0, 1.0);
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    private void loadBase() {
        try {
            BufferedImage src = ImageIO.read(file.toFile());
            if (src == null) {
                loadFailed = true;
                return;
            }
            base = copy(src);
            current = copy(base);
        } catch (IOException e) {
            loadFailed = true;
        }
    }

    private BufferedImage applyOp(BufferedImage img, Op op) {
        if (op instanceof Stroke s) {
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(s.rgb()));
            if (s.pts().size() == 1) {
                double[] p = s.pts().getFirst();
                double r = s.size() / 2.0;
                g.fill(new Ellipse2D.Double(p[0] - r, p[1] - r, s.size(), s.size()));
            } else {
                g.setStroke(new BasicStroke(s.size(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Double path = new Path2D.Double();
                double[] f = s.pts().getFirst();
                path.moveTo(f[0], f[1]);
                for (int i = 1; i < s.pts().size(); i++) {
                    double[] p = s.pts().get(i);
                    path.lineTo(p[0], p[1]);
                }
                g.draw(path);
            }
            g.dispose();
        }
        return img;
    }

    private void commit(Op op) {
        ops.add(op);
        redoStack.clear();
        current = applyOp(current, op);
        refreshTexture(current);
    }

    private void undo() {
        if (ops.isEmpty()) return;
        redoStack.add(ops.removeLast());
        current = copy(base);
        for (Op op : ops) current = applyOp(current, op);
        refreshTexture(current);
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        Op op = redoStack.removeLast();
        ops.add(op);
        current = applyOp(current, op);
        refreshTexture(current);
    }

    private void refreshTexture(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            NativeImage ni = NativeImage.read(new ByteArrayInputStream(out.toByteArray()));

            if (texId != null) minecraft.getTextureManager().release(texId);
            texId = Identifier.fromNamespaceAndPath("lightbox", "editor");
            minecraft.getTextureManager().register(texId,
                    new DynamicTexture(() -> "lightbox editor", ni));

            imgW = ni.getWidth();
            imgH = ni.getHeight();
        } catch (IOException e) {
            texId = null;
            loadFailed = true;
        }
    }

    // screen pos -> image pixel
    double toImgX(double sx) {return (sx - ox) / scale; }
    double toImgY(double sy) {return (sy - oy) / scale; }

    private boolean insideImage(double mx, double my) {
        return mx >= ox && mx < ox + dw && my >= oy && my < oy + dh;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();

        if (event.button() == 0) {
            if (paletteOpen && inPanel(mx, my)) { //absorb click
                if (inWheel(mx, my)) {
                    pickingWheel = true;
                    pickWheel(mx, my);
                } else if (inBar(mx,my)) {
                    pickingBright = true;
                    pickBright(mx);
                }
                return true;
            }
            if (inSwatch(mx, my)) {
                paletteOpen = !paletteOpen;
                return true;
            }
            paletteOpen = false; // click elsewhere closes
        }
        if (super.mouseClicked(event, doubleClick)) return true; // buttons & size slider
        if (event.button() == 0 && texId != null && insideImage(mx, my)) {
            drawing = new ArrayList<>();
            drawing.add(new double[] {toImgX(mx), toImgY(my)});
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pickingWheel) {
            pickWheel(event.x(), event.y());
            return true;
        }
        if (pickingBright) {
            pickBright(event.x());
            return true;
        }
        if (drawing != null) {
            double[] last = drawing.getLast();
            double nx = toImgX(event.x()), ny = toImgY(event.y());
            if (Math.hypot(nx - last[0], ny - last[1]) * scale >= 1.0) {
                drawing.add(new double[] {nx, ny});
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pickingWheel || pickingBright) {
            pickingWheel = false;
            pickingBright = false;
            return true;
        }
        if (drawing != null && event.button() == 0) {
            commit(new Stroke(rgb(), (float) brush, drawing));
            drawing = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        undoBtn.active = !ops.isEmpty();
        redoBtn.active = !redoStack.isEmpty();

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (texId == null) {
            if (loadFailed) {
                Component msg = Component.translatable("lightbox.editor.loadfail");
                graphics.text(font, msg, (width - font.width(msg)) / 2, height / 2, 0xFFFF5555, true);
            }
            return;
        }

        int availW = width - SIDE * 2, availH = height - TOP - BTM;
        scale = Math.min((float) availW / imgW, (float) availH / imgH);
        dw = (int) (imgW * scale);
        dh = (int) (imgH * scale);
        ox = SIDE + (availW - dw) / 2;
        oy = TOP + (availH - dh) / 2;

        graphics.blit(RenderPipelines.GUI_TEXTURED, texId, ox, oy, 0, 0, dw, dh, imgW, imgH, imgW, imgH);
        graphics.outline(ox - 1, oy - 1, dw + 2, dh + 2, 0xFFFFFFFF);

        //drawing preview
        if (drawing != null) {
            graphics.enableScissor(ox, oy, ox + dw, oy + dh);
            int argb = 0xFF000000 | rgb();
            int d = Math.max(1, Math.round((float) brush * scale));
            for (int i = 0; i < drawing.size(); i++) {
                double[] p = drawing.get(i);
                if (i ==0) {
                    stamp(graphics, p[0], p[1], d, argb);
                    continue;
                }
                double[] q = drawing.get(i - 1);
                double dist = Math.hypot(p[0] - q[0], p[1] - q[1]) * scale;
                int steps = Math.max(1, (int) Math.ceil(dist / Math.max(1.0, d / 2.0)));
                for (int s = 1; s <= steps; s++) {
                    stamp(graphics, q[0] + (p[0] - q[0]) * s / steps, q[1] + (p[1] - q[1]) * s / steps, d, argb);
                }
            }
            graphics.disableScissor();
        }

        //colour swatch
        int sx = x0(), sy = height - 30;
        int rgb = rgb();
        graphics.fill(sx, sy, sx + 100, sy + 20, 0xFF000000 | rgb);
        graphics.outline(sx, sy, 100, 20, 0xFFFFFFFF);
        double lum = ((rgb >> 16) & 255) * 0.299 + ((rgb >> 8) & 255) * 0.587 + (rgb & 255) * 0.114;
        Component lbl = Component.translatable("lightbox.editor.colour");
        graphics.text(font, lbl, sx + (100 - font.width(lbl)) / 2, sy + 6,
                lum > 140 ? 0xFF000000 : 0xFFFFFFFF, false);

        if (paletteOpen) drawPalette(graphics);
    }

    private void drawPalette(GuiGraphicsExtractor graphics) {
        graphics.fill(panelX(), panelY(), panelX() + PANEL_W, panelY() + PANEL_H, 0xF0101010);
        graphics.outline(panelX(), panelY(), PANEL_W, PANEL_H, 0xFF888888);

        if (wheelId != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, wheelId,
                    wheelCx() - WHEEL_D / 2, wheelCy() - WHEEL_D / 2, 0, 0,
                    WHEEL_D, WHEEL_D, WHEEL_TEX, WHEEL_TEX, WHEEL_TEX, WHEEL_TEX);
        }
        //marker showing current
        double ang = hue * 2 * Math.PI;
        int mx = (int) Math.round(wheelCx() + Math.cos(ang) * sat * (WHEEL_D / 2.0));
        int my = (int) Math.round(wheelCy() + Math.sin(ang) * sat * (WHEEL_D / 2.0));
        graphics.outline(mx - 3, my - 3, 7, 7, 0xFF000000);
        graphics.outline(mx - 2, my - 2, 5, 5, 0xFFFFFFFF);

        //brightness bar
        for (int i = 0; i < 50; i++) {
            int c = Color.HSBtoRGB(hue,sat,i / 49f) & 0xFFFFFF;
            graphics.fill(barX() + i * 2, barY(), barX() + i * 2 + 2, barY() + 20, 0xFF000000 | c);
        }
        graphics.outline(barX(), barY(), 100, 20, 0xFFFFFFFF);
        int vx = barX() + Math.round(val * 99);
        graphics.fill(vx - 2, barY() - 2, vx + 2, barY() + 22, 0xFF000000);
        graphics.fill(vx - 1, barY() - 2, vx + 1, barY() + 22, 0xFFFFFFFF);
    }

    private void stamp(GuiGraphicsExtractor g, double ix, double iy, int d, int argb) {
        int sx = (int) Math.round(ox + ix * scale - d / 2.0);
        int sy = (int) Math.round(oy + iy * scale - d / 2.0);
        g.fill(sx, sy, sx + d, sy + d, argb);
    }

    @Override
    public void removed() {
        if (texId != null) {
            minecraft.getTextureManager().release(texId);
            texId = null; // init() rebuilds
        }
        if (wheelId != null) {
            minecraft.getTextureManager().release(wheelId);
            wheelId = null;
        }
    }

    @Override
    public void onClose() {
        if (paletteOpen) {
            paletteOpen = false;
            return;
        }
        minecraft.gui.setScreen(parent);
    }

    private static class ValueSlider extends AbstractSliderButton {
        private final String key;
        private final double min, max;
        private final DoubleConsumer onChange;

        ValueSlider(int x, int y, int w, int h, String key, double min, double max, double start, DoubleConsumer onChange) {
            super(x, y, w, h, Component.empty(), (start - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private double real() {
            return min + value * (max - min); // 'value' is 0-1 pos
        }

        @Override
        protected void updateMessage() {
            if (key == null) return; // in case called early
            setMessage(Component.translatable(key).append(": " + Math.round(real())));
        }

        @Override
        protected void applyValue() {
            onChange.accept(real());
        }
    }
}