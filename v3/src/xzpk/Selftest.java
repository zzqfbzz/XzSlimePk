package xzpk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 无框架自检程序：用“暴力直接数”的方式交叉验证 grid / sliding 两种扫描器，
 * 并验证 V3 判定公式与旧版一致、结果确定性。
 *
 * <p>运行：{@code java xzpk.Selftest}</p>
 */
public final class Selftest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("XzPk V3 自检开始...");
        try {
            testFormulaParity();
            testCoordUnit();
            testGridVsBrute();
            testSlidingVsBrute();
            testOverflow();
            testDeterminism();
        } catch (Throwable t) {
            System.err.println("自检失败: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println("全部自检通过, 共 " + checks + " 项检查 OK");
    }

    // ================ 公式一致性 ================

    private static void testFormulaParity() {
        Random rnd = new Random(42);
        // 与 Minecraft Wiki 官方写法一致(大坐标也要一致, 含 32 位溢出)
        for (int i = 0; i < 40_000; i++) {
            int cx = rnd.nextInt(4_000_001) - 2_000_000;
            int cz = rnd.nextInt(4_000_001) - 2_000_000;
            check(SlimeChunk.isSlimeChunk(123456789L, cx, cz) == wikiSnippet(123456789L, cx, cz),
                    "V3 与 Wiki 官方写法不一致 区块(" + cx + "," + cz + ")");
        }
        // 小坐标(±20 内, 各乘项不溢出)时, 两种旧版写法也应与官方一致
        for (int cx = -20; cx <= 20; cx++) {
            for (int cz = -20; cz <= 20; cz++) {
                boolean v3 = SlimeChunk.isSlimeChunk(12345L, cx, cz);
                check(v3 == SlimeChunk.legacyLongMath(12345L, cx, cz),
                        "小坐标与旧版 slimepk(全程long)不一致 区块(" + cx + "," + cz + ")");
                check(v3 == SlimeChunk.legacySkimepk2(12345L, cx, cz),
                        "小坐标与旧版 skimepk2 不一致 区块(" + cx + "," + cz + ")");
            }
        }
        // 回归: 用户报告窗口 种子 -2870327070709450083, NW 方块(93696,-192) → 区块 X5856~5863 Z-12~-5, 官方应为 11
        int cnt = 0;
        for (int x = 5856; x <= 5863; x++) {
            for (int z = -12; z <= -5; z++) {
                if (SlimeChunk.isSlimeChunk(-2870327070709450083L, x, z)) {
                    cnt++;
                }
            }
        }
        check(cnt == 11, "用户窗口官方语义应为 11 个史莱姆区块, 实际 " + cnt);
        System.out.println("[OK] 公式与 Wiki 官方写法一致(含回归窗口=11)");
    }

    /** Minecraft Wiki 上的官方判定代码(逐字抄录, 用于对照) */
    private static boolean wikiSnippet(long seed, int chunkX, int chunkZ) {
        Random rnd = new Random(
                (
                        seed
                                + (int) (chunkX * chunkX * 0x4c1906)
                                + (int) (chunkX * 0x5ac0db)
                                + (int) (chunkZ * chunkZ) * 0x4307a7L
                                + (int) (chunkZ * 0x5f24f)
                ) ^ 0x3ad8025fL
        );
        return rnd.nextInt(10) == 0;
    }

    // ================ 坐标单位换算 ================

    private static void testCoordUnit() throws IOException {
        // 方块 → 区块换算边界(负数向下取整)
        long[][] cases = {
                {-1, -1}, {0, 0}, {15, 0}, {16, 1}, {-16, -1}, {-100, -7}, {100, 6}, {-9600, -600}
        };
        for (long[] c : cases) {
            check(Config.chunkFromBlock(c[0]) == c[1],
                    "方块→区块换算错误: " + c[0] + " 应为 " + c[1] + ", 得到 " + Config.chunkFromBlock(c[0]));
        }
        // 配置文件中 coordUnit=block 时, 范围自动换算成区块
        try {
            Config cfg = cfg("mode=sliding",
                    "coordUnit=block",
                    "minChunkX=-100", "maxChunkX=100",
                    "minChunkZ=-100", "maxChunkZ=100",
                    "windowSize=8", "skip=1", "topK=10",
                    "showProgress=false");
            check(cfg.coordUnit() == Config.CoordUnit.BLOCK, "coordUnit 未生效");
            check(cfg.minChunkX() == -7 && cfg.maxChunkX() == 6, "block X 范围换算错误: "
                    + cfg.minChunkX() + "~" + cfg.maxChunkX());
            check(cfg.minChunkZ() == -7 && cfg.maxChunkZ() == 6, "block Z 范围换算错误: "
                    + cfg.minChunkZ() + "~" + cfg.maxChunkZ());
            // 该换算结果可直接滑动扫描(8×8 能放下)
            ScanResult r = SlidingScanner.scan(cfg);
            check(r.totalWindows > 0, "block 范围扫描无窗口");
        } catch (IllegalArgumentException e) {
            throw new AssertionError("coordUnit=block 配置失败: " + e.getMessage());
        }
        System.out.println("[OK] 坐标单位换算正确");
    }

    // ================ 暴力对照 ================

    private static void testGridVsBrute() throws IOException {
        // 范围含负数且不整除, 考验从角点铺格与越界处理
        Config cfg = cfg("mode=grid", "seed=999",
                "minChunkX=-13", "maxChunkX=12",
                "minChunkZ=-10", "maxChunkZ=15",
                "windowSize=4", "topK=10000", "threads=3",
                "originX=0", "originZ=0", "showProgress=false");
        ScanResult scan = GridScanner.scan(cfg);

        // 暴力对照: 窗口从范围左上角(最小坐标)起, 每隔 windowSize 铺一块, 锚点在范围内即可(可溢出)
        List<ResultItem> brute = new ArrayList<>();
        long hits = 0;
        boolean firstTileFound = false;
        for (long sx = -13; sx <= 12; sx += 4) {
            for (long sz = -10; sz <= 15; sz += 4) {
                int c = countBlock(999L, sx, sz, 4);
                brute.add(ResultItem.of(c, (int) sx, (int) sz, 0, 0));
                hits += c;
                if (sx == -13 && sz == -10) {
                    firstTileFound = true;
                }
            }
        }
        check(firstTileFound, "grid 暴力对照里没有角点第一块");
        check(scan.totalWindows == brute.size(), "grid 窗口总数不符: " + scan.totalWindows + " vs " + brute.size());
        check(scan.slimeHits == hits, "grid 命中总数不符: " + scan.slimeHits + " vs " + hits);
        assertSameSets("grid", scan, brute, brute);
        // 第一块必须是范围角点 (-13,-10) 那块
        check(containsChunk(scan.mostRaw, -13, -10) || containsChunk(scan.leastRaw, -13, -10),
                "grid 结果里没有从角点开始的第一个窗口");
        System.out.println("[OK] grid 与暴力法一致(窗口 " + brute.size() + " 个, 第一块=角点)");
    }

    private static boolean containsChunk(List<ResultItem> items, int cx, int cz) {
        for (ResultItem item : items) {
            if (item.chunkX == cx && item.chunkZ == cz) {
                return true;
            }
        }
        return false;
    }

    private static void testSlidingVsBrute() throws IOException {
        // 用例A: 覆盖 8x8 窗口, 含负坐标, 多分片
        slidingCase("slidingA", 12345L, -5, 4, -5, 4, 3, 1, 3);
        // 用例B: skip=2
        slidingCase("slidingB", 777L, -5, 4, -5, 4, 3, 2, 2);
        // 用例C: 非对称范围
        slidingCase("slidingC", -2024L, -7, 8, 2, 11, 4, 1, 5);
        // 用例D: 单列候选(宽 == 窗口)
        slidingCase("slidingD", 555L, 3, 5, 0, 9, 3, 1, 1);
        System.out.println("[OK] sliding 与暴力法一致(4 组)");
    }

    private static void slidingCase(String name, long seed, int minX, int maxX, int minZ, int maxZ,
                                    int w, int skip, int bandCols) throws IOException {
        Config cfg = cfg("mode=sliding",
                "seed=" + seed,
                "minChunkX=" + minX, "maxChunkX=" + maxX,
                "minChunkZ=" + minZ, "maxChunkZ=" + maxZ,
                "windowSize=" + w, "skip=" + skip, "bandCols=" + bandCols,
                "topK=100000", "threads=3",
                "originX=0", "originZ=0", "showProgress=false");
        ScanResult scan = SlidingScanner.scan(cfg);

        List<ResultItem> brute = new ArrayList<>();
        long hits = 0;
        long sxHi = maxX; // 溢出语义: 锚点区块在范围内即可
        long szHi = maxZ;
        for (long sx = minX; sx <= sxHi; sx += skip) {
            for (long sz = minZ; sz <= szHi; sz += skip) {
                int c = countBlock(seed, sx, sz, w);
                brute.add(ResultItem.of(c, (int) sx, (int) sz, 0, 0));
                hits += c;
            }
        }
        check(scan.totalWindows == brute.size(),
                name + " 窗口总数不符: " + scan.totalWindows + " vs " + brute.size());
        check(scan.slimeHits == hits, name + " 命中总数不符: " + scan.slimeHits + " vs " + hits);
        assertSameSets(name, scan, brute, brute);
    }

    // ================ 溢出语义 ================

    private static void testOverflow() throws IOException {
        // 方块 0~100 → 区块 0~6: sliding 每个区块都可作锚点, 窗口 8x8 向右/下溢出到范围外也照常统计
        Config cfg = cfg("mode=sliding", "coordUnit=block",
                "minChunkX=0", "maxChunkX=100",
                "minChunkZ=0", "maxChunkZ=100",
                "windowSize=8", "skip=1", "topK=100000", "threads=3",
                "showProgress=false");
        ScanResult scan = SlidingScanner.scan(cfg);
        long seed = cfg.seed();

        List<ResultItem> brute = new ArrayList<>();
        long hits = 0;
        for (long sx = 0; sx <= 6; sx++) {
            for (long sz = 0; sz <= 6; sz++) {
                int c = countBlock(seed, sx, sz, 8); // 允许数到锚点+7 的区块
                brute.add(ResultItem.of(c, (int) sx, (int) sz, 0, 0));
                hits += c;
            }
        }
        check(scan.totalWindows == 49, "溢出: 窗口总数应为 49, 实际 " + scan.totalWindows);
        check(scan.slimeHits == hits, "溢出: 命中总数不符");
        assertSameSets("溢出", scan, brute, brute);
        System.out.println("[OK] 溢出语义正确(方块0~100 → 7×7=49 个锚点窗口)");
    }

    // ================ 确定性 ================

    private static void testDeterminism() throws IOException {
        Config cfg = cfg("mode=sliding", "seed=12345",
                "minChunkX=-5", "maxChunkX=4", "minChunkZ=-5", "maxChunkZ=4",
                "windowSize=3", "skip=1", "bandCols=2",
                "topK=50", "threads=4", "originX=0", "originZ=0", "showProgress=false");
        ScanResult a = SlidingScanner.scan(cfg);
        ScanResult b = SlidingScanner.scan(cfg);
        assertSameSets("确定性", a, new ArrayList<>(a.mostRaw), new ArrayList<>(a.leastRaw));
        // 直接比较两次扫描的内容
        List<ResultItem> aMost = new ArrayList<>(a.mostRaw);
        aMost.sort(ResultItem.mostBestFirst());
        List<ResultItem> bMost = new ArrayList<>(b.mostRaw);
        bMost.sort(ResultItem.mostBestFirst());
        check(aMost.size() == bMost.size(), "两次运行 top 数量不同");
        for (int i = 0; i < aMost.size(); i++) {
            eqItem("确定性", aMost.get(i), bMost.get(i));
        }
        System.out.println("[OK] 结果确定(两次运行一致)");
    }

    // ================ 工具 ================

    /** 验证扫描器收集的结果集合与暴力集合完全一致(都按各自比较器排序后逐条比较) */
    private static void assertSameSets(String label, ScanResult scan,
                                       List<ResultItem> bruteMost, List<ResultItem> bruteLeast) {
        List<ResultItem> aMost = new ArrayList<>(scan.mostRaw);
        aMost.sort(ResultItem.mostBestFirst());
        List<ResultItem> bMost = new ArrayList<>(bruteMost);
        bMost.sort(ResultItem.mostBestFirst());
        check(aMost.size() == bMost.size(),
                label + ": 最多侧条数不符 " + aMost.size() + " vs " + bMost.size());
        for (int i = 0; i < aMost.size(); i++) {
            eqItem(label + "/most", aMost.get(i), bMost.get(i));
        }

        List<ResultItem> aLeast = new ArrayList<>(scan.leastRaw);
        aLeast.sort(ResultItem.leastBestFirst());
        List<ResultItem> bLeast = new ArrayList<>(bruteLeast);
        bLeast.sort(ResultItem.leastBestFirst());
        check(aLeast.size() == bLeast.size(),
                label + ": 最少侧条数不符 " + aLeast.size() + " vs " + bLeast.size());
        for (int i = 0; i < aLeast.size(); i++) {
            eqItem(label + "/least", aLeast.get(i), bLeast.get(i));
        }
    }

    private static void eqItem(String label, ResultItem x, ResultItem y) {
        check(x.count == y.count && x.chunkX == y.chunkX && x.chunkZ == y.chunkZ,
                label + ": 结果不同 " + x + " vs " + y);
    }

    private static int countBlock(long seed, long sx, long sz, int w) {
        int c = 0;
        for (long x = sx; x < sx + w; x++) {
            for (long z = sz; z < sz + w; z++) {
                if (SlimeChunk.isSlimeChunk(seed, (int) x, (int) z)) {
                    c++;
                }
            }
        }
        return c;
    }

    /** 写临时配置文件并加载 */
    private static Config cfg(String... lines) throws IOException {
        Path f = Files.createTempFile("xzpk-v3-selftest-", ".properties");
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
            return Config.load(f);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private Selftest() {
    }
}
