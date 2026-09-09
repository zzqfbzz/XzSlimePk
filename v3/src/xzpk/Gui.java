package xzpk;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.nio.file.Paths;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultCaret;

/**
 * XzPk V3 图形界面：输入区 + 开始按钮 + 输出区。
 *
 * <p>范围坐标默认按<b>方块坐标</b>输入（游戏 F3 显示的那种），也可以切换成区块坐标；
 * 扫描在后台线程执行，输出直接复用命令行报告格式（最多/最少各 topK 条）。</p>
 */
public final class Gui extends JFrame {

    private static final long DEFAULT_RANGE_BLOCK = 9600L; // = ±600 区块

    private final JComboBox<String> modeBox =
            new JComboBox<>(new String[]{"grid(对齐网格)", "sliding(滑动窗口)"});
    private final JComboBox<String> unitBox =
            new JComboBox<>(new String[]{"方块坐标(游戏F3)", "区块坐标"});
    private final JTextField seedField = new JTextField(String.valueOf(Config.DEFAULT_SEED), 24);
    private final JTextField minXField = new JTextField(String.valueOf(-DEFAULT_RANGE_BLOCK), 8);
    private final JTextField maxXField = new JTextField(String.valueOf(DEFAULT_RANGE_BLOCK), 8);
    private final JTextField minZField = new JTextField(String.valueOf(-DEFAULT_RANGE_BLOCK), 8);
    private final JTextField maxZField = new JTextField(String.valueOf(DEFAULT_RANGE_BLOCK), 8);
    private final JTextField windowField = new JTextField(4);
    private final JTextField topKField = new JTextField("50", 5);
    private final JButton startButton = new JButton("开始扫描");
    private final JButton clearButton = new JButton("清空输出");
    private final JTextArea outputArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("就绪");
    private SwingWorker<Void, String> worker;

