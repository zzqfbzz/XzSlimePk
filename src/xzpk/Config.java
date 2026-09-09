package xzpk;

import java.util.Properties;

/**
 * 程序配置（纯内存模型，由 GUI 输入直接构建，不再读配置文件）。
 *
 * <p>GUI 路径要求 seed / 范围 / windowSize / topK 全部<b>显式填写</b>，
 * 不提供隐藏默认值；只有 skip、bandCols、线程数、原点等引擎参数保留合理内置值。</p>
 *
 * <p>{@link #fromProperties(Properties)} 仅为自检工具：把键值对解析成配置并换算单位。</p>
 */
public final class Config {

    /** 计算模式 */
    public enum Mode {
        /** 铺格：窗口从范围左上角起每隔 windowSize 铺一格(互不重叠)，允许向右/下溢出 */
        GRID,
        /** 滑动窗口：范围内每个区块都可作锚点(可 skip 抽样)，允许向右/下溢出 */
        SLIDING
    }

    /** 范围(及原点)坐标的输入单位 */
    public enum CoordUnit {
        /** 区块坐标 */
        CHUNK,
        /** 方块坐标（游戏 F3 显示的坐标），内部自动 ÷16 换算成区块 */
        BLOCK
    }

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

    private Config(Mode mode, CoordUnit coordUnit, long seed, long minChunkX, long maxChunkX,
                   long minChunkZ, long maxChunkZ, int windowSize, int skip,
                   int bandCols, int topK, long originX, long originZ,
                   int threads, String outFile, boolean showProgress) {
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
    }

    /**
     * 配置构造器。seed / 范围 / windowSize / topK 为 GUI 必填项（无内置默认）；
     * skip、bandCols、origin、threads、outFile、showProgress 等引擎参数有内置默认。
     */
    public static final class Builder {
        private Mode mode;
        private CoordUnit coordUnit = CoordUnit.CHUNK;
        private Long seed;
        private Long minChunkX;
        private Long maxChunkX;
        private Long minChunkZ;
        private Long maxChunkZ;
        private Integer windowSize;
        private int skip = 1;
        private int bandCols = 4096;
        private Integer topK;
        private long originX;
        private long originZ;
        private int threads;
        private String outFile = "";
        private boolean showProgress = true;

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** 范围/原点坐标的输入单位(仅记录用于提示; 换算由 GUI/解析端负责) */
        public Builder coordUnit(CoordUnit v) {
            this.coordUnit = v == null ? CoordUnit.CHUNK : v;
            return this;
        }

        public Builder seed(long v) {
            this.seed = v;
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

        /** 窗口边长(区块)，必填（GUI 不填会先报错） */
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

        /** 最多/最少各保留条数，必填 */
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

        /** 校验并构建（GUI 与自检共用同一套规则） */
        public Config build() {
            if (mode == null) {
                throw new IllegalArgumentException("请选择计算模式 (grid / sliding)");
            }
            if (seed == null) {
                throw new IllegalArgumentException("请填写世界种子");
            }
            if (minChunkX == null || maxChunkX == null || minChunkZ == null || maxChunkZ == null) {
                throw new IllegalArgumentException("请填写扫描范围(起点与终点 x/z)");
            }
            if (windowSize == null) {
                throw new IllegalArgumentException("请填写窗口大小(区块)");
            }
            if (topK == null) {
                throw new IllegalArgumentException("请填写输出条数 topK");
            }
            int ws = windowSize;

            if (minChunkX > maxChunkX) {
                throw new IllegalArgumentException(
                        "x 方向范围非法: 起点 x(" + minChunkX + ") 不能大于终点 x(" + maxChunkX + ")");
            }
            if (minChunkZ > maxChunkZ) {
                throw new IllegalArgumentException(
                        "z 方向范围非法: 起点 z(" + minChunkZ + ") 不能大于终点 z(" + maxChunkZ + ")");
            }
            if (ws < 1) {
                throw new IllegalArgumentException("windowSize 必须 >= 1 (当前: " + ws + ")");
            }
            if (mode == Mode.SLIDING) {
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
                    ws, skip, bandCols, topK, originX, originZ, threads, outFile, showProgress);
        }
    }

    // ---- 自检用：把键值对(来自测试字符串)解析成配置 ----

