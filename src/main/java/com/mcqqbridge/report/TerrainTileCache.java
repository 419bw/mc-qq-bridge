package com.mcqqbridge.report;

import com.mcqqbridge.report.TileCodec.TileData;
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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;
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
 *
 * 瓦片格式常量、读写、hillshading 合成统一由 {@link TileCodec} 提供。
 */
public class TerrainTileCache implements Listener {

    private final JavaPlugin plugin;
    private final Path tilesRoot;
    private final Logger logger;
    private final ExecutorService renderExecutor;
    private final Map<String, Set<Long>> renderedByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> freshByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> pendingByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRenderChunk = new ConcurrentHashMap<>();

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
                        Long key = TileCodec.parseTileName(f.getFileName().toString());
                        if (key != null) {
                            try {
                                if (Files.size(f) >= TileCodec.TILE_BYTES && TileCodec.tileModeMatches(f, expectedMode)) {
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
        int cx = Math.floorDiv(player.getLocation().getBlockX(), TileCodec.TILE_PIXELS);
        int cz = Math.floorDiv(player.getLocation().getBlockZ(), TileCodec.TILE_PIXELS);
        long key = TileCodec.pack(cx, cz);
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
        int cx = Math.floorDiv(player.getLocation().getBlockX(), TileCodec.TILE_PIXELS);
        int cz = Math.floorDiv(player.getLocation().getBlockZ(), TileCodec.TILE_PIXELS);
        lastRenderChunk.put(player.getUniqueId(), TileCodec.pack(cx, cz));
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
        long key = TileCodec.pack(cx, cz);
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
                    TileCodec.writeTile(tileFile(w, cx, cz), tile, mode);
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
        if (mode == TileCodec.MODE_CAVE) {
            return renderCaveTile(snapshot);
        }
        return renderSurfaceTile(snapshot);
    }

    /** 表面模式（主世界）：每列取最高非空气块，waterDepth 记录水深用于水面明暗平滑。 */
    private TileData renderSurfaceTile(ChunkSnapshot snapshot) {
        int[] rgb = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS];
        int[] height = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS];
        int[] waterDepth = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS];
        for (int lz = 0; lz < TileCodec.TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TileCodec.TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                int idx = lz * TileCodec.TILE_PIXELS + lx;
                height[idx] = h;
                var bd = snapshot.getBlockData(lx, h, lz);
                var mapColor = bd.getMapColor();
                rgb[idx] = mapColor != null ? mapColor.asRGB() : TileCodec.FALLBACK_RGB;
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
        int[] rgb = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS];
        int[] height = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS];
        int[] waterDepth = new int[TileCodec.TILE_PIXELS * TileCodec.TILE_PIXELS]; // 下界无水，恒 0
        final int bottomY = 1; // 跳过底部基岩层（下界维度高度固定 0..127，y=0 为基岩）
        for (int lz = 0; lz < TileCodec.TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TileCodec.TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                int idx = lz * TileCodec.TILE_PIXELS + lx;
                final int fx = lx, fz = lz;
                int floorY = selectCaveFloorY(y -> snapshot.getBlockType(fx, y, fz).isAir(), h, bottomY);
                if (floorY < 0) {
                    floorY = h; // 无洞列（实心山体）：显示最高块（顶部基岩）
                }
                height[idx] = floorY;
                var bd = snapshot.getBlockData(lx, floorY, lz);
                var mapColor = bd.getMapColor();
                rgb[idx] = mapColor != null ? mapColor.asRGB() : TileCodec.FALLBACK_RGB;
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
        return world.getEnvironment() == World.Environment.NETHER ? TileCodec.MODE_CAVE : TileCodec.MODE_SURFACE;
    }

    /**
     * 按世界坐标窗口从磁盘拼出带坡度阴影的底图。
     * 委托 {@link TileCodec#buildTerrainImage}，瓦片读取通过回调从磁盘+缓存读。
     */
    public BufferedImage buildTerrainImage(World overworld, int winMinX, int winMinZ, int winW, int winH) {
        String w = overworld.getName();
        Set<Long> rendered = renderedByWorld.getOrDefault(w, Set.of());
        int expectedMode = renderModeFor(overworld);
        double shadeSensitivity = expectedMode == TileCodec.MODE_CAVE
                ? TileCodec.NETHER_SHADE_SENSITIVITY : TileCodec.SHADE_SENSITIVITY;

        return TileCodec.buildTerrainImage(winMinX, winMinZ, winW, winH, shadeSensitivity,
                (cx, cz) -> rendered.contains(TileCodec.pack(cx, cz))
                        ? TileCodec.readTile(tileFile(w, cx, cz), expectedMode)
                        : null);
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
        return tilesRoot.resolve(worldName).resolve(TileCodec.tileFileName(cx, cz));
    }
}
