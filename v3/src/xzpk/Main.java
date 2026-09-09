package xzpk;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * XzPk V3 入口。
 *
 * <p>用法：{@code java xzpk.Main [配置文件路径]}；缺省读取运行目录下的
 * {@code config.properties}。所有参数都在配置文件中。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            Path cfgPath = args.length > 0 ? Paths.get(args[0]) : Paths.get("config.properties");
            Config cfg = Config.load(cfgPath);

            System.out.println("========== XzPk V3 史莱姆区块扫描 ==========");
            cfg.printSummary(System.out);
            System.out.println();

            long t0 = System.nanoTime();
            ScanResult result = cfg.mode() == Config.Mode.GRID
                    ? GridScanner.scan(cfg)
                    : SlidingScanner.scan(cfg);
            System.out.println("扫描完成, 耗时 " + Output.formatMs(result.elapsedMs)
                    + ", 处理候选窗口 " + result.totalWindows + " 个");

            Output.printReport(result, System.out);

            String outFile = cfg.outFile();
            if (!outFile.isEmpty()) {
                Output.export(result, Paths.get(outFile), System.out);
            }
            System.out.println();
            System.out.println("========== 完成 ==========");
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println();
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }
}