    public Gui() {
        super("XzPk V3 - 史莱姆区块扫描");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        buildUi();
        pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screen.width - getWidth()) / 2, Math.max(60, (screen.height - getHeight()) / 3));
        windowField.setToolTipText("留空=按模式默认 (grid: 8, sliding: 12)");
        unitBox.setToolTipText("范围/原点填什么单位: 方块坐标会按 ÷16 自动换算成区块");
    }

    private void buildUi() {
        // ---- 输入区 ----
        JPanel inputs = new JPanel();
        inputs.setLayout(new BoxLayout(inputs, BoxLayout.Y_AXIS));
        inputs.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        inputs.add(flowRow(new JLabel("计算模式:"), modeBox,
                new JLabel("  坐标单位:"), unitBox,
                new JLabel("    世界种子:"), seedField));
        inputs.add(flowRow(new JLabel("X 范围: "), minXField, new JLabel(" ~ "), maxXField,
                new JLabel("     Z 范围: "), minZField, new JLabel(" ~ "), maxZField));
        inputs.add(flowRow(new JLabel("窗口大小(区块):"), windowField,
                new JLabel("  输出条数 topK:"), topKField));
        inputs.add(flowRow(makeHint(
                "用法: 在\"大范围\"里找 8×8 区块(=128×128格) 的史莱姆区块最多/最少的小区域。"
                        + "范围按所选单位填, 窗口大小永远填区块数(如 8); 输出里 X,Z=起点区块, x,z=起点世界方块坐标")));

        // ---- 输出区 ----
        outputArea.setEditable(false);
        outputArea.setLineWrap(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        DefaultCaret caret = (DefaultCaret) outputArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane scroll = new JScrollPane(outputArea);
        scroll.setPreferredSize(new Dimension(860, 430));
        outputArea.append("输入参数后点击\"开始扫描\"。输出每行格式:\n"
                + "  数量|x,z=世界方块坐标|X,Z=起点区块坐标|WxW|占比\n\n");

        // ---- 底部: 状态 + 按钮 ----
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        buttons.add(clearButton);
        buttons.add(startButton);
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);

        add(inputs, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        startButton.addActionListener(e -> onStart());
        clearButton.addActionListener(e -> outputArea.setText(""));
    }

    private void onStart() {
        if (worker != null && !worker.isDone()) {
            return;
        }
        final Config cfg;
        try {
            cfg = readConfig();
        } catch (IllegalArgumentException ex) {
            append("参数错误: " + ex.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, ex.getMessage(), "参数错误",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        outputArea.setText("");
        startButton.setEnabled(false);
        modeBox.setEnabled(false);
        unitBox.setEnabled(false);
        statusLabel.setText("计算中...");

        worker = new SwingWorker<Void, String>() {
            private volatile ScanResult result;

            @Override
            protected Void doInBackground() {
                publish("== 开始扫描 ==\n"
                        + "模式: " + (cfg.mode() == Config.Mode.GRID ? "grid(对齐网格)" : "sliding(滑动窗口)")
                        + " | 坐标单位: " + (cfg.coordUnit() == Config.CoordUnit.BLOCK ? "方块" : "区块")
                        + " | 种子: " + cfg.seed()
                        + " | 窗口: " + cfg.windowSize() + "x" + cfg.windowSize() + " 区块"
                        + " | 换算后范围(区块) X[" + cfg.minChunkX() + "," + cfg.maxChunkX()
                        + "] Z[" + cfg.minChunkZ() + "," + cfg.maxChunkZ() + "]\n");
                ScanResult r = cfg.mode() == Config.Mode.GRID
                        ? GridScanner.scan(cfg)
                        : SlidingScanner.scan(cfg);
                result = r;
                publish(Output.reportText(r));
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    append(chunk);
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    append("错误: " + cause.getMessage() + "\n");
                    statusLabel.setText("扫描失败");
                }
                startButton.setEnabled(true);
                modeBox.setEnabled(true);
                unitBox.setEnabled(true);
                if (result != null) {
                    statusLabel.setText("完成: 耗时 " + Output.formatMs(result.elapsedMs)
                            + ", 候选窗口 " + result.totalWindows + " 个");
                }
                worker = null;
            }
        };
        worker.execute();
    }

    /** 从输入框组装配置：范围按所选单位换算成区块后交给 Config.Builder */
    private Config readConfig() {
        boolean block = unitBox.getSelectedIndex() == 0; // 0=方块, 1=区块

        long rawMinX = parseLong(minXField, "X 范围最小值");
        long rawMaxX = parseLong(maxXField, "X 范围最大值");
        long rawMinZ = parseLong(minZField, "Z 范围最小值");
        long rawMaxZ = parseLong(maxZField, "Z 范围最大值");
        if (rawMinX > rawMaxX) {
            throw new IllegalArgumentException("X 范围非法: 最小值 " + rawMinX + " 不能大于最大值 " + rawMaxX);
        }
        if (rawMinZ > rawMaxZ) {
            throw new IllegalArgumentException("Z 范围非法: 最小值 " + rawMinZ + " 不能大于最大值 " + rawMaxZ);
        }

        long minX = block ? Config.chunkFromBlock(rawMinX) : rawMinX;
        long maxX = block ? Config.chunkFromBlock(rawMaxX) : rawMaxX;
        long minZ = block ? Config.chunkFromBlock(rawMinZ) : rawMinZ;
        long maxZ = block ? Config.chunkFromBlock(rawMaxZ) : rawMaxZ;

        Config.Builder b = new Config.Builder()
                .mode(modeBox.getSelectedIndex() == 1 ? Config.Mode.SLIDING : Config.Mode.GRID)
                .coordUnit(block ? Config.CoordUnit.BLOCK : Config.CoordUnit.CHUNK)
                .seed(parseLong(seedField, "世界种子"))
                .minChunkX(minX)
                .maxChunkX(maxX)
                .minChunkZ(minZ)
                .maxChunkZ(maxZ)
                .topK((int) parseLong(topKField, "输出条数"))
                .showProgress(false)
                .outFile("")
                .source(Paths.get("(GUI 输入)"));
        String ws = windowField.getText().trim();
        if (!ws.isEmpty()) {
            b.windowSize((int) parseLong(windowField, "窗口大小"));
        }
        return b.build();
    }

    private static long parseLong(JTextField f, String name) {
        String text = f.getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 不是合法整数: \"" + text + "\"");
        }
    }

    private void append(String text) {
        outputArea.append(text);
        outputArea.append("\n");
    }

    /** 把一串组件/文本排成一行(左对齐) */
    private static JPanel flowRow(Object... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (Object item : items) {
            if (item instanceof String) {
                p.add(new JLabel((String) item));
            } else {
                p.add((Component) item);
            }
        }
        return p;
    }

    private static JLabel makeHint(String text) {
        JLabel hint = new JLabel(text);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        hint.setForeground(new java.awt.Color(0x666666));
        return hint;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Gui().setVisible(true));
    }
}
