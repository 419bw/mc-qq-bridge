package com.mcqqbridge.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcqqbridge.stats.DailyRecord;
import com.mcqqbridge.stats.DailyRecord.GameEvent;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecord.Stay;
import com.mcqqbridge.stats.DailyRecord.TrailPoint;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.mcqqbridge.stats.DailyRecord.UNDERGROUND_Y;

/**
 * 将当日轨迹/事件渲染为 PNG 探索地图。
 * 底图（Chunky 导出）+ meta.json 提供像素↔世界坐标映射；缺失时降级为纯色背景，轨迹仍按世界坐标正确绘制。
 * 渲染在调用线程执行（须在异步线程调用，含图像解码与 AWT 绘制）。
 */
public class MapRenderer {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Color[] PALETTE = {
            new Color(231, 76, 60),   // 红
            new Color(52, 152, 219),  // 蓝
            new Color(46, 204, 113),  // 绿
            new Color(243, 156, 18),  // 橙
            new Color(155, 89, 182),  // 紫
            new Color(26, 188, 156)   // 青
    };
    private static final Color MAP_BG = new Color(24, 28, 36);
    private static final Color HEADER_BG = new Color(18, 20, 26);
    private static final Color DEATH_COLOR = new Color(255, 70, 70);
    private static final Color ADVANCE_COLOR = new Color(255, 215, 0);

    private final Path basemapPath;
    private final Path metaPath;
    private final int maxWidth;
    private final int padding;
    private final Logger logger;

    private volatile boolean loaded;
    private BufferedImage basemap;
    private Meta meta;

    private record Meta(int centerX, int centerZ, double blocksPerPixel, int width, int height) {}

