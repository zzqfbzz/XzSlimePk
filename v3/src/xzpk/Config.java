package xzpk;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 程序配置。
 *
 * <p>两种构造方式：
 * <ul>
 *   <li>{@link #load(Path)}：从 UTF-8 properties 文件读取（Main 命令行用）；</li>
 *   <li>{@link Builder}：代码内直接构建（GUI 用），缺省项使用内置默认值。</li>
 * </ul>
 * 两种方式共用同一套校验规则。</p>
 */
public final class Config {

    /** 计算模式 */
    public enum Mode {
        /** 铺格：从范围左上角(最小坐标)起按窗口大小铺互不重叠的完整格子 */
        GRID,
        /** 滑动窗口：窗口起点逐区块（或按 skip）滑动，可覆盖任意位置（新版 skimepk2 思路） */
        SLIDING
    }

    /** 范围(及原点)坐标的输入单位 */
    public enum CoordUnit {
        /** 区块坐标 */
        CHUNK,
        /** 方块坐标（游戏 F3 显示的坐标），内部自动 ÷16 换算成区块 */
        BLOCK
    }

    /** 与旧版一致的默认世界种子 */
    public static final long DEFAULT_SEED = 2950649267509295309L;

    /** 方块坐标 → 所在区块坐标（向下取整，负数也正确） */
    public static long chunkFromBlock(long block) {
        return Math.floorDiv(block, 16L);
    }

    private final Mode mode;
    private final CoordUnit coordUnit;
    private final long seed;
    private final long minChunkX;
    private final long maxChunkX;
    private final long minChunkZ;
    private final long maxChunkZ;
    private final int windowSize;
    private final int skip;
    private final int bandCols;
    private final int topK;
    private final long originX;
    private final long originZ;
    private final int threads;
    private final String outFile;
    private final boolean showProgress;
    private final Path source;

    private Config(Mode mode, CoordUnit coordUnit, long seed, long minChunkX, long maxChunkX,
                   long minChunkZ, long maxChunkZ, int windowSize, int skip,
                   int bandCols, int topK, long originX, long originZ,
                   int threads, String outFile, boolean showProgress, Path source) {
        this.mode = mode;
        this.coordUnit = coordUnit;
        this.seed = seed;
        this.minChunkX = minChunkX;
        this.maxChunkX = maxChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkZ = maxChunkZ;
        this.windowSize = windowSize;
        this.skip = skip;
        this.bandCols = bandCols;
        this.topK = topK;
        this.originX = originX;
        this.originZ = originZ;
        this.threads = threads;
        this.outFile = outFile;
        this.showProgress = showProgress;
        this.source = source;
    }

    /**
     * 从指定路径读取配置。文件不存在时抛出带提示的异常。
     *
     * @param file 配置文件路径
     * @return 校验通过的配置
     */
    public static Config load(Path file) {
        if (file == null || !Files.exists(file)) {
            throw new IllegalArgumentException("找不到配置文件: " + file
                    + "\n  请把 config.properties 放在运行目录, 或使用: java xzpk.Main <配置文件>");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取配置文件失败: " + file + " (" + e.getMessage() + ")", e);
        }

        Builder b = new Builder().source(file);

        String modeRaw = p.getProperty("mode", "sliding").trim().toLowerCase();
        if (modeRaw.equals("grid")) {
            b.mode(Mode.GRID);
        } else if (modeRaw.equals("sliding")) {
            b.mode(Mode.SLIDING);
        } else {
            throw new IllegalArgumentException("配置项 mode 无效: \"" + modeRaw + "\" (可选: grid / sliding)");
        }

        // ---- 世界种子 ----
        if (p.containsKey("seed")) {
            b.seed(parseLong("seed", p.getProperty("seed").trim()));
        }

        // ---- 坐标输入单位 (默认: 区块) ----
        String unitRaw = p.getProperty("coordUnit", "chunk").trim().toLowerCase();
        CoordUnit unit;
        if (unitRaw.equals("chunk")) {
            unit = CoordUnit.CHUNK;
        } else if (unitRaw.equals("block") || unitRaw.equals("blocks")) {
            unit = CoordUnit.BLOCK;
        } else {
            throw new IllegalArgumentException(
                    "配置项 coordUnit 无效: \"" + unitRaw + "\" (可选: chunk / block)");
        }
        b.coordUnit(unit);

        // ---- 范围(按所选单位解析, block 时 ÷16 换算成区块) ----
        long rawMinX = p.containsKey("minChunkX") ? parseInt("minChunkX", p.getProperty("minChunkX").trim()) : -600;
        long rawMaxX = p.containsKey("maxChunkX") ? parseInt("maxChunkX", p.getProperty("maxChunkX").trim()) : 600;
        long rawMinZ = p.containsKey("minChunkZ") ? parseInt("minChunkZ", p.getProperty("minChunkZ").trim()) : -600;
        long rawMaxZ = p.containsKey("maxChunkZ") ? parseInt("maxChunkZ", p.getProperty("maxChunkZ").trim()) : 600;
        if (rawMinX > rawMaxX) {
            throw new IllegalArgumentException("范围非法: minChunkX(" + rawMinX + ") 不能大于 maxChunkX(" + rawMaxX + ")");
        }
        if (rawMinZ > rawMaxZ) {
            throw new IllegalArgumentException("范围非法: minChunkZ(" + rawMinZ + ") 不能大于 maxChunkZ(" + rawMaxZ + ")");
        }
        long minX = unit == CoordUnit.BLOCK ? chunkFromBlock(rawMinX) : rawMinX;
        long maxX = unit == CoordUnit.BLOCK ? chunkFromBlock(rawMaxX) : rawMaxX;
        long minZ = unit == CoordUnit.BLOCK ? chunkFromBlock(rawMinZ) : rawMinZ;
        long maxZ = unit == CoordUnit.BLOCK ? chunkFromBlock(rawMaxZ) : rawMaxZ;
        b.minChunkX(minX).maxChunkX(maxX).minChunkZ(minZ).maxChunkZ(maxZ);
        // windowSize 缺省: 8 (grid/sliding 一致)
        if (p.containsKey("windowSize")) {
            b.windowSize(parseInt("windowSize", p.getProperty("windowSize").trim()));
        }
        if (p.containsKey("skip")) {
            b.skip(parseInt("skip", p.getProperty("skip").trim()));
        }
        if (p.containsKey("bandCols")) {
            b.bandCols(parseInt("bandCols", p.getProperty("bandCols").trim()));
        }
        if (p.containsKey("topK")) {
            b.topK(parseInt("topK", p.getProperty("topK").trim()));
        }
        if (p.containsKey("originX")) {
            long raw = parseInt("originX", p.getProperty("originX").trim());
            b.originX(unit == CoordUnit.BLOCK ? chunkFromBlock(raw) : raw);
        }
        if (p.containsKey("originZ")) {
            long raw = parseInt("originZ", p.getProperty("originZ").trim());
            b.originZ(unit == CoordUnit.BLOCK ? chunkFromBlock(raw) : raw);
        }
        if (p.containsKey("threads")) {
            b.threads(parseInt("threads", p.getProperty("threads").trim()));
        }
        if (p.containsKey("showProgress")) {
            b.showProgress(Boolean.parseBoolean(p.getProperty("showProgress").trim()));
        }
        b.outFile(p.getProperty("outFile", "").trim());
        return b.build();
    }

    private static int parseInt(String key, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 不是合法整数: \"" + raw + "\"");
        }
    }

    private static long parseLong(String key, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 不是合法长整数: \"" + raw + "\"");
        }
    }

    /**
     * 配置构造器。未显式设置的项使用内置默认值；
     * windowSize 不设置时默认 8（grid / sliding 一致）。
     */
    public static final class Builder {
        private Mode mode;
        private CoordUnit coordUnit = CoordUnit.CHUNK;
        private long seed = DEFAULT_SEED;
        private long minChunkX = -600;
        private long maxChunkX = 600;
        private long minChunkZ = -600;
        private long maxChunkZ = 600;
        private int windowSize;
        private int skip = 1;
        private int bandCols = 4096;
        private int topK = 100;
        private long originX;
        private long originZ;
        private int threads;
        private String outFile = "";
        private boolean showProgress = true;
        private Path source;

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** 范围/原点坐标的输入单位(默认区块; 方块时由调用方自行换算, 此字段仅记录单位) */
        public Builder coordUnit(CoordUnit v) {
            this.coordUnit = v == null ? CoordUnit.CHUNK : v;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder minChunkX(long v) {
            this.minChunkX = v;
            return this;
        }

        public Builder maxChunkX(long v) {
            this.maxChunkX = v;
            return this;
        }

        public Builder minChunkZ(long v) {
            this.minChunkZ = v;
            return this;
        }

        public Builder maxChunkZ(long v) {
            this.maxChunkZ = v;
            return this;
        }

        /** 窗口边长(区块)；0 或未设置 = 按模式默认 */
        public Builder windowSize(int v) {
            this.windowSize = v;
            return this;
        }

        public Builder skip(int v) {
            this.skip = v;
            return this;
        }

        public Builder bandCols(int v) {
            this.bandCols = v;
            return this;
        }

        public Builder topK(int v) {
            this.topK = v;
            return this;
        }

        public Builder originX(long v) {
            this.originX = v;
            return this;
        }

        public Builder originZ(long v) {
            this.originZ = v;
            return this;
        }

        /** 0 = 自动(CPU核数) */
        public Builder threads(int v) {
            this.threads = v;
            return this;
        }

        public Builder outFile(String v) {
            this.outFile = v == null ? "" : v;
            return this;
        }

        public Builder showProgress(boolean v) {
            this.showProgress = v;
            return this;
        }

        public Builder source(Path v) {
            this.source = v;
            return this;
        }

        /** 校验并构建（与文件读取共用同一套规则） */
        public Config build() {
            if (mode == null) {
                throw new IllegalArgumentException("未指定计算模式 (mode = grid / sliding)");
            }
            int ws = windowSize > 0 ? windowSize : 8;

            if (minChunkX > maxChunkX) {
                throw new IllegalArgumentException(
                        "范围非法: minChunkX(" + minChunkX + ") 不能大于 maxChunkX(" + maxChunkX + ")");
            }
            if (minChunkZ > maxChunkZ) {
                throw new IllegalArgumentException(
                        "范围非法: minChunkZ(" + minChunkZ + ") 不能大于 maxChunkZ(" + maxChunkZ + ")");
            }
            if (ws < 1) {
                throw new IllegalArgumentException("windowSize 必须 >= 1 (当前: " + ws + ")");
            }
            if (mode == Mode.SLIDING) {
                long width = maxChunkX - minChunkX + 1;
                long height = maxChunkZ - minChunkZ + 1;
                if (ws > width || ws > height) {
                    throw new IllegalArgumentException("窗口 " + ws + "x" + ws
                            + " 大于扫描范围 " + width + "x" + height + "，放不下任何窗口");
                }
                if (skip < 1) {
                    throw new IllegalArgumentException("skip 必须 >= 1 (当前: " + skip + ")");
                }
                if (bandCols < 1) {
                    throw new IllegalArgumentException("bandCols 必须 >= 1 (当前: " + bandCols + ")");
                }
            }
            if (topK < 1) {
                throw new IllegalArgumentException("topK 必须 >= 1 (当前: " + topK + ")");
            }
            if (threads < 0) {
                throw new IllegalArgumentException(
                        "threads 不能为负数 (0 表示自动 = CPU 核数, 当前: " + threads + ")");
            }
            if (ws > 1_000_000) {
                throw new IllegalArgumentException("windowSize 过大: " + ws);
            }
            final long MAX_CHUNK = 2_000_000_000L;
            if (Math.abs(minChunkX) > MAX_CHUNK || Math.abs(maxChunkX) > MAX_CHUNK
                    || Math.abs(minChunkZ) > MAX_CHUNK || Math.abs(maxChunkZ) > MAX_CHUNK
                    || Math.abs(originX) > MAX_CHUNK || Math.abs(originZ) > MAX_CHUNK) {
                throw new IllegalArgumentException(
                        "区块坐标超出支持范围(±" + MAX_CHUNK + "): 请检查输入的范围/原点");
            }

            return new Config(mode, coordUnit, seed, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    ws, skip, bandCols, topK, originX, originZ, threads, outFile,
                    showProgress, source);
        }
    }

    /** 计算后实际使用的线程数（0 = 自动 = CPU 核数） */
    public int effectiveThreads() {
        return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public void printSummary(PrintStream out) {
        String modeName = mode == Mode.GRID ? "grid(从角点铺格)" : "sliding(滑动窗口)";
        out.println("配置文件: " + (source != null ? source.toAbsolutePath() : "(GUI/内存配置)"));
        out.println("模式: " + modeName);
        out.println("世界种子: " + seed);
        out.println("窗口大小: " + windowSize + "x" + windowSize + " 区块");
        out.println("坐标输入单位: " + (coordUnit == CoordUnit.BLOCK ? "block(方块坐标, 已自动÷16换算)" : "chunk(区块坐标)"));
        out.println("扫描范围(区块,含端点): X[" + minChunkX + ", " + maxChunkX
                + "] Z[" + minChunkZ + ", " + maxChunkZ + "]");
        out.println("扫描范围(世界方块): X[" + (minChunkX * 16L) + ", " + ((maxChunkX + 1) * 16L - 1)
                + "] Z[" + (minChunkZ * 16L) + ", " + ((maxChunkZ + 1) * 16L - 1) + "]");
        if (mode == Mode.SLIDING) {
            out.println("滑动步长 skip: " + skip + (skip == 1 ? "(全覆盖)" : ""));
            out.println("X 方向分片列数 bandCols: " + bandCols);
            out.println("原点(用于距离排序): 区块(" + originX + ", " + originZ + ")");
        } else {
            out.println("对齐方式: 窗口从范围左上角起按 windowSize 铺互不重叠的格子, 只统计完整落在范围内的整块");
        }
        out.println("输出条数 topK: " + topK + " (最多/最少各 " + topK + " 条)");
        out.println("线程数: " + effectiveThreads());
        out.println("进度条: " + showProgress);
        out.println("导出文件: " + (outFile.isEmpty() ? "(无, 仅控制台)" : outFile));
    }

    // ---- getters ----
    public Mode mode() { return mode; }
    public CoordUnit coordUnit() { return coordUnit; }
    public long seed() { return seed; }
    public long minChunkX() { return minChunkX; }
    public long maxChunkX() { return maxChunkX; }
    public long minChunkZ() { return minChunkZ; }
    public long maxChunkZ() { return maxChunkZ; }
    public int windowSize() { return windowSize; }
    public int skip() { return skip; }
    public int bandCols() { return bandCols; }
    public int topK() { return topK; }
    public long originX() { return originX; }
    public long originZ() { return originZ; }
    public int threads() { return threads; }
    public String outFile() { return outFile; }
    public boolean showProgress() { return showProgress; }
    public Path source() { return source; }
}
