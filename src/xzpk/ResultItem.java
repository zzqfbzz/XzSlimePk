package xzpk;

import java.util.Comparator;

/**
 * 一条扫描结果：一个 W×W 窗口(起点区块坐标) 内包含的史莱姆区块数量。
 *
 * <p>输出格式（与新版 skimepk2 风格一致，一行一条便于复制）：
 * <pre>
 *   count|x,z=bx,bz|X,Z=cx,cz|WxW|pct%
 * </pre>
 * 其中 x,z 为窗口起点区块的世界方块坐标(西北角, 即 chunk*16)，X,Z 为窗口起点区块坐标。</p>
 */
public final class ResultItem {

    public final int count;
    public final int chunkX;
    public final int chunkZ;
    public final long dist2;

    ResultItem(int count, int chunkX, int chunkZ, long dist2) {
        this.count = count;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dist2 = dist2;
    }

    /** 创建结果项并相对 (originX, originZ) 计算距离平方(用于并列排序) */
    static ResultItem of(int count, int chunkX, int chunkZ, long originX, long originZ) {
        long dx = (long) chunkX - originX;
        long dz = (long) chunkZ - originZ;
        return new ResultItem(count, chunkX, chunkZ, dx * dx + dz * dz);
    }

    public long blockX() {
        return (long) chunkX * 16L;
    }

    public long blockZ() {
        return (long) chunkZ * 16L;
    }

    /** 输出一行(窗口大小 window) */
    public String toLine(int window) {
        double pct = count * 100.0 / ((double) window * window);
        return String.format(java.util.Locale.ROOT,
                "%d|x,z=%d,%d|X,Z=%d,%d|%dx%d|%.1f%%",
                count, blockX(), blockZ(), chunkX, chunkZ, window, window, pct);
    }

    @Override
    public String toString() {
        return "Win{count=" + count + ", chunk=(" + chunkX + "," + chunkZ + "), dist2=" + dist2 + "}";
    }

    /** “最多”一端的最终排序：数量降序 → 距原点近→远 → Z 升 → X 升 */
    public static Comparator<ResultItem> mostBestFirst() {
        return (a, b) -> {
            if (a.count != b.count) {
                return Integer.compare(b.count, a.count);
            }
            if (a.dist2 != b.dist2) {
                return Long.compare(a.dist2, b.dist2);
            }
            if (a.chunkZ != b.chunkZ) {
                return Integer.compare(a.chunkZ, b.chunkZ);
            }
            return Integer.compare(a.chunkX, b.chunkX);
        };
    }

    /** “最少”一端的最终排序：数量升序 → 距原点近→远 → Z 升 → X 升 */
    public static Comparator<ResultItem> leastBestFirst() {
        return (a, b) -> {
            if (a.count != b.count) {
                return Integer.compare(a.count, b.count);
            }
            if (a.dist2 != b.dist2) {
                return Long.compare(a.dist2, b.dist2);
            }
            if (a.chunkZ != b.chunkZ) {
                return Integer.compare(a.chunkZ, b.chunkZ);
            }
            return Integer.compare(a.chunkX, b.chunkX);
        };
    }
}
