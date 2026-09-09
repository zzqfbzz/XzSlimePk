package xzpk;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 有界 Top-K 收集器：边扫描边只保留“最好”的 K 条，内存占用 O(K)。
 *
 * <p>规则：{@link Side#MOST} 保留史莱姆区块数最多的 K 个窗口；
 * {@link Side#LEAST} 保留最少的 K 个窗口。
 * 数量并列时依次按 距原点近→远 → Z 升 → X 升 排序（确定性）。</p>
 *
 * <p>offer(...) 用裸指标与堆顶(当前最差)比较，未入选时零对象分配，
 * 保证每秒百万级候选窗口的吞吐。</p>
 */
public final class TopKCollector {

    public enum Side { MOST, LEAST }

    private final int capacity;
    private final Side side;
    private final long originX;
    private final long originZ;
    /** 堆顶 = 当前保留条目里最差的一个 */
    private final PriorityQueue<ResultItem> heap;

    public TopKCollector(int capacity, Side side, long originX, long originZ) {
        if (capacity < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1");
        }
        this.capacity = capacity;
        this.side = side;
        this.originX = originX;
        this.originZ = originZ;
        // 堆使用“更差排前面”的比较器：小顶堆堆顶自然是最差
        this.heap = new PriorityQueue<>(Math.min(capacity, 256) + 1,
                side == Side.MOST ? ResultItem.mostBestFirst().reversed()
                                  : ResultItem.leastBestFirst().reversed());
    }

    public int size() {
        return heap.size();
    }

    private long dist2(int chunkX, int chunkZ) {
        long dx = (long) chunkX - originX;
        long dz = (long) chunkZ - originZ;
        return dx * dx + dz * dz;
    }

    /** 判断裸指标 (count, chunkX, chunkZ) 是否优于现有最差条目 root */
    private boolean betterThanRoot(ResultItem root, int count, long d2, int chunkX, int chunkZ) {
        if (count != root.count) {
            return side == Side.MOST ? count > root.count : count < root.count;
        }
        if (d2 != root.dist2) {
            return d2 < root.dist2;
        }
        if (chunkZ != root.chunkZ) {
            return chunkZ < root.chunkZ;
        }
        return chunkX < root.chunkX;
    }

    /**
     * 提交一个候选窗口。
     *
     * @param count   窗口内史莱姆区块数
     * @param chunkX  窗口起点区块 X
     * @param chunkZ  窗口起点区块 Z
     */
    public void offer(int count, int chunkX, int chunkZ) {
        if (heap.size() < capacity) {
            heap.add(new ResultItem(count, chunkX, chunkZ, dist2(chunkX, chunkZ)));
            return;
        }
        ResultItem root = heap.peek();
        if (betterThanRoot(root, count, dist2(chunkX, chunkZ), chunkX, chunkZ)) {
            heap.poll();
            heap.add(new ResultItem(count, chunkX, chunkZ, dist2(chunkX, chunkZ)));
        }
    }

    /** 把另一个收集器的内容并入本收集器 */
    public void mergeFrom(TopKCollector other) {
        for (ResultItem item : other.heap) {
            addExisting(item);
        }
    }

    private void addExisting(ResultItem item) {
        if (heap.size() < capacity) {
            heap.add(item);
            return;
        }
        ResultItem root = heap.peek();
        if (betterThanRoot(root, item.count, item.dist2, item.chunkX, item.chunkZ)) {
            heap.poll();
            heap.add(item);
        }
    }

    /** 快照当前保留条目(未排序) */
    public List<ResultItem> snapshot() {
        return new ArrayList<>(heap);
    }
}
