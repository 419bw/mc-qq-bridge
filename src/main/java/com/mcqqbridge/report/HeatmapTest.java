package com.mcqqbridge.report;

import com.mcqqbridge.stats.DailyRecord;
import com.mcqqbridge.stats.DailyRecord.*;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 独立测试入口：读取日报 JSON + 本地地形瓦片，调用 MapRenderer 渲染热力图并输出 PNG。
 * 不依赖 Bukkit / Gson，仅 JDK。用完即删。
 *
 * 用法: java com.mcqqbridge.report.HeatmapTest <json> <tilesDir> <output.png>
 */
public class HeatmapTest {

    // ================================================================
    //  极简 JSON 解析器（递归下降，仅覆盖本项目 JSON 结构）
    // ================================================================

    private static class Json {
        final String s;
        int p;

        Json(String s) { this.s = s; }

        void ws() { while (p < s.length() && s.charAt(p) <= ' ') p++; }
        char peek() { ws(); return s.charAt(p); }
        char next() { ws(); return s.charAt(p++); }
        boolean eof() { ws(); return p >= s.length(); }

        Object val() {
            char c = peek();
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') { p += 4; return null; }
            return num();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> obj() {
            next(); // {
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek() == '}') { next(); return m; }
            while (true) {
                String k = str();
                next(); // :
                m.put(k, val());
                if (next() == '}') break;
            }
            return m;
        }

        List<Object> arr() {
            next(); // [
            List<Object> l = new ArrayList<>();
            if (peek() == ']') { next(); return l; }
            while (true) {
                l.add(val());
                if (next() == ']') break;
            }
            return l;
        }

        String str() {
            next(); // "
            StringBuilder b = new StringBuilder();
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(p++);
                    switch (e) {
                        case '"', '\\', '/' -> b.append(e);
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        Number num() {
            ws();
            int st = p;
            if (p < s.length() && s.charAt(p) == '-') p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
            boolean fp = false;
            if (p < s.length() && s.charAt(p) == '.') { fp = true; p++; while (p < s.length() && Character.isDigit(s.charAt(p))) p++; }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) { fp = true; p++; if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++; while (p < s.length() && Character.isDigit(s.charAt(p))) p++; }
            String t = s.substring(st, p);
            return fp ? Double.parseDouble(t) : Long.parseLong(t);
        }

        boolean bool() {
            if (s.charAt(p) == 't') { p += 4; return true; }
            p += 5; return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object o) { return (List<Object>) o; }
    private static int asInt(Object o) { return ((Number) o).intValue(); }
    private static long asLong(Object o) { return ((Number) o).longValue(); }
    private static String asStr(Object o) { return (String) o; }

    private static int intOr(Map<String, Object> m, String k, int d) {
        Object v = m.get(k); return v == null ? d : asInt(v);
    }
    private static long longOr(Map<String, Object> m, String k, long d) {
        Object v = m.get(k); return v == null ? d : asLong(v);
    }
    private static String strOr(Map<String, Object> m, String k, String d) {
        Object v = m.get(k); return v == null ? d : asStr(v);
    }

    // ================================================================
    //  JSON -> PlayerSnapshot
    // ================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, PlayerSnapshot> parseSnapshots(Map<String, Object> root) {
        Map<String, Object> players = asObj(root.get("players"));
        Map<String, PlayerSnapshot> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> po = asObj(e.getValue());

            long playtimeMs = longOr(po, "playtimeMinutes", 0) * 60000;

            Map<String, Integer> stats = new HashMap<>();
            Object statsObj = po.get("stats");
            if (statsObj instanceof Map) {
                for (Map.Entry<String, Object> se : ((Map<String, Object>) statsObj).entrySet()) {
                    stats.put(se.getKey(), asInt(se.getValue()));
                }
            }

            int chatCount = intOr(po, "chatCount", 0);

            // trail
            List<TrailPoint> trail = new ArrayList<>();
            int minY = Integer.MAX_VALUE, ugPts = 0, sfPts = 0;
            for (Object item : asArr(po.get("trail"))) {
                Map<String, Object> tp = asObj(item);
                int x = asInt(tp.get("x")), y = asInt(tp.get("y")), z = asInt(tp.get("z"));
                long t = asLong(tp.get("t"));
                String w = asStr(tp.get("world"));
                trail.add(new TrailPoint(x, y, z, t, w));
                if (y < minY) minY = y;
                if (y < DailyRecord.UNDERGROUND_Y) ugPts++; else sfPts++;
            }

            // breaks
            List<BreakPoint> breaks = new ArrayList<>();
            for (Object item : asArr(po.get("breaks"))) {
                Map<String, Object> bp = asObj(item);
                breaks.add(new BreakPoint(asStr(bp.get("type")), asStr(bp.get("world")),
                        asInt(bp.get("x")), asInt(bp.get("y")), asInt(bp.get("z")), asLong(bp.get("t"))));
            }

            // stays
            List<Stay> stays = new ArrayList<>();
            for (Object item : asArr(po.get("stays"))) {
                Map<String, Object> sp = asObj(item);
                stays.add(new Stay(asInt(sp.get("x")), asInt(sp.get("z")),
                        asLong(sp.get("startT")), asLong(sp.get("endT")),
                        asLong(sp.get("minutes")), asStr(sp.get("world"))));
            }

            // events
            List<GameEvent> events = new ArrayList<>();
            for (Object item : asArr(po.get("events"))) {
                Map<String, Object> ev = asObj(item);
                events.add(new GameEvent(asStr(ev.get("type")), asStr(ev.get("world")),
                        asInt(ev.get("x")), asInt(ev.get("y")), asInt(ev.get("z")),
                        asLong(ev.get("t")), strOr(ev, "text", "")));
            }

            // chats
            List<ChatLine> chats = new ArrayList<>();
            for (Object item : asArr(po.get("chats"))) {
                Map<String, Object> cl = asObj(item);
                chats.add(new ChatLine(asLong(cl.get("t")), asStr(cl.get("player")), asStr(cl.get("text"))));
            }

            out.put(e.getKey(), new PlayerSnapshot(playtimeMs, stats, chatCount,
                    trail, events, chats, stays, breaks,
                    minY == Integer.MAX_VALUE ? 0 : minY, ugPts, sfPts));
        }
        return out;
    }

