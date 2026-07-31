package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord.BreakPoint;
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
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 将当日轨迹/事件渲染为 PNG 探索地图，叠加在调用方提供的地形底图上。
 * 底图由 TerrainTileCache 按轨迹窗口从磁盘拼出；底图为 null 时降级为纯色背景。
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
    private static final Color JOIN_COLOR = new Color(70, 210, 110);
    private static final Color QUIT_COLOR = new Color(255, 90, 90);
    private static final Color REJOIN_COLOR = new Color(255, 200, 40);
    private static final Color TP_COLOR = new Color(150, 150, 150);

    private static final DateTimeFormatter HM_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private static final int SEA_LEVEL = 63;
    private static final double SIMPLIFY_EPSILON = 2.0;
    private static final double ARROW_INTERVAL_PX = 50.0;
    private static final double MIN_SEGMENT_PX = 1.0;

    private final int maxWidth;
    private final int padding;

    public MapRenderer(int maxWidth, int padding) {
        this.maxWidth = maxWidth;
        this.padding = padding;
    }

    /**
     * 计算主世界轨迹/事件的世界坐标窗口（含 padding）。无活动返回 null。
     * 返回 {winMinX, winMinZ, winW, winH}，供底图拼接与渲染共用，保证坐标一致。
     */
    public static int[] computeWindow(Map<String, PlayerSnapshot> snapshots, int padding, String mainWorld) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (PlayerSnapshot s : snapshots.values()) {
            for (TrailPoint tp : s.trail()) {
                if (!mainWorld.equals(tp.world())) continue;
                minX = Math.min(minX, tp.x()); maxX = Math.max(maxX, tp.x());
                minZ = Math.min(minZ, tp.z()); maxZ = Math.max(maxZ, tp.z());
                any = true;
            }
            for (GameEvent ev : s.events()) {
                if (!mainWorld.equals(ev.world())) continue;
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

    public byte[] render(Map<String, PlayerSnapshot> snapshots, String date, BufferedImage terrain, int[] win,
                         String mainWorld) {
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

            BasicStroke casingStroke = new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            BasicStroke trailStroke = new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            BasicStroke stayStroke = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

            // 预计算主世界分段轨迹（断点切段 + 世界过滤），段内才连线
            Map<String, List<List<TrailPoint>>> segmentsByPlayer = new LinkedHashMap<>();
            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                List<List<TrailPoint>> segments = new ArrayList<>();
                List<TrailPoint> current = new ArrayList<>();
                int bi = 0;
                for (TrailPoint p : e.getValue().trail()) {
                    boolean cut = !mainWorld.equals(p.world());
                    if (!cut && bi < e.getValue().breaks().size()) {
                        while (bi < e.getValue().breaks().size()
                                && e.getValue().breaks().get(bi).t() < (current.isEmpty() ? Long.MIN_VALUE : current.get(current.size() - 1).t())) {
                            bi++;
                        }
                        if (bi < e.getValue().breaks().size()
                                && e.getValue().breaks().get(bi).t() < p.t()) {
                            cut = true;
                        }
                    }
                    if (cut) {
                        if (!current.isEmpty()) {
                            segments.add(simplifyTrail(current, SIMPLIFY_EPSILON));
                            current = new ArrayList<>();
                        }
                        if (mainWorld.equals(p.world())) {
                            current.add(p);
                        }
                    } else {
                        current.add(p);
                    }
                }
                if (!current.isEmpty()) {
                    segments.add(simplifyTrail(current, SIMPLIFY_EPSILON));
                }
                segmentsByPlayer.put(e.getKey(), segments);
            }

            // Pass 1: 所有玩家的暗色包边
            g.setStroke(casingStroke);
            g.setColor(withAlpha(Color.BLACK, 100));
            for (List<List<TrailPoint>> segments : segmentsByPlayer.values()) {
                for (List<TrailPoint> seg : segments) {
                    for (int i = 1; i < seg.size(); i++) {
                        TrailPoint a = seg.get(i - 1);
                        TrailPoint b = seg.get(i);
                        int ax = toPx(a.x(), winMinX, outScale), ay = toPy(a.z(), winMinZ, outScale, header);
                        int bx = toPx(b.x(), winMinX, outScale), by = toPy(b.z(), winMinZ, outScale, header);
                        if (screenDist(ax, ay, bx, by) < MIN_SEGMENT_PX) continue;
                        g.drawLine(ax, ay, bx, by);
                    }
                }
            }

            // Pass 2: 所有玩家的彩色轨迹（高度→透明度）
            g.setStroke(trailStroke);
            for (Map.Entry<String, List<List<TrailPoint>>> e : segmentsByPlayer.entrySet()) {
                Color c = colors.get(e.getKey());
                for (List<TrailPoint> seg : e.getValue()) {
                    for (int i = 1; i < seg.size(); i++) {
                        TrailPoint a = seg.get(i - 1);
                        TrailPoint b = seg.get(i);
                        int ax = toPx(a.x(), winMinX, outScale), ay = toPy(a.z(), winMinZ, outScale, header);
                        int bx = toPx(b.x(), winMinX, outScale), by = toPy(b.z(), winMinZ, outScale, header);
                        if (screenDist(ax, ay, bx, by) < MIN_SEGMENT_PX) continue;
                        g.setColor(withAlpha(c, heightAlpha(b.y())));
                        g.drawLine(ax, ay, bx, by);
                    }
                }
            }

            // Pass 3: 所有玩家的方向箭头（段内）
            for (Map.Entry<String, List<List<TrailPoint>>> e : segmentsByPlayer.entrySet()) {
                for (List<TrailPoint> seg : e.getValue()) {
                    drawArrows(g, seg, colors.get(e.getKey()), winMinX, winMinZ, outScale, header);
                }
            }

            // 会话标记（登录绿+ / 退出红- / 重登黄菱形 / 传送灰点）
            Font markerFont = new Font(Font.SANS_SERIF, Font.PLAIN, 8);
            g.setFont(markerFont);
            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                List<BreakPoint> breaks = e.getValue().breaks();
                for (int i = 0; i < breaks.size(); i++) {
                    BreakPoint b = breaks.get(i);
                    if (!mainWorld.equals(b.world())) continue;
                    int px = toPx(b.x(), winMinX, outScale);
                    int py = toPy(b.z(), winMinZ, outScale, header);
                    if (px < -20 || px > canvasW + 20 || py < header - 20 || py > totalH + 20) continue;

                    if ("QUIT".equals(b.type())) {
                        // 检查下一个断点是否为 JOIN 且同点（重登）→ 黄菱形 + 离线分钟数
                        BreakPoint next = (i + 1 < breaks.size()) ? breaks.get(i + 1) : null;
                        if (next != null && "JOIN".equals(next.type()) && mainWorld.equals(next.world())) {
                            int nx = toPx(next.x(), winMinX, outScale);
                            int ny = toPy(next.z(), winMinZ, outScale, header);
                            if (Math.hypot(nx - px, ny - py) < 12) {
                                drawDiamond(g, px, py, REJOIN_COLOR);
                                long offlineMin = Math.max(0, (next.t() - b.t()) / 60000);
                                drawOutlinedText(g, offlineMin + "m", px + 6, py - 4, REJOIN_COLOR);
                                i++; // 跳过已合并的 JOIN
                                continue;
                            }
                        }
                        drawMinus(g, px, py, QUIT_COLOR);
                        drawOutlinedText(g, formatHm(b.t()), px + 6, py - 4, QUIT_COLOR);
                    } else if ("JOIN".equals(b.type())) {
                        drawPlus(g, px, py, JOIN_COLOR);
                        drawOutlinedText(g, formatHm(b.t()), px + 6, py - 4, JOIN_COLOR);
                    } else if ("TP".equals(b.type())) {
                        g.setColor(TP_COLOR);
                        g.fillOval(px - 2, py - 2, 4, 4);
                    }
                    // DEATH 断点不画标记（红X 已由事件绘制）
                }
            }

            // 停留圈
            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                Color c = colors.get(e.getKey());
                for (Stay st : e.getValue().stays()) {
                    int r = clamp(3 + (int) (st.minutes() / 2), 3, 14);
                    int cx = toPx(st.x(), winMinX, outScale);
                    int cy = toPy(st.z(), winMinZ, outScale, header);
                    g.setColor(withAlpha(c, 70));
                    g.fillOval(cx - r, cy - r, r * 2, r * 2);
                    g.setColor(c);
                    g.setStroke(stayStroke);
                    g.drawOval(cx - r, cy - r, r * 2, r * 2);
                }
            }

            for (PlayerSnapshot s : snapshots.values()) {
                for (GameEvent ev : s.events()) {
                    if (!mainWorld.equals(ev.world())) continue;
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
                        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawOval(px - 4, py - 4, 8, 8);
                    }
                }
            }

            Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g.setFont(labelFont);
            for (Map.Entry<String, PlayerSnapshot> e : snapshots.entrySet()) {
                List<TrailPoint> trail = e.getValue().trail();
                TrailPoint last = null;
                for (int i = trail.size() - 1; i >= 0; i--) {
                    if (mainWorld.equals(trail.get(i).world())) {
                        last = trail.get(i);
                        break;
                    }
                }
                if (last == null) continue;
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

    // ---- 轨迹简化 (Douglas-Peucker, XZ 平面) ----

    private static List<TrailPoint> simplifyTrail(List<TrailPoint> trail, double epsilon) {
        if (trail.size() <= 2) {
            return trail;
        }
        boolean[] keep = new boolean[trail.size()];
        keep[0] = true;
        keep[trail.size() - 1] = true;
        douglasPeucker(trail, 0, trail.size() - 1, epsilon, keep);
        List<TrailPoint> result = new ArrayList<>();
        for (int i = 0; i < trail.size(); i++) {
            if (keep[i]) {
                result.add(trail.get(i));
            }
        }
        return result;
    }

    private static void douglasPeucker(List<TrailPoint> pts, int start, int end, double eps, boolean[] keep) {
        if (end - start < 2) {
            return;
        }
        double maxDist = 0;
        int maxIdx = start;
        double ax = pts.get(start).x(), az = pts.get(start).z();
        double bx = pts.get(end).x(), bz = pts.get(end).z();
        double dx = bx - ax, dz = bz - az;
        double lenSq = dx * dx + dz * dz;

        for (int i = start + 1; i < end; i++) {
            double px = pts.get(i).x(), pz = pts.get(i).z();
            double dist;
            if (lenSq == 0) {
                dist = Math.hypot(px - ax, pz - az);
            } else {
                double cross = Math.abs((px - ax) * dz - (pz - az) * dx);
                dist = cross / Math.sqrt(lenSq);
            }
            if (dist > maxDist) {
                maxDist = dist;
                maxIdx = i;
            }
        }
        if (maxDist > eps) {
            keep[maxIdx] = true;
            douglasPeucker(pts, start, maxIdx, eps, keep);
            douglasPeucker(pts, maxIdx, end, eps, keep);
        }
    }

    // ---- 方向箭头 ----

    private void drawArrows(Graphics2D g, List<TrailPoint> trail, Color c,
                            int winMinX, int winMinZ, double outScale, int header) {
        if (trail.size() < 2) return;
        double accumulated = 0;
        for (int i = 1; i < trail.size(); i++) {
            TrailPoint a = trail.get(i - 1);
            TrailPoint b = trail.get(i);
            int ax = toPx(a.x(), winMinX, outScale), ay = toPy(a.z(), winMinZ, outScale, header);
            int bx = toPx(b.x(), winMinX, outScale), by = toPy(b.z(), winMinZ, outScale, header);
            double segLen = screenDist(ax, ay, bx, by);
            if (segLen < MIN_SEGMENT_PX) continue;

            accumulated += segLen;
            if (accumulated >= ARROW_INTERVAL_PX) {
                accumulated -= ARROW_INTERVAL_PX;
                double angle = Math.atan2(by - ay, bx - ax);
                int mx = (ax + bx) / 2, my = (ay + by) / 2;
                drawArrow(g, mx, my, angle, c);
            }
        }
    }

    private void drawArrow(Graphics2D g, int x, int y, double angle, Color c) {
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(8, 0);
        arrow.lineTo(-4, -5);
        arrow.lineTo(-2, 0);
        arrow.lineTo(-4, 5);
        arrow.closePath();

        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        at.rotate(angle);
        Path2D.Double transformed = (Path2D.Double) arrow.clone();
        transformed.transform(at);

        g.setColor(Color.BLACK);
        g.fill(transformed);
        g.setColor(c);
        g.fill(at.createTransformedShape(arrow));
    }

    // ---- 会话标记 ----

    private void drawPlus(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x - 3, y, x + 3, y);
        g.drawLine(x, y - 3, x, y + 3);
    }

    private void drawMinus(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x - 3, y, x + 3, y);
    }

    private void drawDiamond(Graphics2D g, int x, int y, Color c) {
        Path2D.Double diamond = new Path2D.Double();
        diamond.moveTo(x, y - 5);
        diamond.lineTo(x + 5, y);
        diamond.lineTo(x, y + 5);
        diamond.lineTo(x - 5, y);
        diamond.closePath();
        g.setColor(Color.BLACK);
        g.fill(diamond);
        g.setColor(c);
        g.fill(diamond);
    }

    private static String formatHm(long epochMs) {
        return HM_FORMAT.format(Instant.ofEpochMilli(epochMs));
    }

    // ---- 高度→透明度 ----

    private static int heightAlpha(int y) {
        double alpha;
        if (y < SEA_LEVEL) {
            alpha = 210 + (y - SEA_LEVEL) * 1.26;
        } else {
            alpha = 210 + (y - SEA_LEVEL) * 0.33;
        }
        return (int) Math.round(Math.max(50, Math.min(255, alpha)));
    }

    // ---- 工具方法 ----

    private int toPx(int worldX, int winMinX, double outScale) {
        return (int) Math.round((worldX - winMinX) * outScale);
    }

    private int toPy(int worldZ, int winMinZ, double outScale, int header) {
        return header + (int) Math.round((worldZ - winMinZ) * outScale);
    }

    private static double screenDist(int ax, int ay, int bx, int by) {
        return Math.hypot(bx - ax, by - ay);
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
