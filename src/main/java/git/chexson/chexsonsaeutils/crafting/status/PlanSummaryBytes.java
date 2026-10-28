package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.network.FriendlyByteBuf;
import java.math.BigInteger;
import org.jetbrains.annotations.Nullable;

/** 计划字节随当前摘要一起传输和读取，避免独立菜单字段缺失时显示饱和 long。 */
public final class PlanSummaryBytes {
    private PlanSummaryBytes() {}

    /** 摘要从原始计划取得字节，必须在递归计划被剥壳前保留原始引用。 */
    public static void attach(CraftingPlanSummary summary, ICraftingPlan plan) {
        if (summary instanceof EnhancedCraftingPlanSummary enhanced)
            enhanced.chexsonsaeutils$setExactBytes(WideAmountPlanCarrier.resolveBytes(plan).exactValue());
    }

    /** 标题优先读取与本摘要绑定的真值；原生摘要的有限 long 保持原义。 */
    public static BigInteger resolve(CraftingPlanSummary summary, String menuBytes) {
        if (summary instanceof EnhancedCraftingPlanSummary enhanced && enhanced.chexsonsaeutils$exactBytes() != null)
            return enhanced.chexsonsaeutils$exactBytes();
        return menuBytes.isEmpty() ? BigInteger.valueOf(summary.getUsedBytes()) : new BigInteger(menuBytes);
    }

    /** 空值仅表示原生摘要没有精确扩展，十进制传输不经过 long。 */
    public static void write(FriendlyByteBuf buffer, @Nullable BigInteger bytes) {
        git.chexson.chexsonsaeutils.network.codec.PacketTextWire.write(buffer, bytes == null ? "" : bytes.toString());
    }

    /** 非法负字节拒绝读取，不能悄悄替换为饱和值。 */
    public static @Nullable BigInteger read(FriendlyByteBuf buffer) {
        String encoded = git.chexson.chexsonsaeutils.network.codec.PacketTextWire.read(buffer);
        if (encoded.isEmpty()) return null;
        BigInteger bytes = new BigInteger(encoded);
        if (bytes.signum() < 0) throw new IllegalArgumentException("计划字节不能为负数");
        return bytes;
    }
}