    // ================================================================
    //  地形瓦片读取 + hillshading（从 TerrainTileCache 提取，不依赖 Bukkit）
    // ================================================================

    private static final int TILE_PX = 16;
    private static final int TILE_BYTES = TILE_PX * TILE_PX * Integer.BYTES * 3;
    private static final int MAP_BG_RGB = new Color(24, 28, 36).getRGB();
    private static final int SEA_LEVEL = 63;
    private static final int WATER_BASE_RGB = 0x4040FF;
    private static final int MAX_SIDE = 4096;

    private record TileData(int[] rgb, int[] height, int[] waterDepth) {}

    private static TileData readTile(Path dir, int cx, int cz) {
        Path f = dir.resolve("c" + cx + "z" + cz + ".bin");
        if (!Files.isRegularFile(f)) return null;
        try {
            byte[] bytes = Files.readAllBytes(f);
            if (bytes.length < TILE_BYTES) return null;
            IntBuffer ib = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] rgb = new int[256], h = new int[256], w = new int[256];
            ib.get(rgb); ib.get(h); ib.get(w);
            return new TileData(rgb, h, w);
        } catch (IOException e) { return null; }
    }

    private static BufferedImage buildTerrain(Path tilesDir, String world,
                                               int winMinX, int winMinZ, int winW, int winH) {
        if (winW <= 0 || winH <= 0) return null;
        Path worldDir = tilesDir.resolve(world);
        if (!Files.isDirectory(worldDir)) return null;

        int step = 1;
        while (winW / step > MAX_SIDE || winH / step > MAX_SIDE) step <<= 1;
        int outW = (winW + step - 1) / step;
        int outH = (winH + step - 1) / step;

        int n = outW * outH;
        int[] rgbF = new int[n], hF = new int[n], wF = new int[n];
        Arrays.fill(rgbF, MAP_BG_RGB);
        Arrays.fill(hF, SEA_LEVEL);

        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        TileData cur = null;
        for (int j = 0; j < outH; j++) {
            int wz = winMinZ + j * step + step / 2;
            int cz = Math.floorDiv(wz, TILE_PX);
            int lz = Math.floorMod(wz, TILE_PX);
            for (int i = 0; i < outW; i++) {
                int wx = winMinX + i * step + step / 2;
                int cx = Math.floorDiv(wx, TILE_PX);
                if (cx != lastCx || cz != lastCz) {
                    lastCx = cx; lastCz = cz;
                    cur = readTile(worldDir, cx, cz);
                }
                if (cur != null) {
                    int lx = Math.floorMod(wx, TILE_PX);
                    int ci = lz * TILE_PX + lx;
                    int idx = j * outW + i;
                    rgbF[idx] = cur.rgb[ci]; hF[idx] = cur.height[ci]; wF[idx] = cur.waterDepth[ci];
                }
            }
        }

        int[] out = new int[n];
        for (int j = 0; j < outH; j++) {
            for (int i = 0; i < outW; i++) {
                int idx = j * outW + i;
                int base = rgbF[idx];
                if (base == MAP_BG_RGB) { out[idx] = MAP_BG_RGB; continue; }
                int parity = (i + j) & 1;
                int shade;
                if (base == WATER_BASE_RGB) {
                    double d2 = wF[idx] * 0.1 + parity * 0.2;
                    shade = d2 < 0.5 ? 2 : d2 > 0.9 ? 0 : 1;
                } else {
                    int h = hF[idx];
                    int hN = j > 0 ? hF[idx - outW] : 0;
                    double d2 = (h - hN) * 4.0 / (step * 5.0) + (parity - 0.5) * 0.4;
                    shade = d2 > 0.6 ? 2 : d2 < -0.6 ? 0 : 1;
                }
                out[idx] = applyShade(base, shade);
            }
        }

        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, outW, outH, out, 0, outW);
        return img;
    }

    private static int applyShade(int base, int shade) {
        int mult = switch (shade) { case 0 -> 180; case 2 -> 255; case 3 -> 135; default -> 220; };
        int r = ((base >> 16) & 0xFF) * mult / 255;
        int g = ((base >> 8) & 0xFF) * mult / 255;
        int b = (base & 0xFF) * mult / 255;
        return (r << 16) | (g << 8) | b;
    }

    // ================================================================
    //  main
    // ================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: HeatmapTest <json> <tilesDir> <output.png>");
            System.exit(1);
        }
        String jsonPath = args[0];
        String tilesDir = args[1];
        String outPath = args[2];

        System.out.println("Reading " + jsonPath + " ...");
        String jsonStr = Files.readString(Path.of(jsonPath));

        Json json = new Json(jsonStr);
        Map<String, Object> root = json.obj();
        String date = asStr(root.get("date"));

        Map<String, PlayerSnapshot> snapshots = parseSnapshots(root);
        System.out.println("Parsed " + snapshots.size() + " player(s): " + snapshots.keySet());

        String mainWorld = "world";
        int mapPadding = 64;
        int maxWidth = 1200;

        int[] win = MapRenderer.computeWindow(snapshots, mapPadding, mainWorld);
        if (win == null) {
            System.err.println("No overworld activity");
            System.exit(1);
        }
        System.out.printf("Window: [%d, %d] %d x %d%n", win[0], win[1], win[2], win[3]);

        BufferedImage terrain = buildTerrain(Path.of(tilesDir), mainWorld, win[0], win[1], win[2], win[3]);
        System.out.println("Terrain: " + (terrain != null ? terrain.getWidth() + "x" + terrain.getHeight() : "null"));

        MapRenderer renderer = new MapRenderer(maxWidth, mapPadding);
        byte[] png = renderer.render(snapshots, date, terrain, win, mainWorld, null);

        if (png != null) {
            Files.write(Path.of(outPath), png);
            System.out.println("Written " + outPath + " (" + png.length + " bytes)");
        } else {
            System.err.println("Render returned null");
            System.exit(1);
        }
    }
}
