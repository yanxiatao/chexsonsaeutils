package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.client.Point;
import appeng.client.gui.layout.SlotGridLayout;
import git.chexson.chexsonsaeutils.client.gui.MultiPagePatternScreen;
import net.minecraft.client.Minecraft;

/**
 * 多页样板供应器摆位（照抄 ExtendedAE_Plus SlotGridLayoutMixin）。
 * <p>
 * 动机：AE2 repositionSlots 按语义组索引排布全部槽位，扩容后索引 36+ 的样板槽会
 * 排到网格下方溢出区（与返回库存重叠）。本 mixin 拦截 9 列网格布局函数：
 * 非当前页槽位映射到屏幕外 (-10000,-10000)，当前页槽位按页内序号映射回网格前 36 格，
 * 使每一页的样板槽位置完全相同。
 * <p>
 * 安全性：翻页仅对 ENCODED_PATTERN/STORAGE 两组触发重摆；玩家背包虽同为 9 列网格，
 * 但 init 后不再重布局且 init 时页号恒为 0，不受影响。
 */
@Mixin(value = SlotGridLayout.class, remap = false)
public abstract class SlotGridLayoutMultiPageMixin {

    /** 每页样板槽数量（与 FramePatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE 一致）。 */
    @Unique
    private static final int SLOTS_PER_PAGE = 36;

    @Inject(method = "getRowBreakPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private static void chexsonsaeutils$onGetRowBreakPosition(int x, int y, int semanticIdx, int cols,
                                                              CallbackInfoReturnable<Point> cir) {
        if (cols != 9 || !(Minecraft.getInstance().screen instanceof MultiPagePatternScreen screen)) {
            return;
        }

        int currentPage = screen.chexsonsaeutils$getCurrentPage();
        if (semanticIdx / SLOTS_PER_PAGE != currentPage) {
            // 非当前页：移出屏幕
            cir.setReturnValue(new Point(-10000, -10000));
            cir.cancel();
            return;
        }

        // 当前页：按页内序号映射回同一网格区域
        int slotInPage = semanticIdx % SLOTS_PER_PAGE;
        cir.setReturnValue(new Point(x + (slotInPage % 9) * 18, y + (slotInPage / 9) * 18));
        cir.cancel();
    }
}
