package com.mcqqbridge.report;

import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 玩家驱动的地形增量渲染器：玩家进服/跑图时后台按区块渲染地形，色块落盘，
 * 内存只保留"已渲染坐标集合"做标记位。日报时按轨迹范围从磁盘读色块拼成底图。
 * 复刻客户端小地图 mod 的"增量渲染+缓存"机制，但持久化到磁盘，重启不丢。
 *
 * 线程模型：PlayerMoveEvent/PlayerJoinEvent 在主线程做非阻塞判断与 getChunkAtAsync 发起；
 * 异步加载回调取 ChunkSnapshot 后提交到自有后台单线程渲染+写盘。
 */
public class TerrainTileCache implements Listener {

    private static final int TILE_PIXELS = 16;
    private static final int TILE_BYTES = TILE_PIXELS * TILE_PIXELS * Integer.BYTES;
    private static final int MAP_BG_RGB = new Color(24, 28, 36).getRGB();
    private static final int MAX_TERRAIN_SIDE = 4096;
    private static final double HEIGHT_MIN = -64.0;
    private static final double HEIGHT_MAX = 200.0;

    private static final Color C_WATER = new Color(45, 95, 180);
    private static final Color C_SAND = new Color(220, 205, 140);
    private static final Color C_DESERT = new Color(210, 180, 110);
    private static final Color C_SNOW = new Color(235, 240, 245);
    private static final Color C_SWAMP = new Color(70, 90, 55);
    private static final Color C_JUNGLE = new Color(50, 110, 45);
    private static final Color C_TAIGA = new Color(60, 95, 70);
    private static final Color C_FOREST = new Color(55, 120, 50);
    private static final Color C_SAVANNA = new Color(150, 150, 70);
    private static final Color C_PLAINS = new Color(110, 160, 70);
    private static final Color C_MUSHROOM = new Color(150, 90, 110);
    private static final Color C_NETHER = new Color(110, 45, 45);
    private static final Color C_END = new Color(120, 110, 80);
    private static final Color C_STONE = new Color(90, 90, 95);
    private static final Color C_DEFAULT = new Color(95, 130, 80);

    private final JavaPlugin plugin;
    private final Path tilesRoot;
    private final Logger logger;
    private final ExecutorService renderExecutor;
    private final Map<String, Set<Long>> renderedByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> pendingByWorld = new ConcurrentHashMap<>();

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
                            set.add(key);
                            total++;
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
        ensureRendered(player.getWorld(), cx, cz);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int cx = Math.floorDiv(player.getLocation().getBlockX(), TILE_PIXELS);
        int cz = Math.floorDiv(player.getLocation().getBlockZ(), TILE_PIXELS);
        World world = player.getWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
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
                    int[] pixels = renderChunk(snapshot);
                    writeTile(w, cx, cz, pixels);
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

    private int[] renderChunk(ChunkSnapshot snapshot) {
        int[] pixels = new int[TILE_PIXELS * TILE_PIXELS];
        for (int lz = 0; lz < TILE_PIXELS; lz++) {
            for (int lx = 0; lx < TILE_PIXELS; lx++) {
                int h = snapshot.getHighestBlockYAt(lx, lz);
                Biome biome = snapshot.getBiome(lx, h, lz);
                Color base = biomeColor(biome);
                pixels[lz * TILE_PIXELS + lx] = isWater(biome) ? base.getRGB() : shade(base, h).getRGB();
            }
        }
        return pixels;
    }

    private void writeTile(String worldName, int cx, int cz, int[] pixels) throws IOException {
        Path file = tileFile(worldName, cx, cz);
        Files.createDirectories(file.getParent());
        ByteBuffer bb = ByteBuffer.allocate(TILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : pixels) {
            bb.putInt(v);
        }
        Files.write(file, bb.array());
    }

    private int[] readTile(String worldName, int cx, int cz) {
        Path file = tileFile(worldName, cx, cz);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < TILE_BYTES) {
                return null;
            }
            int[] pixels = new int[TILE_PIXELS * TILE_PIXELS];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
            return pixels;
        } catch (IOException e) {
            logger.warning("[Terrain] read tile failed for " + worldName + " " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 按世界坐标窗口从磁盘拼出底图。win 边长超过上限返回 null（降级，防超大图）。
     * 缺失/未渲染区块填深色。每区块只读一次文件（局部缓存当前区块色块）。
     */
    public BufferedImage buildTerrainImage(World overworld, int winMinX, int winMinZ, int winW, int winH) {
        if (winW > MAX_TERRAIN_SIDE || winH > MAX_TERRAIN_SIDE || winW <= 0 || winH <= 0) {
            return null;
        }
        String w = overworld.getName();
        Set<Long> rendered = renderedByWorld.getOrDefault(w, Set.of());

        BufferedImage img = new BufferedImage(winW, winH, BufferedImage.TYPE_INT_RGB);
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        int[] cur = null;

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
                int lx = Math.floorMod(worldX, TILE_PIXELS);
                int rgb = (cur != null) ? cur[lz * TILE_PIXELS + lx] : MAP_BG_RGB;
                img.setRGB(i, j, rgb);
            }
        }
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

    private static boolean isWater(Biome biome) {
        String k = biome.getKey().getKey();
        return k.contains("ocean") || k.contains("river");
    }

    private static Color biomeColor(Biome biome) {
        String k = biome.getKey().getKey();
        if (k.contains("ocean") || k.contains("river")) return C_WATER;
        if (k.contains("beach")) return C_SAND;
        if (k.contains("desert") || k.contains("badland")) return C_DESERT;
        if (k.contains("snow") || k.contains("ice") || k.contains("frozen")) return C_SNOW;
        if (k.contains("swamp") || k.contains("mangrove")) return C_SWAMP;
        if (k.contains("jungle")) return C_JUNGLE;
        if (k.contains("taiga") || k.contains("grove")) return C_TAIGA;
        if (k.contains("forest") || k.contains("birch")) return C_FOREST;
        if (k.contains("savanna")) return C_SAVANNA;
        if (k.contains("plains") || k.contains("meadow")) return C_PLAINS;
        if (k.contains("mushroom")) return C_MUSHROOM;
        if (k.contains("nether") || k.contains("warped") || k.contains("crimson") || k.contains("basalt") || k.contains("soul")) return C_NETHER;
        if (k.contains("end") || k.contains("void")) return C_END;
        if (k.contains("cave") || k.contains("deep_dark") || k.contains("dripstone") || k.contains("lush")) return C_STONE;
        return C_DEFAULT;
    }

    private static Color shade(Color c, int h) {
        double t = (h - HEIGHT_MIN) / (HEIGHT_MAX - HEIGHT_MIN);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double bright = 0.5 + 0.6 * t; // 0.5 .. 1.1
        return new Color(
                clamp((int) (c.getRed() * bright)),
                clamp((int) (c.getGreen() * bright)),
                clamp((int) (c.getBlue() * bright)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
