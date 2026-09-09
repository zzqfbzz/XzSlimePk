# XzPk

批量计算《我的世界》指定范围内史莱姆区块的数量 / 寻找史莱姆区块密集与稀疏的区域。

## 版本

- **[V3（当前推荐，图形界面版）→ 见 [v3/README.md](v3/README.md)**](v3/README.md)
  统一 Java 工程，`grid`（铺格）与 `sliding`（滑动窗口）两种模式，参数在界面手动填写
  （无隐藏默认值），多线程、结果只保留最多/最少各 topK 条，判定公式与游戏官方一致。
  运行：`v3\gui.bat`（双击）。

- 旧版（历史参考）：
  - `src/slimepk.java`（旧命令行版，全程 long 公式，大坐标会算错，仅供对照）
  - `src/skimepk2.java`（滑动窗口重写版）

## 介绍文章

- [介绍文章在 B 站，点击快速前往](https://www.bilibili.com/opus/1109929191230406657)
- Introduction article on Bilibili, click to jump to it