    public MapRenderer(JavaPlugin plugin, int maxWidth, int padding) {
        this.basemapPath = plugin.getDataFolder().toPath().resolve("map").resolve("basemap.png");
        this.metaPath = plugin.getDataFolder().toPath().resolve("map").resolve("meta.json");
        this.maxWidth = maxWidth;
        this.padding = padding;
        this.logger = plugin.getLogger();
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                if (Files.isRegularFile(basemapPath) && Files.isRegularFile(metaPath)) {
                    basemap = ImageIO.read(basemapPath.toFile());
                    String json = Files.readString(metaPath, StandardCharsets.UTF_8);
                    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                    meta = new Meta(
                            o.get("centerX").getAsInt(),
                            o.get("centerZ").getAsInt(),
                            o.get("blocksPerPixel").getAsDouble(),
                            o.get("width").getAsInt(),
                            o.get("height").getAsInt());
                    logger.info("[MapRenderer] basemap loaded: " + meta.width + "x" + meta.height
                            + ", bpp=" + meta.blocksPerPixel);
                } else {
                    logger.info("[MapRenderer] basemap/meta not found, rendering without terrain background");
                }
            } catch (Exception e) {
                logger.warning("[MapRenderer] failed to load basemap/meta: " + e.getMessage());
                basemap = null;
                meta = null;
            }
            loaded = true;
        }
    }

    public byte[] render(Map<String, PlayerSnapshot> snapshots, String date) {
        ensureLoaded();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (PlayerSnapshot s : snapshots.values()) {
            for (TrailPoint tp : s.trail()) {
                minX = Math.min(minX, tp.x()); maxX = Math.max(maxX, tp.x());
                minZ = Math.min(minZ, tp.z()); maxZ = Math.max(maxZ, tp.z());
                any = true;
            }
            for (GameEvent ev : s.events()) {
                minX = Math.min(minX, ev.x()); maxX = Math.max(maxX, ev.x());
                minZ = Math.min(minZ, ev.z()); maxZ = Math.max(maxZ, ev.z());
                any = true;
            }
        }
        if (!any) {
            return null;
        }

        int winMinX = minX - padding, winMaxX = maxX + padding;
        int winMinZ = minZ - padding, winMaxZ = maxZ + padding;
        int winW = Math.max(1, winMaxX - winMinX);
        int winH = Math.max(1, winMaxZ - winMinZ);

        double outScale = Math.min(2.0, (double) maxWidth / winW);
        if (outScale < 0.25) outScale = 0.25;
        int canvasW = Math.max(1, (int) Math.round(winW * outScale));
        int canvasH = Math.max(1, (int) Math.round(winH * outScale));

        int header = 28 + snapshots.size() * 16;
        int totalH = canvasH + header;

        BufferedImage img = new BufferedImage(canvasW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(HEADER_BG);
            g.fillRect(0, 0, canvasW, header);

            if (basemap != null && meta != null) {
                int srcX1 = clamp(worldToMetaX(winMinX), 0, meta.width);
                int srcX2 = clamp(worldToMetaX(winMaxX), 0, meta.width);
                int srcY1 = clamp(worldToMetaZ(winMinZ), 0, meta.height);
                int srcY2 = clamp(worldToMetaZ(winMaxZ), 0, meta.height);
                if (srcX2 > srcX1 && srcY2 > srcY1) {
                    g.drawImage(basemap, 0, header, canvasW, totalH, srcX1, srcY1, srcX2, srcY2, null);
                } else {
                    g.setColor(MAP_BG);
                    g.fillRect(0, header, canvasW, canvasH);
                }
            } else {
                g.setColor(MAP_BG);
                g.fillRect(0, header, canvasW, canvasH);
            }

            Map<String, Color> colors = new LinkedHashMap<>();
            int idx = 0;
            for (String name : snapshots.keySet()) {
                colors.put(name, PALETTE[idx++ % PALETTE.length]);
            }

            BasicStroke solid = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            BasicStroke dashed = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    10f, new float[]{4f, 4f}, 0f);

            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                Color c = colors.get(e.getKey());
                PlayerSnapshot s = e.getValue();
                List<TrailPoint> trail = s.trail();

                for (int i = 1; i < trail.size(); i++) {
                    TrailPoint a = trail.get(i - 1);
                    TrailPoint b = trail.get(i);
                    boolean underground = b.y() < UNDERGROUND_Y;
                    g.setStroke(underground ? dashed : solid);
                    g.setColor(underground ? withAlpha(c, 150) : c);
                    g.drawLine(toPx(a.x(), winMinX, outScale), toPy(a.z(), winMinZ, outScale, header),
                            toPx(b.x(), winMinX, outScale), toPy(b.z(), winMinZ, outScale, header));
                }

                for (Stay st : s.stays()) {
                    int r = clamp(3 + (int) (st.minutes() / 2), 3, 14);
                    int cx = toPx(st.x(), winMinX, outScale);
                    int cy = toPy(st.z(), winMinZ, outScale, header);
                    g.setColor(withAlpha(c, 70));
                    g.fillOval(cx - r, cy - r, r * 2, r * 2);
                    g.setColor(c);
                    g.setStroke(solid);
                    g.drawOval(cx - r, cy - r, r * 2, r * 2);
                }
            }

            for (PlayerSnapshot s : snapshots.values()) {
                for (GameEvent ev : s.events()) {
                    int px = toPx(ev.x(), winMinX, outScale);
                    int py = toPy(ev.z(), winMinZ, outScale, header);
                    if ("death".equals(ev.type())) {
                        g.setColor(DEATH_COLOR);
                        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(px - 4, py - 4, px + 4, py + 4);
                        g.drawLine(px - 4, py + 4, px + 4, py - 4);
                    } else {
                        g.setColor(ADVANCE_COLOR);
                        g.fillOval(px - 4, py - 4, 8, 8);
                        g.setColor(Color.BLACK);
                        g.setStroke(solid);
                        g.drawOval(px - 4, py - 4, 8, 8);
                    }
                }
            }

            Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g.setFont(labelFont);
            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                List<TrailPoint> trail = e.getValue().trail();
                if (trail.isEmpty()) continue;
                TrailPoint last = trail.get(trail.size() - 1);
                int px = toPx(last.x(), winMinX, outScale) + 4;
                int py = toPy(last.z(), winMinZ, outScale, header) - 4;
                drawOutlinedText(g, e.getKey(), px, py, colors.get(e.getKey()));
            }

            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 14);
            g.setFont(titleFont);
            drawOutlinedText(g, "探索日报 " + date, 6, 18, Color.WHITE);

            Font legendFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g.setFont(legendFont);
            int ly = 34;
            for (Map.Entry<String, Color> e : colors.entrySet()) {
                g.setColor(e.getValue());
                g.fillRect(6, ly - 9, 10, 10);
                drawOutlinedText(g, e.getKey(), 20, ly, Color.WHITE);
                ly += 16;
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            logger.warning("[MapRenderer] encode png failed: " + e.getMessage());
            return null;
        }
    }

    private int worldToMetaX(int worldX) {
        return (int) Math.round((worldX - meta.centerX) / meta.blocksPerPixel + meta.width / 2.0);
    }

    private int worldToMetaZ(int worldZ) {
        return (int) Math.round((worldZ - meta.centerZ) / meta.blocksPerPixel + meta.height / 2.0);
    }

    private int toPx(int worldX, int winMinX, double outScale) {
        return (int) Math.round((worldX - winMinX) * outScale);
    }

    private int toPy(int worldZ, int winMinZ, double outScale, int header) {
        return header + (int) Math.round((worldZ - winMinZ) * outScale);
    }

    private void drawOutlinedText(Graphics2D g, String text, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
