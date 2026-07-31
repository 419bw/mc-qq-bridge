package com.mcqqbridge.report;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int TILE_BYTES = TILE_PIXELS * TILE_PIXELS * Integer.BYTES * 2; // rgb + height
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
    private final Map<String, Set<Long>> pendingByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRenderChunk = new ConcurrentHashMap<>();

    private record TileData(int[] rgb, int[] height) {}

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
                try (Stream<Path> files = Files.list(worldDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        Long key = parseTileName(f.getFileName().toString());
                        if (key != null) {
                            try {
                                if (Files.size(f) >= TILE_BYTES) {
                                    set.add(key);
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
        Set<Long> rendered = renderedByWorld.computeIfAbsent(w, k -> ConcurrentHashMap.newKeySet());
        if (rendered.contains(key)) {
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
                try {
                    TileData tile = renderChunk(snapshot);
                    writeTile(w, cx, cz, tile);
                    rendered.add(key);
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

    private TileData renderChunk(ChunkSnapshot snapshot) {
        int[] rgb = new int[TILE_PIXELS * TILE_PIXELS];
        int[] height = new int[TILE_PIXELS * TILE_PIXELS];
        for (int lz = 0; lz < TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                int idx = lz * TILE_PIXELS + lx;
                height[idx] = h;
                var mapColor = snapshot.getBlockData(lx, h, lz).getMapColor();
                rgb[idx] = mapColor != null ? mapColor.asRGB() : FALLBACK_RGB; // 方块级地图色，光照在拼图时统一算
            }
        }
        return new TileData(rgb, height);
    }

    private void writeTile(String worldName, int cx, int cz, TileData tile) throws IOException {
        Path file = tileFile(worldName, cx, cz);
        Files.createDirectories(file.getParent());
        ByteBuffer bb = ByteBuffer.allocate(TILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : tile.rgb) {
            bb.putInt(v);
        }
        for (int v : tile.height) {
            bb.putInt(v);
        }
        Files.write(file, bb.array());
    }

    private TileData readTile(String worldName, int cx, int cz) {
        Path file = tileFile(worldName, cx, cz);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < TILE_BYTES) {
                return null; // 旧格式（无高度）作废
            }
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int[] rgb = new int[TILE_PIXELS * TILE_PIXELS];
            int[] height = new int[TILE_PIXELS * TILE_PIXELS];
            bb.asIntBuffer().get(rgb);
            bb.asIntBuffer().get(height);
            return new TileData(rgb, height);
        } catch (IOException e) {
            logger.warning("[Terrain] read tile failed for " + worldName + " " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 按世界坐标窗口从磁盘拼出带坡度阴影的底图。两遍法：先拼 RGB 场+高度场，
     * 再对高度场做光照卷积乘到 RGB 上，保证坡面明暗连续、无 16 格网格缝。
     * win 边长超过上限返回 null（降级，防超大图）。缺失/未渲染区块填深色且不参与光照。
     */
    public BufferedImage buildTerrainImage(World overworld, int winMinX, int winMinZ, int winW, int winH) {
        if (winW > MAX_TERRAIN_SIDE || winH > MAX_TERRAIN_SIDE || winW <= 0 || winH <= 0) {
            return null;
        }
        String w = overworld.getName();
        Set<Long> rendered = renderedByWorld.getOrDefault(w, Set.of());

        int n = winW * winH;
        int[] rgbFlat = new int[n];
        int[] hFlat = new int[n];
        Arrays.fill(rgbFlat, MAP_BG_RGB);
        Arrays.fill(hFlat, SEA_LEVEL);

        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        TileData cur = null;
        for (int j = 0; j < winH; j++) {
            int worldZ = winMinZ + j;
            int cz = Math.floorDiv(worldZ, TILE_PIXELS);
            int lz = Math.floorMod(worldZ, TILE_PIXELS);
            for (int i = 0; i < winW; i++) {
                int worldX = winMinX + i;
                int cx = Math.floorDiv(worldX, TILE_PIXELS);
                if (cx != lastCx || cz != lastCz) {
                    lastCx = cx;
                    lastCz = cz;
                    cur = rendered.contains(pack(cx, cz)) ? readTile(w, cx, cz) : null;
                }
                if (cur != null) {
                    int lx = Math.floorMod(worldX, TILE_PIXELS);
                    int cidx = lz * TILE_PIXELS + lx;
                    int idx = j * winW + i;
                    rgbFlat[idx] = cur.rgb[cidx];
                    hFlat[idx] = cur.height[cidx];
                }
            }
        }

        int[] out = new int[n];
        for (int j = 0; j < winH; j++) {
            for (int i = 0; i < winW; i++) {
                int idx = j * winW + i;
                int base = rgbFlat[idx];
                if (base == MAP_BG_RGB) {
                    out[idx] = MAP_BG_RGB;
                    continue;
                }
                int parity = (i + j) & 1; // 棋盘抖动，与 mojang 一致
                int shade;
                if (base == WATER_BASE_RGB) {
                    // 水：原版按水深分档；水深未缓存，按浅水(depth=0)处理
                    double d2 = parity * 0.2;
                    shade = 1;
                    if (d2 < 0.5) shade = 2;
                    else if (d2 > 0.9) shade = 0;
                } else {
                    // 陆地：与北邻居高度差，scale0 公式 (h-hN)*0.8 + dither，阈值 ±0.6 分三档
                    int h = hFlat[idx];
                    int hN = j > 0 ? hFlat[idx - winW] : 0; // 首行无北邻居，mojang d0=0
                    double d2 = (h - hN) * 4.0 / (1 + 4) + (parity - 0.5) * 0.4;
                    shade = 1;
                    if (d2 > 0.6) shade = 2;
                    else if (d2 < -0.6) shade = 0;
                }
                out[idx] = applyShade(base, shade);
            }
        }

        BufferedImage img = new BufferedImage(winW, winH, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, winW, winH, out, 0, winW);
        return img;
    }

    public int tileCount() {
        return renderedByWorld.values().stream().mapToInt(Set::size).sum();
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
