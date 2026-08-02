package com.mcqqbridge.report;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 瓦片二进制格式编解码 + 地形底图 hillshading 合成，纯 JDK 无 Bukkit 依赖。
 * TerrainTileCache（服务端渲染/读写）与 HeatmapTest（离线验证）共用本类，
 * 保证瓦片格式常量与阴影算法单一定义。
 */
public final class TileCodec {

    private TileCodec() {}

    // ---- 瓦片格式常量 ----

    public static final int TILE_PIXELS = 16;
    public static final int TILE_BYTES = TILE_PIXELS * TILE_PIXELS * Integer.BYTES * 3; // rgb + height + waterDepth
    public static final int TILE_MODE_BYTES = Integer.BYTES; // 文件头：渲染模式
    public static final int TILE_FILE_BYTES = TILE_MODE_BYTES + TILE_BYTES;

    /** 渲染模式，写入瓦片文件头：0=表面（主世界，最高非空气块）；1=空洞（下界，最长空气段底面）。 */
    public static final int MODE_SURFACE = 0;
    public static final int MODE_CAVE = 1;

    // ---- 地图渲染常量 ----

    /** 坡度阴影敏感度：主世界 4.0（约 1 格高差触发满阴影）；下界垂直落差大，0.4 约 8~10 格才满阴影。 */
    public static final double SHADE_SENSITIVITY = 4.0;
    public static final double NETHER_SHADE_SENSITIVITY = 0.4;

    public static final int MAP_BG_RGB = new Color(24, 28, 36).getRGB();
    public static final int MAX_TERRAIN_SIDE = 4096;
    public static final int SEA_LEVEL = 63;
    public static final int WATER_BASE_RGB = 0x4040FF; // mojang WATER 基色，用于识别水像素
    public static final int FALLBACK_RGB = 0x5F8250;

    // ---- 数据结构 ----

    public record TileData(int[] rgb, int[] height, int[] waterDepth) {}

    /** 按区块坐标读取瓦片的回调，由调用方提供（服务端从磁盘+缓存读，离线工具直接读目录）。 */
    @FunctionalInterface
    public interface TileReader {
        TileData read(int cx, int cz);
    }

    // ---- 瓦片文件读写 ----

