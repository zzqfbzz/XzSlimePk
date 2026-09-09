# XzPk V3 —— 史莱姆区块扫描器（规范重写版）

批量扫描《我的世界》指定范围内的史莱姆区块，按 **N×N 窗口**统计每个窗口内的
史莱姆区块数量，输出**最多（密集，适合建史莱姆农场）**和**最少（稀疏）**的若干窗口。

V3 为规范的 Java 工程重写，统一了旧版两个文件的能力：

| 旧版 | V3 |
|---|---|
| `slimepk.java`（对齐网格、命令行传参） | `mode=grid`（对齐网格） |
| `skimepk2.java`（滑动窗口、参数硬编码） | `mode=sliding`（滑动窗口） |

## 目录结构

```
v3/
├─ config.properties           # 默认配置(grid, 8x8, ±600, 快速可跑)
├─ config.sliding.properties   # sliding 大范围示例(8x8, ±5000)
├─ run.bat                     # 编译并运行命令行版(双击或命令行)
├─ gui.bat                     # 编译并启动图形界面(双击即可)
├─ selftest.bat                # 编译并运行内置自检(暴力法交叉验证)
└─ src/xzpk/
   ├─ Main.java        # 入口(命令行)
   ├─ Gui.java         # 图形界面(Swing, 输入框+开始按钮+输出区)
   ├─ Config.java      # 配置解析/Builder/校验(文件与 GUI 共用)
   ├─ SlimeChunk.java  # 史莱姆区块判定公式
   ├─ GridScanner.java # grid 模式(对齐网格, 多线程)
   ├─ SlidingScanner.java # sliding 模式(滑动窗口+滑窗优化)
   ├─ TopKCollector.java # 有界 Top-K 收集器(内存 O(K))
   ├─ ResultItem.java    # 一条结果 + 排序规则
   ├─ ScanResult.java    # 扫描汇总
   ├─ ProgressBar.java   # 控制台进度条
   ├─ Output.java        # 控制台报告 + CSV/TXT 导出
   └─ Selftest.java      # 无框架自检
```

## 使用

```bat
rem 0) 图形界面(推荐双击): 填参数 → 开始扫描 → 下方输出区看结果
v3\gui.bat

rem 1) 编译并运行命令行版(读运行目录下的 config.properties)
v3\run.bat

rem 2) 指定配置文件(大范围 sliding 示例)
v3\run.bat config.sliding.properties

rem 3) 只编译
javac -encoding UTF-8 -d out src\xzpk\*.java     (在 v3 目录内)

rem 4) 自检(与暴力法逐窗口比对)
v3\selftest.bat
```

GUI 基础版只暴露常用参数（模式 / 种子 / X·Z 范围 / 窗口大小 / 输出条数）；
线程数、原点、导出路径等高级项用命令行 + 配置文件控制。

## 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `mode` | `sliding` | `grid`=从角点铺格 / `sliding`=滑动窗口 |
| `coordUnit` | `chunk` | `chunk`=范围按区块坐标填 / `block`=按方块坐标填(游戏 F3 坐标)，自动 ÷16 换算成区块 |
| `seed` | `2950649267509295309` | 世界种子 |
| `minChunkX/maxChunkX/minChunkZ/maxChunkZ` | ±600 | 扫描范围（按 `coordUnit` 单位，**含端点**；窗口大小永远按区块数填） |
| `windowSize` | 8 | 窗口边长（区块）；8=单个玩家加载的最大区域（=128×128 格） |
| `skip` | 1 | sliding：窗口起点步进，1=全覆盖 |
| `bandCols` | 4096 | sliding：X 方向分片窗口起点列数 |
| `topK` | 100 | 最多/最少各保留条数（逐条与当前列表比较后插入，不保存全部窗口） |
| `originX/originZ` | 0 | 并列排序“距原点近→远”的原点（按 `coordUnit` 单位） |
| `threads` | 0 | 0=自动（CPU 核数） |
| `outFile` | 空 | 导出路径；`.csv`→CSV(带BOM)，否则 TXT |
| `showProgress` | true | 控制台进度条 |

## 典型用法示例

在游戏里以坐标原点为中心，方块坐标 `-100 ~ 100` 的大范围内，找
**8×8 区块（=128×128 格）里史莱姆区块最多/最少**的小区域：

```properties
mode=sliding        # 想任意摆放找最优
coordUnit=block     # 范围按方块坐标填
minChunkX=-100
maxChunkX=100
minChunkZ=-100
maxChunkZ=100
windowSize=8        # 8×8 区块
topK=100            # 最多/最少各保留 100 条
```

程序会自动把方块范围 ÷16 换算成区块（-100~100 → 区块 -7~6）再扫描，
输出每行带窗口的区块坐标(X,Z)与方块坐标(x,z)，照着飞过去圈 128×128 格即可。
GUI 里也一样：把“坐标单位”选成“方块坐标”，X/Z 范围直接填 F3 的数值。

## 两种模式的区别

- **grid（铺格）**：从范围的**左上角（最小坐标）**开始，每隔 `windowSize` 铺一块
  **互不重叠**的格子。锚点（窗口左上角）在范围内即可，窗口可向右/下**溢出**范围边缘，
  超出部分照常统计满 8×8。
  例：范围区块 0~100、窗口 8 → 锚点 0,8,…,96。
  适合：想把范围按固定格子分块统计的场景。
- **sliding（滑动窗口）**：窗口起点为范围内**每个区块**（可 `skip` 抽样），
  窗口同样允许向右/下溢出，找到“任意摆放”下的最优窗口位置。适合：想精确知道 8×8
  （单个玩家可加载的最大区域）窗口在这片范围里放哪最合适。sliding 的候选集包含 grid 的所有锚点。
  例：范围方块 0~100 → 区块 0~6，锚点方块 0,16,…,96 共 7 个。

sliding 使用滑窗增量算法：纵向环形缓冲 + 列和，横向 O(1) 滚动，
单个窗口的统计不随窗口大小增长，配合多线程分片可扫描上亿候选窗口。

## 输出格式

一行一条（便于复制）：

```
18|x,z=-144,-320|X,Z=-9,-20|8x8|28.1%
```

- `18`：窗口内史莱姆区块数量
- `x,z`：窗口起点区块的**世界方块坐标**（西北角，`区块×16`，可为负）
- `X,Z`：窗口起点**区块坐标**
- `8x8`：窗口大小（区块）
- `28.1%`：占比

“最多的”按 数量降序 → 距原点近→远 → Z升 → X升；
“最少的”按 数量升序 → 距原点近→远 → Z升 → X升。

## 判定公式与旧版兼容性

与旧版 `slimepk.java` **完全一致**（长整型运算，与 Chunkbase 等主流工具的语义相同）：

```java
hash = seed + chunkX²·0x4c1906 + chunkX·0x5ac0db + chunkZ²·0x4307a7 + chunkZ·0x5f24f
hash ^= 0x3ad8025f
new Random(hash).nextInt(10) == 0   → 史莱姆区块(理论概率 10%)
```

注意：旧版 `skimepk2.java` 部分乘法为 int 运算，在 |区块坐标| > 20 左右会因
int 溢出而偏离真实判定，V3 不沿用该写法（自检中已验证小坐标下与之一致）。

## 自检覆盖

- 公式与旧版一致性（随机 8 万坐标）
- grid 扫描 vs 暴力直接数（含负坐标、不整除范围）
- sliding 扫描 vs 暴力直接数（4 组：多分片、skip=2、非对称范围、单列）
- 两次运行结果确定性
