package xzpk;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 结果输出：控制台报告 + 可选文件导出(CSV / TXT)。
 */
public final class Output {

    private Output() {
    }

    /** 把扫描结果打印到控制台 */
    public static void printReport(ScanResult r, PrintStream out) {
        out.print(reportText(r));
    }

    /**
     * 导出到文件。以 .csv 结尾写 CSV(带 BOM, 便于 Excel 直接打开中文)，
     * 否则写与控制台报告相同的纯文本。
     */
    public static void export(ScanResult r, Path file, PrintStream out) {
        try {
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                writeCsv(r, file);
            } else {
                writeText(r, file);
            }
            out.println("结果已导出到: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("导出文件失败: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    /** 生成完整报告文本(不含进度条) */
    public static String reportText(ScanResult r) {
        Config cfg = r.config;
        int w = cfg.windowSize();
        StringWriter sw = new StringWriter();

        List<ResultItem> most = new ArrayList<>(r.mostRaw);
        List<ResultItem> least = new ArrayList<>(r.leastRaw);
        most.sort(ResultItem.mostBestFirst());
        least.sort(ResultItem.leastBestFirst());

        line(sw, "");
        line(sw, "=== 史莱姆区块最多的窗口(共 " + most.size() + " 条) ===");
        line(sw, "# 行格式: 数量|x,z=世界方块坐标(窗口起点西北角)|X,Z=起点区块坐标|WxW|占比");
        for (ResultItem item : most) {
            line(sw, item.toLine(w));
        }

        line(sw, "");
        line(sw, "=== 史莱姆区块最少的窗口(共 " + least.size() + " 条) ===");
        line(sw, "# 行格式: 数量|x,z=世界方块坐标(窗口起点西北角)|X,Z=起点区块坐标|WxW|占比");
        for (ResultItem item : least) {
            line(sw, item.toLine(w));
        }

        line(sw, "");
        line(sw, "=== 统计信息 ===");
        line(sw, "模式: " + (cfg.mode() == Config.Mode.GRID ? "grid(从角点铺格)" : "sliding(滑动窗口)"));
        line(sw, "世界种子: " + cfg.seed());
        line(sw, "窗口大小: " + w + "x" + w + " 区块");
        line(sw, "扫描范围(区块,含端点): X[" + cfg.minChunkX() + ", " + cfg.maxChunkX()
                + "] Z[" + cfg.minChunkZ() + ", " + cfg.maxChunkZ() + "]");
        if (cfg.mode() == Config.Mode.SLIDING) {
            line(sw, "滑动步长 skip: " + cfg.skip() + (cfg.skip() == 1 ? "(全覆盖)" : ""));
        }
        line(sw, "候选窗口总数: " + r.totalWindows);
        line(sw, "命中总数(所有候选窗口内史莱姆区块数之和, 窗口重叠时重复计数): " + r.slimeHits);
        if (r.totalWindows > 0) {
            sw.write(String.format(Locale.ROOT, "平均每窗口史莱姆区块: %.2f%n",
                    (double) r.slimeHits / r.totalWindows));
        }
        if (!most.isEmpty()) {
            ResultItem max = most.get(0);
            sw.write(String.format(Locale.ROOT, "最多: %d 个(占 %.1f%%) 位于 %s%n",
                    max.count, max.count * 100.0 / (w * (double) w), max.toLine(w)));
        }
        if (!least.isEmpty()) {
            ResultItem min = least.get(0);
            sw.write(String.format(Locale.ROOT, "最少: %d 个(占 %.1f%%) 位于 %s%n",
                    min.count, min.count * 100.0 / (w * (double) w), min.toLine(w)));
        }
        line(sw, "理论概率: 每个区块为史莱姆区块的概率 10.0%");
        line(sw, "线程数: " + cfg.effectiveThreads());
        line(sw, "耗时: " + formatMs(r.elapsedMs));
        return sw.toString();
    }

    private static void line(StringWriter sw, String s) {
        sw.write(s);
        sw.write(System.lineSeparator());
    }

    private static void writeText(ScanResult r, Path file) throws IOException {
        ensureParent(file);
        Files.write(file, reportText(r).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeCsv(ScanResult r, Path file) throws IOException {
        ensureParent(file);
        Config cfg = r.config;
        int w = cfg.windowSize();

        List<ResultItem> most = new ArrayList<>(r.mostRaw);
        List<ResultItem> least = new ArrayList<>(r.leastRaw);
        most.sort(ResultItem.mostBestFirst());
        least.sort(ResultItem.leastBestFirst());

        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file), StandardCharsets.UTF_8))) {
            bw.write("\uFEFF"); // UTF-8 BOM, 方便 Excel 识别中文
            bw.write("side,rank,count,chunkStartX,chunkStartZ,blockStartX,blockStartZ,percent\n");
            int rank = 0;
            for (ResultItem item : most) {
                bw.write(csvRow("most", ++rank, item, w));
            }
            rank = 0;
            for (ResultItem item : least) {
                bw.write(csvRow("least", ++rank, item, w));
            }
        }
    }

    private static String csvRow(String side, int rank, ResultItem item, int w) {
        double pct = item.count * 100.0 / (w * (double) w);
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.1f%n",
                side, rank, item.count, item.chunkX, item.chunkZ,
                item.blockX(), item.blockZ(), pct);
    }

    private static void ensureParent(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }

    /** 耗时格式化 */
    public static String formatMs(long ms) {
        if (ms < 1000) {
            return ms + " 毫秒";
        }
        long secs = ms / 1000;
        long min = secs / 60;
        long rem = secs % 60;
        return min > 0 ? min + " 分 " + rem + " 秒" : rem + " 秒";
    }
}
