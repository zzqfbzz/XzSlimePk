package xzpk;

import java.util.Random;

/**
 * Minecraft 史莱姆区块判定。
 *
 * <p>公式与项目旧版 slimepk / skimepk2 完全一致（长整型计算）：
 * <pre>
 *   hash = worldSeed
 *        + chunkX*chunkX * 0x4c1906 + chunkX * 0x5ac0db
 *        + chunkZ*chunkZ * 0x4307a7 + chunkZ * 0x5f24f
 *   hash ^= 0x3ad8025f
 *   new Random(hash).nextInt(10) == 0  → 史莱姆区块
 * </pre>
 * 每个区块成为史莱姆区块的理论概率为 1/10。
 */
public final class SlimeChunk {

    private SlimeChunk() {
    }

    /**
     * 判断区块坐标 (chunkX, chunkZ) 在世界种子 worldSeed 下是否为史莱姆区块。
     *
     * @param worldSeed 世界种子
     * @param chunkX    区块 X 坐标
     * @param chunkZ    区块 Z 坐标
     * @return true 表示史莱姆区块
     */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long hash = worldSeed
                + (long) chunkX * chunkX * 0x4c1906L
                + (long) chunkX * 0x5ac0dbL
                + (long) chunkZ * chunkZ * 0x4307a7L
                + (long) chunkZ * 0x5f24fL;
        hash ^= 0x3ad8025fL;
        return new Random(hash).nextInt(10) == 0;
    }

    /**
     * 旧版 slimepk.java 的实现（用于自检对照，证明 V3 与旧版结果一致）。
     */
    static boolean legacySlimepk1(long seed, int cx, int cz) {
        Random rnd = new Random(seed
                + (long) cx * cx * 0x4c1906L
                + (long) cx * 0x5ac0dbL
                + (long) cz * cz * 0x4307a7L
                + (long) cz * 0x5f24fL ^ 0x3ad8025fL);
        return rnd.nextInt(10) == 0;
    }

    /**
     * 旧版 skimepk2.java 的实现（用于自检对照，证明 V3 与旧版结果一致）。
     */
    static boolean legacySkimepk2(long seed, int cx, int cz) {
        Random rng = new Random(
                seed
                        + (long) (cx * cx * 4987142)
                        + (long) (cx * 5947611)
                        + (long) (cz * cz) * 4392871L
                        + (long) (cz * 389711) ^ 987234911L
        );
        return rng.nextInt(10) == 0;
    }
}
