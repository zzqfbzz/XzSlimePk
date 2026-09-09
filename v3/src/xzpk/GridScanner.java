package xzpk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对齐网格扫描器（对应旧版 slimepk 思路）。
 *
 * <p>把扫描范围按 windowSize 对齐成互不重叠的方块：方块起点 X/Z 均为
 * windowSize 的整数倍，且要求方块<b>完全落在</b>用户给定的范围内
 * （含端点的区块坐标），超出范围的半截方块不计入。</p>
 *
 * <p>并行方式：把全部方块按批次动态分发给线程池里的 worker，
 * 每个 worker 用本地 Top-K 收集器，结束时再合并，降低锁竞争。</p>
 */
public final class GridScanner {

    private GridScanner() {
    }

    /**
     * 执行 grid 模式扫描。
     *
     * @param cfg 校验通过的配置（mode == GRID）
     * @return 扫描结果
     */
    public static ScanResult scan(Config cfg) {
        long w = cfg.windowSize();
        long firstX = ceilMul(cfg.minChunkX(), w);
        long lastX = floorMul(cfg.maxChunkX() - w + 1L, w);
        long firstZ = ceilMul(cfg.minChunkZ(), w);
        long lastZ = floorMul(cfg.maxChunkZ() - w + 1L, w);

        if (firstX > lastX || firstZ > lastZ) {
            throw new IllegalArgumentException("范围内放不下任何完整的 " + w + "x" + w
                    + " 对齐块。grid 的窗口起点必须在区块坐标的 " + w + " 整数倍上、且整块落在范围内"
                    + "(换算后范围每个方向至少要有 " + (2 * w - 1) + " 个区块宽并包含一个对齐起点)。"
                    + "若只想在范围内\"任意摆放\"找最优位置, 请改用 sliding 模式。");
        }
        long nX = (lastX - firstX) / w + 1L;
        long nZ = (lastZ - firstZ) / w + 1L;
        long total = Math.multiplyExact(nX, nZ);

        final int threads = cfg.effectiveThreads();
        final long originX = cfg.originX();
        final long originZ = cfg.originZ();
        final int topK = cfg.topK();
        final long seed = cfg.seed();
        final int window = (int) w;

        TopKCollector globalMost = new TopKCollector(topK, TopKCollector.Side.MOST, originX, originZ);
        TopKCollector globalLeast = new TopKCollector(topK, TopKCollector.Side.LEAST, originX, originZ);
        AtomicLong globalHits = new AtomicLong();
        Object mergeLock = new Object();

        ProgressBar bar = cfg.showProgress() ? new ProgressBar("扫描(grid)", total) : null;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong cursor = new AtomicLong();
        long batch = Math.max(1, Math.min(8192, Math.max(1, total / (threads * 32L))));

        long startNanos = System.nanoTime();
        if (bar != null) {
            bar.start();
        }

        Runnable worker = () -> {
            TopKCollector localMost = new TopKCollector(topK, TopKCollector.Side.MOST, originX, originZ);
            TopKCollector localLeast = new TopKCollector(topK, TopKCollector.Side.LEAST, originX, originZ);
            long localHits = 0L;
            while (true) {
                long base = cursor.getAndAdd(batch);
                if (base >= total) {
                    break;
                }
                long end = Math.min(base + batch, total);
                for (long idx = base; idx < end; idx++) {
                    long ix = idx % nX;
                    long iz = idx / nX;
                    long sx = firstX + ix * w;
                    long sz = firstZ + iz * w;
                    int count = countBlock(seed, sx, sz, window);
                    localMost.offer(count, (int) sx, (int) sz);
                    localLeast.offer(count, (int) sx, (int) sz);
                    localHits += count;
                }
                if (bar != null) {
                    bar.add(end - base);
                }
            }
            synchronized (mergeLock) {
                globalMost.mergeFrom(localMost);
                globalLeast.mergeFrom(localLeast);
                globalHits.addAndGet(localHits);
            }
        };

        for (int i = 0; i < threads; i++) {
            pool.execute(worker);
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
                throw new IllegalStateException("扫描超时(24小时)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("扫描被中断", e);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (bar != null) {
            bar.finish();
        }

        List<ResultItem> most = new ArrayList<>(globalMost.snapshot());
        List<ResultItem> least = new ArrayList<>(globalLeast.snapshot());
        return new ScanResult(cfg, most, least, total, globalHits.get(), elapsedMs);
    }

    /** 统计 [sx, sx+w) x [sz, sz+w) 内的史莱姆区块数 */
    private static int countBlock(long seed, long sx, long sz, int w) {
        int count = 0;
        for (long x = sx; x < sx + w; x++) {
            for (long z = sz; z < sz + w; z++) {
                if (SlimeChunk.isSlimeChunk(seed, (int) x, (int) z)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 最小的 >= a 的 b 的倍数 */
    static long ceilMul(long a, long b) {
        return ceilDiv(a, b) * b;
    }

    /** 最大的 <= a 的 b 的倍数 */
    static long floorMul(long a, long b) {
        return Math.floorDiv(a, b) * b;
    }

    /** 向上取整除法(对负数也正确) */
    static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
