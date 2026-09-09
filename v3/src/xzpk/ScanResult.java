package xzpk;

import java.util.List;

/**
 * 一次扫描的汇总结果。
 *
 * <p>mostRaw / leastRaw 为收集器快照(未排序)，展示前请用
 * {@link ResultItem#mostBestFirst()} / {@link ResultItem#leastBestFirst()} 排序。</p>
 */
public final class ScanResult {

    public final Config config;
    /** 史莱姆区块最多的窗口(原始快照) */
    public final List<ResultItem> mostRaw;
    /** 史莱姆区块最少的窗口(原始快照) */
    public final List<ResultItem> leastRaw;
    /** 扫描(候选)窗口总数 */
    public final long totalWindows;
    /** 所有候选窗口的史莱姆命中数之和(重叠窗口会重复计数, 用于求平均) */
    public final long slimeHits;
    /** 扫描耗时(毫秒) */
    public final long elapsedMs;

    public ScanResult(Config config, List<ResultItem> mostRaw, List<ResultItem> leastRaw,
                      long totalWindows, long slimeHits, long elapsedMs) {
        this.config = config;
        this.mostRaw = mostRaw;
        this.leastRaw = leastRaw;
        this.totalWindows = totalWindows;
        this.slimeHits = slimeHits;
        this.elapsedMs = elapsedMs;
    }
}
