package com.mcqqbridge.report;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 玩家驱动的地形增量渲染器：玩家进服/跑图时后台按"视距范围"渲染地形，色块+高度落盘，
 * 内存只保留"已渲染坐标集合"做标记位。日报时按轨迹窗口从磁盘读色块+高度，
 * 用两遍法做坡度阴影(hillshading)拼成无网格缝的底图。
 *
 * 渲染玩家视距范围而非仅踩过的区块：视距内区块 Paper 已加载，getChunkAtAsync 几乎零成本，
 * 跑图时视距扫过的区域连成实心探索带，自然减少空洞。
 *
 * 线程模型：PlayerMoveEvent/PlayerJoinEvent 在主线程做非阻塞判断与 getChunkAtAsync 发起；
 * 异步加载回调取 ChunkSnapshot 后提交到自有后台单线程渲染+写盘。
 */
public class TerrainTileCache implements Listener {

    private static final int TILE_PIXELS = 16;
    private static final int TILE_BYTES = TILE_PIXELS * TILE_PIXELS * Integer.BYTES * 3; // rgb + height + waterDepth
    private static final int TILE_MODE_BYTES = Integer.BYTES; // 文件头：渲染模式
    private static final int TILE_FILE_BYTES = TILE_MODE_BYTES + TILE_BYTES;

    /** 渲染模式，写入瓦片文件头：0=表面（主世界，最高非空气块）；1=空洞（下界，最长空气段底面）。 */
    private static final int MODE_SURFACE = 0;
    private static final int MODE_CAVE = 1;

    /** 坡度阴影敏感度：主世界 4.0（约 1 格高差触发满阴影）；下界垂直落差大，0.4 约 8~10 格才满阴影。 */
    private static final double SHADE_SENSITIVITY = 4.0;
    private static final double NETHER_SHADE_SENSITIVITY = 0.4;

    private static final int MAP_BG_RGB = new Color(24, 28, 36).getRGB();
    private static final int MAX_TERRAIN_SIDE = 4096;
    private static final int SEA_LEVEL = 63;
    private static final int WATER_BASE_RGB = 0x4040FF; // mojang WATER 基色，用于识别水像素

    private static final int FALLBACK_RGB = 0x5F8250;

    private final JavaPlugin plugin;
    private final Path tilesRoot;
    private final Logger logger;
    private final ExecutorService renderExecutor;
    private final Map<String, Set<Long>> renderedByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> freshByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> pendingByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRenderChunk = new ConcurrentHashMap<>();

    private record TileData(int[] rgb, int[] height, int[] waterDepth) {}

