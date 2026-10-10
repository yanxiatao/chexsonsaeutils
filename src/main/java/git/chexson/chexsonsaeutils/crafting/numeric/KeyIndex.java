package git.chexson.chexsonsaeutils.crafting.numeric;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import appeng.api.stacks.AEKey;

/**
 * 键内化表：把一次规划里会出现的全部 {@link AEKey} 编成稠密 int id，
 * 让账本从哈希表退化成数组寻址。
 *
 * <p>实机剖析显示求解自耗有一半以上花在账本查表上
 * （{@code Object2LongOpenHashMap.getLong} 24% + {@code WideKeyCounter.getLong} 18%
 * + {@code add} 13%），而 25.7 万次 request 摊下来每次要做八次左右哈希查询。
 * 键集合在编译期就已封闭（产出 / 输入 / 候选 / 余料 / 库存 / 目标 / 宏净流量），
 * 编号后热路径只需一次 id 解析（后续由 {@code Site} / {@code GraphPattern} 直接
 * 携带 id，连这一次也省掉），其余全是 {@code long[]} 下标读写。
 *
 * <p>求解单线程（与 {@code CraftGraph.stock} 的既有假设一致），迟到键按需追加编号。
 */
public final class KeyIndex {

    private static final int MISSING = -1;

    private final Object2IntOpenHashMap<AEKey> ids;
    private AEKey[] keys;
    private int count;

    public KeyIndex() {
        this(64);
    }

    public KeyIndex(int expectedKeys) {
        int capacity = Math.max(16, expectedKeys);
        this.ids = new Object2IntOpenHashMap<>(capacity * 2);
        this.ids.defaultReturnValue(MISSING);
        this.keys = new AEKey[capacity];
    }

    /** 键的 id；未编号则就地追加。 */
    public int idOf(AEKey key) {
        int existing = this.ids.getInt(key);
        if (existing != MISSING) {
            return existing;
        }
        int id = this.count++;
        if (id == this.keys.length) {
            this.keys = java.util.Arrays.copyOf(this.keys, this.keys.length << 1);
        }
        this.keys[id] = key;
        this.ids.put(key, id);
        return id;
    }

    /** 键的 id；未编号返回 {@code -1}（不追加）。 */
    public int lookup(AEKey key) {
        return this.ids.getInt(key);
    }

    public AEKey keyOf(int id) {
        return this.keys[id];
    }

    /** 已编号的键数——账本按此开表。 */
    public int size() {
        return this.count;
    }
}