    /**
     * 读瓦片文件并按期望模式校验：无头旧文件按模式 0 兼容读；模式不符返回 null。
     */
    public static TileData readTile(Path file, int expectedMode) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            boolean hasHeader = bytes.length >= TILE_FILE_BYTES;
            if (!hasHeader) {
                if (bytes.length < TILE_BYTES) {
                    return null; // 残缺文件作废
                }
                if (expectedMode != MODE_SURFACE) {
                    return null; // 无头旧文件 = 模式 0，与期望模式不符作废
                }
            } else {
                int mode = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (mode != expectedMode) {
                    return null;
                }
            }
            int dataOffset = hasHeader ? TILE_MODE_BYTES : 0;
            IntBuffer ib = ByteBuffer.wrap(bytes, dataOffset, bytes.length - dataOffset)
                    .order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] rgb = new int[TILE_PIXELS * TILE_PIXELS];
            int[] height = new int[TILE_PIXELS * TILE_PIXELS];
            int[] waterDepth = new int[TILE_PIXELS * TILE_PIXELS];
            ib.get(rgb);
            ib.get(height);
            ib.get(waterDepth);
            return new TileData(rgb, height, waterDepth);
        } catch (IOException e) {
            return null;
        }
    }

    /** 瓦片文件头模式是否匹配期望模式；无头旧文件（< 头+数据）视为模式 0。expectedMode < 0 不过滤。 */
    public static boolean tileModeMatches(Path f, int expectedMode) throws IOException {
        if (expectedMode < 0) {
            return true;
        }
        if (Files.size(f) < TILE_FILE_BYTES) {
            return expectedMode == MODE_SURFACE; // 无头旧文件 = 模式 0，仅表面模式认
        }
        try (InputStream in = Files.newInputStream(f)) {
            byte[] head = in.readNBytes(TILE_MODE_BYTES);
            return ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt() == expectedMode;
        }
    }

    public static void writeTile(Path file, TileData tile, int mode) throws IOException {
        Files.createDirectories(file.getParent());
        ByteBuffer bb = ByteBuffer.allocate(TILE_FILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(mode);
        for (int v : tile.rgb) {
            bb.putInt(v);
        }
        for (int v : tile.height) {
            bb.putInt(v);
        }
        for (int v : tile.waterDepth) {
            bb.putInt(v);
        }
        Files.write(file, bb.array());
    }

    // ---- 地形底图合成 ----

    /**
     * 按世界坐标窗口拼出带坡度阴影的底图。自动选择缩放级别（step = 2^n），
     * 使输出图片不超过 MAX_TERRAIN_SIDE 像素。缺失/未渲染区块填深色且不参与光照。
     *
     * @param shadeSensitivity 阴影敏感度（主世界 SHADE_SENSITIVITY / 下界 NETHER_SHADE_SENSITIVITY）
     * @param reader           瓦片读取回调，返回 null 表示该区块未渲染
     */
    public static BufferedImage buildTerrainImage(int winMinX, int winMinZ, int winW, int winH,
                                                  double shadeSensitivity, TileReader reader) {
        if (winW <= 0 || winH <= 0) {
            return null;
        }
        int step = 1;
        while (winW / step > MAX_TERRAIN_SIDE || winH / step > MAX_TERRAIN_SIDE) {
            step <<= 1;
        }

        int outW = (winW + step - 1) / step;
        int outH = (winH + step - 1) / step;

        int n = outW * outH;
        int[] rgbFlat = new int[n];
        int[] hFlat = new int[n];
        int[] wFlat = new int[n];
        Arrays.fill(rgbFlat, MAP_BG_RGB);
        Arrays.fill(hFlat, SEA_LEVEL);

        // tile-major 采样：按瓦片遍历，每个瓦片只读一次；无数据瓦片整块跳过（不迭代其像素）。
        // 输出像素 (i,j) 的采样点 = (winMinX + i*step + half, winMinZ + j*step + half)，与逐行版逐像素一致。
        int half = step / 2;
        int cxMin = Math.floorDiv(winMinX, TILE_PIXELS);
        int cxMax = Math.floorDiv(winMinX + winW - 1, TILE_PIXELS);
        int czMin = Math.floorDiv(winMinZ, TILE_PIXELS);
        int czMax = Math.floorDiv(winMinZ + winH - 1, TILE_PIXELS);
        for (int cz = czMin; cz <= czMax; cz++) {
            int tileMinZ = cz * TILE_PIXELS;
            int jLo = -Math.floorDiv(-(tileMinZ - winMinZ - half), step);
            int jHi = Math.floorDiv(tileMinZ + TILE_PIXELS - 1 - winMinZ - half, step);
            if (jLo < 0) jLo = 0;
            if (jHi >= outH) jHi = outH - 1;
            if (jLo > jHi) continue;
            for (int cx = cxMin; cx <= cxMax; cx++) {
                TileData tile = reader.read(cx, cz);
                if (tile == null) continue;
                int tileMinX = cx * TILE_PIXELS;
                int iLo = -Math.floorDiv(-(tileMinX - winMinX - half), step);
                int iHi = Math.floorDiv(tileMinX + TILE_PIXELS - 1 - winMinX - half, step);
                if (iLo < 0) iLo = 0;
                if (iHi >= outW) iHi = outW - 1;
                if (iLo > iHi) continue;
                for (int j = jLo; j <= jHi; j++) {
                    int lz = winMinZ + j * step + half - tileMinZ;
                    int rowBase = j * outW;
                    for (int i = iLo; i <= iHi; i++) {
                        int lx = winMinX + i * step + half - tileMinX;
                        int cidx = lz * TILE_PIXELS + lx;
                        int idx = rowBase + i;
                        rgbFlat[idx] = tile.rgb[cidx];
                        hFlat[idx] = tile.height[cidx];
                        wFlat[idx] = tile.waterDepth[cidx];
                    }
                }
            }
        }

        int[] out = new int[n];
        for (int j = 0; j < outH; j++) {
            for (int i = 0; i < outW; i++) {
                int idx = j * outW + i;
                int base = rgbFlat[idx];
                if (base == MAP_BG_RGB) {
                    out[idx] = MAP_BG_RGB;
                    continue;
                }
                int parity = (i + j) & 1;
                int shade;
                if (base == WATER_BASE_RGB) {
                    double d2 = wFlat[idx] * 0.1 + parity * 0.2;
                    shade = 1;
                    if (d2 < 0.5) shade = 2;
                    else if (d2 > 0.9) shade = 0;
                } else {
                    int h = hFlat[idx];
                    int hN = j > 0 ? hFlat[idx - outW] : 0;
                    double d2 = (h - hN) * shadeSensitivity / (step * 5.0) + (parity - 0.5) * 0.4;
                    shade = 1;
                    if (d2 > 0.6) shade = 2;
                    else if (d2 < -0.6) shade = 0;
                }
                out[idx] = applyShade(base, shade);
            }
        }

        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, outW, outH, out, 0, outW);
        return img;
    }

    // ---- 阴影 ----

    public static int applyShade(int base, int shade) {
        int mult = switch (shade) {
            case 0 -> 180;
            case 2 -> 255;
            case 3 -> 135;
            default -> 220;
        };
        int r = ((base >> 16) & 0xFF) * mult / 255;
        int g = ((base >> 8) & 0xFF) * mult / 255;
        int b = (base & 0xFF) * mult / 255;
        return (r << 16) | (g << 8) | b;
    }

    // ---- 坐标工具 ----

    public static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static Long parseTileName(String name) {
        if (!name.startsWith("c") || !name.endsWith(".bin")) {
            return null;
        }
        String body = name.substring(1, name.length() - ".bin".length());
        int split = body.indexOf('z');
        if (split <= 0 || split == body.length() - 1) {
            return null;
        }
        try {
            int cx = Integer.parseInt(body.substring(0, split));
            int cz = Integer.parseInt(body.substring(split + 1));
            return pack(cx, cz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String tileFileName(int cx, int cz) {
        return "c" + cx + "z" + cz + ".bin";
    }
}
