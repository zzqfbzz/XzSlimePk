package xzpk;

import java.util.Random;

/**
 * Minecraft 史莱姆区块判定（Java 版，按官方源码语义）。
 *
 * <p>依据 [Minecraft Wiki](https://minecraft.wiki/w/Slime) 的判定代码：各项按
 * <b>32 位 int 运算</b>（溢出回绕）后再与世界种子相加，最后异或掩码：
 * <pre>
 *   hash = worldSeed
 *        + (int)(chunkX*chunkX * 0x4c1906)
 *        + (int)(chunkX      * 0x5ac0db)
 *        + (int)(chunkZ*chunkZ) * 0x4307a7L
 *        + (int)(chunkZ      * 0x5f24f)
 *   hash ^= 0x3ad8025fL
 *   new Random(hash).nextInt(10) == 0  → 史莱姆区块（概率 1/10）
 * </pre>
 *
 * <p><b>警告</b>：不要改成“全程 long”写法。旧版 slimepk.java 就是全程 long，
 * 区块坐标超过约 ±20 就会与真实游戏不一致（例如区块 X=5856 时同一 8×8 窗口
 * long 写法数出 24 个、官方写法只有 11 个）。</p>
 */
public final class SlimeChunk {

    private SlimeChunk() {
    }

    /**
     * 判断区块坐标 (chunkX, chunkZ) 在世界种子 worldSeed 下是否为史莱姆区块。
     *
     * @param worldSeed 世界种子（/seed 得到的值）
     * @param chunkX    区块 X 坐标（= floor(方块坐标/16)）
     * @param chunkZ    区块 Z 坐标
     * @return true 表示史莱姆区块
     */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long hash = worldSeed
                + (long) (chunkX * chunkX * 0x4c1906)
                + (long) (chunkX * 0x5ac0db)
                + (long) (chunkZ * chunkZ) * 0x4307a7L
                + (long) (chunkZ * 0x5f24f);
        hash ^= 0x3ad8025fL;
        return new Random(hash).nextInt(10) == 0;
    }

    /**
     * 旧版 slimepk.java 的实现（全程 long 运算），仅供自检对照——
     * 该写法在大坐标下与真实游戏不一致。
     */
    static boolean legacyLongMath(long seed, int cx, int cz) {
        long hash = seed
                + (long) cx * cx * 0x4c1906L
                + (long) cx * 0x5ac0dbL
                + (long) cz * cz * 0x4307a7L
                + (long) cz * 0x5f24fL;
        hash ^= 0x3ad8025fL;
        return new Random(hash).nextInt(10) == 0;
    }

    /**
     * 旧版 skimepk2.java 的实现（部分 int 溢出写法），仅供自检对照。
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
