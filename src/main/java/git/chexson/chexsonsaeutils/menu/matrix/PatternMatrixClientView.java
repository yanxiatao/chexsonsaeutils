package git.chexson.chexsonsaeutils.menu.matrix;

import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;

/**
 * 装配矩阵的客户端视图：只保存服务端下发的展示栈、状态码与按需索取的真栈，不持有全量配方。
 *
 * <p>{@link #changeCount()} 随任意一格的实际内容或命中位图变化而递增，界面据此决定
 * 行窗口与搜索过滤是否需要重建。</p>
 */
public final class PatternMatrixClientView {

    private static final int[] NO_CODES = new int[0];
    private static final long[] NO_BITS = new long[0];

    private ItemStack[] displayStacks = new ItemStack[0];
    private ItemStack[] realStacks = new ItemStack[0];
    private int[] statusCodes = NO_CODES;
    private int[] reasonCodes = NO_CODES;
    private long[] matchBits = NO_BITS;
    private int matchCount;
    private int nonEmptySlots;
    private int changeCount;

    /** 容量按服务端同步来的总槽数分配；缩容时丢弃多出来的格子。 */
    public void resize(int totalSlots) {
        int target = Math.max(0, totalSlots);
        if (target == displayStacks.length) {
            return;
        }
        ItemStack[] nextStacks = new ItemStack[target];
        ItemStack[] nextReal = new ItemStack[target];
        int[] nextStatus = new int[target];
        int[] nextReason = new int[target];
        int copied = Math.min(target, displayStacks.length);
        System.arraycopy(displayStacks, 0, nextStacks, 0, copied);
        System.arraycopy(realStacks, 0, nextReal, 0, copied);
        System.arraycopy(statusCodes, 0, nextStatus, 0, copied);
        System.arraycopy(reasonCodes, 0, nextReason, 0, copied);
        displayStacks = nextStacks;
        realStacks = nextReal;
        statusCodes = nextStatus;
        reasonCodes = nextReason;
        recountNonEmptySlots();
        changeCount++;
    }

    public void apply(List<PatternMatrixEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (PatternMatrixEntry entry : entries) {
            int slot = entry.slot();
            if (slot < 0 || slot >= displayStacks.length) {
                continue;
            }
            boolean wasEmpty = isEmpty(displayStacks[slot]);
            boolean isEmpty = isEmpty(entry.displayStack());
            if (wasEmpty && isEmpty) {
                continue;
            }
            if (wasEmpty) {
                nonEmptySlots++;
            } else if (isEmpty) {
                nonEmptySlots--;
            }
            if (!wasEmpty
                    && ItemStack.isSameItemSameComponents(displayStacks[slot], entry.displayStack())
                    && statusCodes[slot] == entry.statusCode()
                    && reasonCodes[slot] == entry.reasonCode()) {
                continue;
            }
            displayStacks[slot] = entry.displayStack().copy();
            statusCodes[slot] = entry.statusCode();
            reasonCodes[slot] = entry.reasonCode();
            // 内容变了就作废按需索取的本体，界面下一帧会重新索取
            realStacks[slot] = ItemStack.EMPTY;
            changeCount++;
        }
    }

    /** 写入可见行的样板本体；只接受本地视图里确实非空的格子。 */
    public void applyRealStacks(List<PatternMatrixRealStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return;
        }
        for (PatternMatrixRealStack realStack : stacks) {
            int slot = realStack.slot();
            if (slot < 0 || slot >= realStacks.length || isEmpty(displayStacks[slot])) {
                continue;
            }
            realStacks[slot] = realStack.stack().copy();
        }
    }

    /** 命中位图；长度为 0 表示当前没有过滤。 */
    public void applyMatches(long[] bits, int count) {
        long[] next = bits == null ? NO_BITS : bits;
        if (count == matchCount && Arrays.equals(next, matchBits)) {
            return;
        }
        matchBits = next;
        matchCount = count;
        changeCount++;
    }

    public int getTotalSlots() {
        return displayStacks.length;
    }

    public int getNonEmptySlots() {
        return nonEmptySlots;
    }

    public int changeCount() {
        return changeCount;
    }

    public ItemStack displayStackAt(int slot) {
        if (slot < 0 || slot >= displayStacks.length) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = displayStacks[slot];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public ItemStack realStackAt(int slot) {
        if (slot < 0 || slot >= realStacks.length) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = realStacks[slot];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** 移植件的格子取栈口径：样板本体已到就用本体，否则退回服务端下发的产出物摘要。 */
    public ItemStack cellStackAt(int slot) {
        ItemStack realStack = realStackAt(slot);
        return realStack.isEmpty() ? displayStackAt(slot) : realStack;
    }

    public int statusCodeAt(int slot) {
        if (slot < 0 || slot >= statusCodes.length) {
            return 0;
        }
        return statusCodes[slot];
    }

    public int reasonCodeAt(int slot) {
        if (slot < 0 || slot >= reasonCodes.length) {
            return 0;
        }
        return reasonCodes[slot];
    }

    public boolean hasDisplayStack(int slot) {
        return !isEmpty(displayStackAt(slot));
    }

    public boolean hasRealStack(int slot) {
        return !isEmpty(realStackAt(slot));
    }

    public boolean filterActive() {
        return matchBits.length > 0;
    }

    public int matchCount() {
        return matchCount;
    }

    public long[] matchBits() {
        return matchBits;
    }

    public boolean isMatched(int slot) {
        if (slot < 0) {
            return false;
        }
        int word = slot >>> 6;
        return word < matchBits.length && (matchBits[word] & (1L << (slot & 63))) != 0L;
    }

    private void recountNonEmptySlots() {
        int count = 0;
        for (ItemStack stack : displayStacks) {
            if (!isEmpty(stack)) {
                count++;
            }
        }
        nonEmptySlots = count;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
