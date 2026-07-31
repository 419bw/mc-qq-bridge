package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord.GameEvent;
import com.mcqqbridge.stats.DailyRecord.PlayerSnapshot;
import com.mcqqbridge.stats.DailyRecord.Stay;
import com.mcqqbridge.stats.DailyRecord.TrailPoint;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.mcqqbridge.stats.DailyRecord.UNDERGROUND_Y;

/**
 * 将当日轨迹/事件渲染为 PNG 探索地图，叠加在调用方提供的地形底图上。
 * 底图由 TerrainTileCache 按轨迹窗口从磁盘拼出（每格1像素，尺寸=窗口格数）；底图为 null 时降级为纯色背景。
 * 矢量叠加（轨迹/停留/事件/标签/标题/图例）与底图来源无关。渲染须在异步线程调用（含 AWT 绘制）。
 */
public class MapRenderer {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Logger LOGGER = Logger.getLogger("McQqBridge.MapRenderer");

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

    private final int maxWidth;
    private final int padding;

    public MapRenderer(int maxWidth, int padding) {
        this.maxWidth = maxWidth;
        this.padding = padding;
    }

    /**
     * 计算轨迹/事件的世界坐标窗口（含 padding）。无活动返回 null。
     * 返回 {winMinX, winMinZ, winW, winH}，供底图拼接与渲染共用，保证坐标一致。
     */
    public static int[] computeWindow(Map<String, PlayerSnapshot> snapshots, int padding) {
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
        int winMinX = minX - padding;
        int winMinZ = minZ - padding;
        int winW = Math.max(1, (maxX + padding) - winMinX);
        int winH = Math.max(1, (maxZ + padding) - winMinZ);
        return new int[]{winMinX, winMinZ, winW, winH};
    }

    public byte[] render(Map<String, PlayerSnapshot> snapshots, String date, BufferedImage terrain, int[] win) {
        if (win == null) {
            return null;
        }
        int winMinX = win[0], winMinZ = win[1], winW = win[2], winH = win[3];

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

            if (terrain != null) {
                g.drawImage(terrain, 0, header, canvasW, totalH,
                        0, 0, terrain.getWidth(), terrain.getHeight(), null);
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
            LOGGER.warning("[MapRenderer] encode png failed: " + e.getMessage());
            return null;
        }
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
