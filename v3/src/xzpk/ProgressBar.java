package xzpk;

/**
 * 简易控制台进度条（守护线程驱动），多线程扫描共用。
 */
public final class ProgressBar {

    private static final int BAR_WIDTH = 40;
    private static final long REFRESH_MS = 200;

    private final String label;
    private final long total;
    private final java.util.concurrent.atomic.AtomicLong current = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean finished;
    private Thread thread;
    private long startNanos;

    public ProgressBar(String label, long total) {
        this.label = label;
        this.total = total;
    }

    public void add(long delta) {
        current.addAndGet(delta);
    }

    public void set(long value) {
        current.set(value);
    }

    /** 启动进度线程(内部自己刷行) */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        finished = false;
        startNanos = System.nanoTime();
        thread = new Thread(this::loop, "progress-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (!finished) {
            draw();
            try {
                Thread.sleep(REFRESH_MS);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        draw();
    }

    private void draw() {
        long cur = Math.min(current.get(), total);
        double pct = total <= 0 ? 100.0 : cur * 100.0 / total;
        int filled = (int) Math.round(BAR_WIDTH * pct / 100.0);
        if (filled > BAR_WIDTH) {
            filled = BAR_WIDTH;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filled; i++) {
            sb.append('#');
        }
        for (int i = filled; i < BAR_WIDTH; i++) {
            sb.append('.');
        }
        long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
        System.out.printf(java.util.Locale.ROOT, "\r%s [%s] %5.1f%% (%d/%d) 耗时: %d秒",
                label, sb, pct, cur, total, secs);
        System.out.flush();
    }

    /** 结束进度条并换行 */
    public synchronized void finish() {
        finished = true;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        System.out.println();
    }
}
