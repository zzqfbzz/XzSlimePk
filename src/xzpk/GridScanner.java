package xzpk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 铺格(grid)扫描器（对应旧版 slimepk 思路）。
 *
 * <p>把窗口从范围左上角(最小坐标)起按 windowSize 铺成<b>互不重叠</b>的格子：
 * 锚点 = 范围最小坐标 + k×windowSize，锚点在范围内即可，窗口可向右/向下
 * <b>溢出</b>范围边缘（不足整块时也照常统计 8×8 的 64 格）。
 * 例：范围区块 0~100、窗口 8 → 锚点 0,8,16,…,96，窗口 (0,0)~(7,7)、(8,0)~(15,7)…</p>
 *
 * <p>并行方式：把全部格子按批次动态分发给线程池里的 worker，
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
        long minX = cfg.minChunkX();
        long maxX = cfg.maxChunkX();
        long minZ = cfg.minChunkZ();
        long maxZ = cfg.maxChunkZ();

        // 溢出语义: 锚点在范围内即可, 窗口可向右/下越过 max, 尾块不丢弃
        long nX = (maxX - minX) / w + 1L;
        long nZ = (maxZ - minZ) / w + 1L;
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
                    long sx = minX + ix * w;
                    long sz = minZ + iz * w;
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
}
