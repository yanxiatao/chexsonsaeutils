package git.chexson.chexsonsaeutils.network.matrix;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 装配矩阵单格的下发数据：全局槽号 + 展示栈 + 状态/原因码 + 本体修订号。
 *
 * <p>展示栈是样板产出物而非样板本体，因此一格的下发量与配方大小无关；
 * 修订号覆盖输入数量等不影响摘要的变化；槽位被清空时用空栈撤销客户端缓存。</p>
 */
public record PatternMatrixEntry(
        int slot,
        ItemStack displayStack,
        int statusCode,
        int reasonCode,
        PatternMatrixSearchTerms searchTerms,
        long revision
) {

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternMatrixEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            PatternMatrixEntry::slot,
            ItemStack.OPTIONAL_STREAM_CODEC,
            PatternMatrixEntry::displayStack,
            ByteBufCodecs.VAR_INT,
            PatternMatrixEntry::statusCode,
            ByteBufCodecs.VAR_INT,
            PatternMatrixEntry::reasonCode,
            PatternMatrixSearchTerms.STREAM_CODEC,
            PatternMatrixEntry::searchTerms,
            ByteBufCodecs.VAR_LONG,
            PatternMatrixEntry::revision,
            PatternMatrixEntry::new
    );

    public PatternMatrixEntry(int slot, ItemStack stack, int statusCode, int reasonCode) {
        this(slot, stack, statusCode, reasonCode, PatternMatrixSearchTerms.EMPTY, 0L);
    }

    public PatternMatrixEntry(int slot, ItemStack stack, int statusCode, int reasonCode,
            PatternMatrixSearchTerms searchTerms) {
        this(slot, stack, statusCode, reasonCode, searchTerms, 0L);
    }

    public boolean sameContent(PatternMatrixEntry other) {
        return other != null
                && other.slot == slot
                && other.statusCode == statusCode
                && other.reasonCode == reasonCode
                && other.revision == revision
                && other.searchTerms.equals(searchTerms)
                && ItemStack.isSameItemSameComponents(other.displayStack, displayStack);
    }
}
