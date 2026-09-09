package xzpk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滑动窗口扫描器（对应新版 skimepk2 思路）。
 *
 * <p>窗口起点覆盖范围内每一个可行位置（可按 skip 抽样），即能找到
 * “任意摆放”窗口下的最优位置，而不限于对齐网格。</p>
 *
 * <p>性能优化：
 * <ul>
 *   <li>逐行增量滑动：纵向用 环形缓冲(ring) + 列和(colSums)，横向窗口和 O(1) 滚动；</li>
 *   <li>X 方向按 bandCols 分片（片间重叠 w-1 列），Z 方向同样分片，多任务并行；</li>
 *   <li>每个任务只保留本地 Top-K，任务结束再合并，降低锁竞争。</li>
 * </ul>
 * 单个窗口的计数代价约为 O(1)（不随窗口大小增长）。</p>
 */
public final class SlidingScanner {

    private SlidingScanner() {
    }

    /**
     * 执行 sliding 模式扫描。
     *
     * @param cfg 校验通过的配置（mode == SLIDING，且窗口能放进范围）
     * @return 扫描结果
     */
    public static ScanResult scan(Config cfg) {
        int skip = cfg.skip();
        long w = cfg.windowSize();
        long sxLo = cfg.minChunkX();
        long sxHi = cfg.maxChunkX() - w + 1L;
        long szLo = cfg.minChunkZ();
        long szHi = cfg.maxChunkZ() - w + 1L;

        long nSx = (sxHi - sxLo) / skip + 1L;
        long nSz = (szHi - szLo) / skip + 1L;
        long totalWindows = Math.multiplyExact(nSx, nSz);

        List<long[]> xBands = splitBands(nSx, cfg.bandCols());
        List<long[]> zBands = splitBands(nSz, cfg.bandCols());

        final int threads = cfg.effectiveThreads();
        final long originX = cfg.originX();
        final long originZ = cfg.originZ();
        final int topK = cfg.topK();

        TopKCollector globalMost = new TopKCollector(topK, TopKCollector.Side.MOST, originX, originZ);
        TopKCollector globalLeast = new TopKCollector(topK, TopKCollector.Side.LEAST, originX, originZ);
        AtomicLong globalHits = new AtomicLong();
        Object mergeLock = new Object();

        ProgressBar bar = cfg.showProgress() ? new ProgressBar("扫描(sliding)", totalWindows) : null;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        long startNanos = System.nanoTime();
        if (bar != null) {
            bar.start();
        }

        List<Runnable> tasks = new ArrayList<>();
        for (long[] xb : xBands) {
            for (long[] zb : zBands) {
                tasks.add(bandTask(cfg, xb[0], xb[1], zb[0], zb[1],
                        globalMost, globalLeast, globalHits, mergeLock, bar));
            }
        }
        for (Runnable task : tasks) {
            pool.execute(task);
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

        return new ScanResult(cfg,
                new ArrayList<>(globalMost.snapshot()),
                new ArrayList<>(globalLeast.snapshot()),
                totalWindows, globalHits.get(), elapsedMs);
    }

    /**
     * 把 [0, n) 的候选索引切成若干段，每段最多 max 个。
     * 返回每段的 [lo, hi]（含端点）。
     */
    private static List<long[]> splitBands(long n, long max) {
        List<long[]> bands = new ArrayList<>();
        for (long lo = 0; lo < n; lo += max) {
            long hi = Math.min(lo + max - 1, n - 1);
            bands.add(new long[]{lo, hi});
        }
        return bands;
    }

    /**
     * 生成一个 (xBand, zBand) 的扫描任务。
     * 行缓冲、列和等数组在任务真正执行时才分配。
     */
    private static Runnable bandTask(Config cfg,
                                     long xb0, long xb1,
                                     long zb0, long zb1,
                                     TopKCollector globalMost, TopKCollector globalLeast,
                                     AtomicLong globalHits, Object mergeLock, ProgressBar bar) {
        return () -> {
            int skip = cfg.skip();
            long w = cfg.windowSize();
            long seed = cfg.seed();
            long szLo = cfg.minChunkZ();
            final long originX = cfg.originX();
            final long originZ = cfg.originZ();
            final int topK = cfg.topK();

            TopKCollector localMost = new TopKCollector(topK, TopKCollector.Side.MOST, originX, originZ);
            TopKCollector localLeast = new TopKCollector(topK, TopKCollector.Side.LEAST, originX, originZ);

            long sxFirst = cfg.minChunkX() + xb0 * skip;
            int nCand = (int) (xb1 - xb0 + 1L);
            int nCols = (int) ((xb1 - xb0) * skip + w);
            int[] colSums = new int[nCols];
            byte[][] ring = new byte[(int) w][nCols];

            long zTop = szLo + zb0 * skip;
            // 预填充前 w 行（zTop .. zTop+w-1）
            for (int r = 0; r < w; r++) {
                long z = zTop + r;
                for (int ci = 0; ci < nCols; ci++) {
                    int bit = SlimeChunk.isSlimeChunk(seed, (int) (sxFirst + ci), (int) z) ? 1 : 0;
                    ring[r][ci] = (byte) bit;
                    colSums[ci] += bit;
                }
            }

            int head = 0;
            long hits = 0L;
            long windowsInTask = 0L;

            for (long zi = zb0; zi <= zb1; zi++) {
                // 横向：第一个窗口
                int sum = 0;
                for (int k = 0; k < w; k++) {
                    sum += colSums[k];
                }
                // 横向滚动所有候选窗口起点（步长 skip）
                for (int t = 0; t < nCand; t++) {
                    long sx = sxFirst + (long) t * skip;
                    localMost.offer(sum, (int) sx, (int) zTop);
                    localLeast.offer(sum, (int) sx, (int) zTop);
                    hits += sum;
                    if (t < nCand - 1) {
                        int base = t * skip;
                        for (int k = 0; k < skip; k++) {
                            sum += colSums[base + (int) w + k] - colSums[base + k];
                        }
                    }
                }
                windowsInTask += nCand;

                // 纵向下移 skip 行：换出最旧 skip 行、换入 skip 个新行
                if (zi < zb1) {
                    for (int k = 0; k < skip; k++) {
                        int slot = (head + k) % (int) w;
                        long zEnter = zTop + w + k;
                        for (int ci = 0; ci < nCols; ci++) {
                            int enter = SlimeChunk.isSlimeChunk(seed, (int) (sxFirst + ci), (int) zEnter) ? 1 : 0;
                            colSums[ci] += enter - ring[slot][ci];
                            ring[slot][ci] = (byte) enter;
                        }
                    }
                    head = (head + skip) % (int) w;
                    zTop += skip;
                }
            }

            synchronized (mergeLock) {
                globalMost.mergeFrom(localMost);
                globalLeast.mergeFrom(localLeast);
                globalHits.addAndGet(hits);
            }
            if (bar != null) {
                bar.add(windowsInTask);
            }
        };
    }
}