    public TerrainTileCache(JavaPlugin plugin) {
        this.plugin = plugin;
        this.tilesRoot = plugin.getDataFolder().toPath().resolve("map").resolve("tiles");
        this.logger = plugin.getLogger();
        this.renderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TerrainRender");
            t.setDaemon(true);
            return t;
        });
        scanDisk();
    }

    private void scanDisk() {
        if (!Files.isDirectory(tilesRoot)) {
            return;
        }
        int total = 0;
        try (Stream<Path> worlds = Files.list(tilesRoot)) {
            for (Path worldDir : (Iterable<Path>) worlds::iterator) {
                if (!Files.isDirectory(worldDir)) {
                    continue;
                }
                String worldName = worldDir.getFileName().toString();
                Set<Long> set = renderedByWorld.computeIfAbsent(worldName, w -> ConcurrentHashMap.newKeySet());
                Set<Long> fresh = freshByWorld.computeIfAbsent(worldName, w -> ConcurrentHashMap.newKeySet());
                World world = Bukkit.getWorld(worldName);
                int expectedMode = world != null ? renderModeFor(world) : -1; // -1：世界未加载，不过滤
                try (Stream<Path> files = Files.list(worldDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        Long key = parseTileName(f.getFileName().toString());
                        if (key != null) {
                            try {
                                if (Files.size(f) >= TILE_BYTES && tileModeMatches(f, expectedMode)) {
                                    set.add(key);
                                    fresh.add(key);
                                    total++;
                                }
                            } catch (IOException ignored) {
                            }
                        }
                    }
                } catch (IOException e) {
                    logger.warning("[Terrain] failed to scan tiles for " + worldName + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warning("[Terrain] failed to scan tiles root: " + e.getMessage());
        }
        logger.info("[Terrain] loaded " + total + " rendered tile(s) from disk");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        int cx = Math.floorDiv(player.getLocation().getBlockX(), TILE_PIXELS);
        int cz = Math.floorDiv(player.getLocation().getBlockZ(), TILE_PIXELS);
        long key = pack(cx, cz);
        Long last = lastRenderChunk.get(player.getUniqueId());
        if (last != null && last == key) {
            return; // 同一区块内移动，周围渲染范围未变
        }
        lastRenderChunk.put(player.getUniqueId(), key);
        renderAround(player.getWorld(), cx, cz);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int cx = Math.floorDiv(player.getLocation().getBlockX(), TILE_PIXELS);
        int cz = Math.floorDiv(player.getLocation().getBlockZ(), TILE_PIXELS);
        lastRenderChunk.put(player.getUniqueId(), pack(cx, cz));
        renderAround(player.getWorld(), cx, cz);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastRenderChunk.remove(event.getPlayer().getUniqueId());
    }

    private void renderAround(World world, int cx, int cz) {
        int r = Bukkit.getServer().getViewDistance();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ensureRendered(world, cx + dx, cz + dz);
            }
        }
    }

    private void ensureRendered(World world, int cx, int cz) {
        String w = world.getName();
        long key = pack(cx, cz);
        Set<Long> fresh = freshByWorld.computeIfAbsent(w, k -> ConcurrentHashMap.newKeySet());
        if (fresh.contains(key)) {
            return;
        }
        Set<Long> pending = pendingByWorld.computeIfAbsent(w, k -> ConcurrentHashMap.newKeySet());
        if (!pending.add(key)) {
            return; // 已在渲染中
        }
        world.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
            ChunkSnapshot snapshot;
            try {
                snapshot = chunk.getChunkSnapshot(true, true, false);
            } catch (Exception e) {
                logger.warning("[Terrain] snapshot failed for " + w + " " + cx + "," + cz + ": " + e.getMessage());
                pending.remove(key);
                return;
            }
            renderExecutor.submit(() -> {
                int mode = renderModeFor(world);
                try {
                    TileData tile = renderChunk(snapshot, mode);
                    writeTile(w, cx, cz, tile, mode);
                    renderedByWorld.computeIfAbsent(w, k -> ConcurrentHashMap.newKeySet()).add(key);
                    fresh.add(key);
                } catch (Exception e) {
                    logger.warning("[Terrain] render/write failed for " + w + " " + cx + "," + cz + ": " + e.getMessage());
                } finally {
                    pending.remove(key);
                }
            });
        }).exceptionally(ex -> {
            logger.warning("[Terrain] chunk load failed for " + w + " " + cx + "," + cz + ": " + ex.getMessage());
            pending.remove(key);
            return null;
        });
    }

    private TileData renderChunk(ChunkSnapshot snapshot, int mode) {
        if (mode == MODE_CAVE) {
            return renderCaveTile(snapshot);
        }
        return renderSurfaceTile(snapshot);
    }

    /** 表面模式（主世界）：每列取最高非空气块，waterDepth 记录水深用于水面明暗平滑。 */
    private TileData renderSurfaceTile(ChunkSnapshot snapshot) {
        int[] rgb = new int[TILE_PIXELS * TILE_PIXELS];
        int[] height = new int[TILE_PIXELS * TILE_PIXELS];
        int[] waterDepth = new int[TILE_PIXELS * TILE_PIXELS];
        for (int lz = 0; lz < TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                int idx = lz * TILE_PIXELS + lx;
                height[idx] = h;
                var bd = snapshot.getBlockData(lx, h, lz);
                var mapColor = bd.getMapColor();
                rgb[idx] = mapColor != null ? mapColor.asRGB() : FALLBACK_RGB; // 方块级地图色，明暗在拼图时按原版算法算
                int wd = 0;
                if (bd.getMaterial() == Material.WATER) {
                    for (int y = h; y > h - 64 && y >= -64; y--) {
                        if (snapshot.getBlockType(lx, y, lz) == Material.WATER) {
                            wd++;
                        } else {
                            break;
                        }
                    }
                }
                waterDepth[idx] = wd;
            }
        }
        return new TileData(rgb, height, waterDepth);
    }

    /**
     * 空洞模式（下界）：顶部基岩层使"最高非空气块"恒为基岩，无地形信息。
     * 改为整列选"最长空气段"（最大的洞）的底面——玩家最可能活动的空间；
     * 熔岩海、要塞桥面、菌毯、双层洞穴的大洞均由此自然选中；无洞列（实心山体）退回最高块（基岩）。
     */
    private TileData renderCaveTile(ChunkSnapshot snapshot) {
        int[] rgb = new int[TILE_PIXELS * TILE_PIXELS];
        int[] height = new int[TILE_PIXELS * TILE_PIXELS];
        int[] waterDepth = new int[TILE_PIXELS * TILE_PIXELS]; // 下界无水，恒 0（熔岩色不参与水深明暗）
        final int bottomY = 1; // 跳过底部基岩层（下界维度高度固定 0..127，y=0 为基岩）
        for (int lz = 0; lz < TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                int idx = lz * TILE_PIXELS + lx;
                final int fx = lx, fz = lz;
                int floorY = selectCaveFloorY(y -> snapshot.getBlockType(fx, y, fz).isAir(), h, bottomY);
                if (floorY < 0) {
                    floorY = h; // 无洞列（实心山体）：显示最高块（顶部基岩）
                }
                height[idx] = floorY;
                var bd = snapshot.getBlockData(lx, floorY, lz);
                var mapColor = bd.getMapColor();
                rgb[idx] = mapColor != null ? mapColor.asRGB() : FALLBACK_RGB;
                waterDepth[idx] = 0;
            }
        }
        return new TileData(rgb, height, waterDepth);
    }

    /**
     * 纯逻辑：在 [bottomY, topY] 内自上而下扫描，返回"最长空气段"下方的第一个非空气块 Y（洞底）；
     * 整列无空气段返回 -1。液体（熔岩）视为非空气，其顶面即洞底。
     */
    static int selectCaveFloorY(IntPredicate isAirAt, int topY, int bottomY) {
        int bestBottom = -1;
        int bestLen = -1;
        boolean inAir = false;
        int airTop = -1;
        for (int y = topY; y >= bottomY; y--) {
            boolean air = isAirAt.test(y);
            if (air && !inAir) {
                inAir = true;
                airTop = y;
            } else if (!air && inAir) {
                int len = airTop - y;
                if (len > bestLen) {
                    bestLen = len;
                    bestBottom = y;
                }
                inAir = false;
            }
        }
        return bestBottom;
    }

    /** 世界 -> 渲染模式映射（扫描/读写/拼图共用，集中一处）。 */
    static int renderModeFor(World world) {
        return world.getEnvironment() == World.Environment.NETHER ? MODE_CAVE : MODE_SURFACE;
    }

    /** 瓦片文件头模式是否匹配期望模式；无头旧文件（< 头+数据）视为模式 0。expectedMode < 0 不过滤。 */
    private static boolean tileModeMatches(Path f, int expectedMode) throws IOException {
        if (expectedMode < 0 || Files.size(f) < TILE_FILE_BYTES) {
            return true;
        }
        try (InputStream in = Files.newInputStream(f)) {
            byte[] head = in.readNBytes(TILE_MODE_BYTES);
            return ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt() == expectedMode;
        }
    }

    private void writeTile(String worldName, int cx, int cz, TileData tile, int mode) throws IOException {
        Path file = tileFile(worldName, cx, cz);
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

    /** 读瓦片并按期望模式校验：无头旧文件按模式 0 兼容读；模式不符（如下界旧基岩瓦片）视为未渲染。 */
    private TileData readTile(String worldName, int cx, int cz, int expectedMode) {
        Path file = tileFile(worldName, cx, cz);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            boolean hasHeader = bytes.length >= TILE_FILE_BYTES;
            if (!hasHeader && bytes.length < TILE_BYTES) {
                return null; // 残缺文件作废
            }
            int dataOffset = 0;
            if (hasHeader) {
                int mode = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (mode != expectedMode) {
                    return null;
                }
                dataOffset = TILE_MODE_BYTES;
            }
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
            logger.warning("[Terrain] read tile failed for " + worldName + " " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 按世界坐标窗口从磁盘拼出带坡度阴影的底图。自动选择缩放级别（step = 2^n），
     * 使输出图片不超过 MAX_TERRAIN_SIDE 像素。step=1 时每像素一个方块；
     * step=4 时每像素代表 4x4 区域（采样中心点），大尺度坡度阴影保留。
     * 缺失/未渲染区块填深色且不参与光照。
     */
    public BufferedImage buildTerrainImage(World overworld, int winMinX, int winMinZ, int winW, int winH) {
        if (winW <= 0 || winH <= 0) {
            return null;
        }
        int step = 1;
        while (winW / step > MAX_TERRAIN_SIDE || winH / step > MAX_TERRAIN_SIDE) {
            step <<= 1;
        }

        int outW = (winW + step - 1) / step;
        int outH = (winH + step - 1) / step;

        String w = overworld.getName();
        Set<Long> rendered = renderedByWorld.getOrDefault(w, Set.of());
        int expectedMode = renderModeFor(overworld);
        double shadeSensitivity = expectedMode == MODE_CAVE ? NETHER_SHADE_SENSITIVITY : SHADE_SENSITIVITY;

        int n = outW * outH;
        int[] rgbFlat = new int[n];
        int[] hFlat = new int[n];
        int[] wFlat = new int[n];
        Arrays.fill(rgbFlat, MAP_BG_RGB);
        Arrays.fill(hFlat, SEA_LEVEL);

        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        TileData cur = null;
        for (int j = 0; j < outH; j++) {
            int worldZ = winMinZ + j * step + step / 2;
            int cz = Math.floorDiv(worldZ, TILE_PIXELS);
            int lz = Math.floorMod(worldZ, TILE_PIXELS);
            for (int i = 0; i < outW; i++) {
                int worldX = winMinX + i * step + step / 2;
                int cx = Math.floorDiv(worldX, TILE_PIXELS);
                if (cx != lastCx || cz != lastCz) {
                    lastCx = cx;
                    lastCz = cz;
                    cur = rendered.contains(pack(cx, cz)) ? readTile(w, cx, cz, expectedMode) : null;
                }
                if (cur != null) {
                    int lx = Math.floorMod(worldX, TILE_PIXELS);
                    int cidx = lz * TILE_PIXELS + lx;
                    int idx = j * outW + i;
                    rgbFlat[idx] = cur.rgb[cidx];
                    hFlat[idx] = cur.height[cidx];
                    wFlat[idx] = cur.waterDepth[cidx];
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

    public int tileCount() {
        return renderedByWorld.values().stream().mapToInt(Set::size).sum();
    }

    /** 日报生成后调用：清除本轮渲染标记，玩家再次经过的区块将重新渲染以反映最新地形。 */
    public void resetFreshMarkers() {
        freshByWorld.clear();
        lastRenderChunk.clear();
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
    }

    private Path tileFile(String worldName, int cx, int cz) {
        return tilesRoot.resolve(worldName).resolve("c" + cx + "z" + cz + ".bin");
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static Long parseTileName(String name) {
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

    private static int applyShade(int base, int shade) {
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
}