    /**
     * 从键值对解析配置(自检工具用)。与旧配置文件字段同名；coordUnit=block 时范围/原点自动 ÷16。
     * 缺省项: 模式=sliding, 单位=chunk, 范围=±600, 窗口=8, topK=100, 引擎参数取 Builder 默认。
     */
    static Config fromProperties(Properties p) {
        Builder b = new Builder();
        String modeRaw = p.getProperty("mode", "sliding").trim().toLowerCase();
        if (modeRaw.equals("grid")) {
            b.mode(Mode.GRID);
        } else if (modeRaw.equals("sliding")) {
            b.mode(Mode.SLIDING);
        } else {
            throw new IllegalArgumentException("配置项 mode 无效: \"" + modeRaw + "\" (可选: grid / sliding)");
        }
        String unitRaw = p.getProperty("coordUnit", "chunk").trim().toLowerCase();
        CoordUnit unit;
        if (unitRaw.equals("chunk")) {
            unit = CoordUnit.CHUNK;
        } else if (unitRaw.equals("block") || unitRaw.equals("blocks")) {
            unit = CoordUnit.BLOCK;
        } else {
            throw new IllegalArgumentException("配置项 coordUnit 无效: \"" + unitRaw + "\" (可选: chunk / block)");
        }
        b.coordUnit(unit);

        long rawMinX = parseLong(p, "minChunkX", -600);
        long rawMaxX = parseLong(p, "maxChunkX", 600);
        long rawMinZ = parseLong(p, "minChunkZ", -600);
        long rawMaxZ = parseLong(p, "maxChunkZ", 600);
        if (rawMinX > rawMaxX) {
            throw new IllegalArgumentException("范围非法: minChunkX(" + rawMinX + ") 不能大于 maxChunkX(" + rawMaxX + ")");
        }
        if (rawMinZ > rawMaxZ) {
            throw new IllegalArgumentException("范围非法: minChunkZ(" + rawMinZ + ") 不能大于 maxChunkZ(" + rawMaxZ + ")");
        }
        b.minChunkX(unit == CoordUnit.BLOCK ? chunkFromBlock(rawMinX) : rawMinX)
                .maxChunkX(unit == CoordUnit.BLOCK ? chunkFromBlock(rawMaxX) : rawMaxX)
                .minChunkZ(unit == CoordUnit.BLOCK ? chunkFromBlock(rawMinZ) : rawMinZ)
                .maxChunkZ(unit == CoordUnit.BLOCK ? chunkFromBlock(rawMaxZ) : rawMaxZ);
        b.seed(p.containsKey("seed") ? parseLong(p, "seed", 0) : 2950649267509295309L);
        b.windowSize((int) parseLong(p, "windowSize", 8));
        if (p.containsKey("skip")) {
            b.skip((int) parseLong(p, "skip", 1));
        }
        if (p.containsKey("bandCols")) {
            b.bandCols((int) parseLong(p, "bandCols", 4096));
        }
        b.topK((int) parseLong(p, "topK", 100));
        if (p.containsKey("originX")) {
            long raw = parseLong(p, "originX", 0);
            b.originX(unit == CoordUnit.BLOCK ? chunkFromBlock(raw) : raw);
        }
        if (p.containsKey("originZ")) {
            long raw = parseLong(p, "originZ", 0);
            b.originZ(unit == CoordUnit.BLOCK ? chunkFromBlock(raw) : raw);
        }
        if (p.containsKey("threads")) {
            b.threads((int) parseLong(p, "threads", 0));
        }
        if (p.containsKey("showProgress")) {
            b.showProgress(Boolean.parseBoolean(p.getProperty("showProgress").trim()));
        }
        if (p.containsKey("outFile")) {
            b.outFile(p.getProperty("outFile", "").trim());
        }
        return b.build();
    }

    private static long parseLong(Properties p, String key, long fallback) {
        if (!p.containsKey(key)) {
            return fallback;
        }
        try {
            return Long.parseLong(p.getProperty(key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置项 " + key + " 不是合法整数: \"" + p.getProperty(key) + "\"");
        }
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

    /** 计算后实际使用的线程数（0 = 自动 = CPU 核数） */
    public int effectiveThreads() {
        return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
