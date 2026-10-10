package git.chexson.chexsonsaeutils.client.gui.crafting;

import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import org.jetbrains.annotations.Nullable;

/**
 * 合成确认屏向 {@code CraftConfirmTableRenderer} mixin 暴露当前解析好的宽域快照。
 *
 * <p>renderer mixin 只拿到 {@code entry} 与 {@code screen} 引用；{@code screen} 若是
 * 我们的宽域确认屏（{@link WideAmountCraftConfirmScreen}）就向下转型到本接口读快照，
 * 否则（原生 AE2 屏或别的 mod 接管的屏）返回 {@code null}，数字显示原样退回 AE2 的 long。
 */
public interface WideAmountSource {

    /** 当前确认屏解析出的宽域快照；无数据或尚未下发时返回 {@code null}。 */
    @Nullable
    WideLedgerSnapshot chexsonsaeutils$wideSnapshot();
}
