package git.chexson.chexsonsaeutils.mixin.ae2.parts.encoding;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.parts.encoding.PatternEncodingLogic;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternDetails;

/**
 * 物质团样板接入 AE2 原生编码终端的"放回自动解析"（照现有
 * PatternEncodingLogicMixin 的框架样板模式，独立类便于特性门控）。
 * <p>
 * 动机：AE2 PatternEncodingLogic.loadEncodedPattern 纯 instanceof 四分支只认
 * 原生四类样板，物质团样板放入样板槽会被静默跳过。本 mixin 在 HEAD 注入
 * （不取消）：解码出物质团样板时用原始输入/输出快照还原等价
 * AEProcessingPattern（{@link MatterMassPatternDetails#getAEProcessingPattern}），
 * 调原生 loadProcessingPattern 回填输入/输出格；随后"编码"按钮写出的产物
 * 天然是标准处理样板（原始配方），实现物质团样板的可逆重编码。
 * <p>
 * 受物质团供应器特性门控（ChexsonsaeutilsMixinPlugin.MATTER_MASS_ONLY_MIXINS）。
 */
@Mixin(value = PatternEncodingLogic.class, remap = false)
public abstract class PatternEncodingLogicMatterMassMixin {

    @Final
    @Shadow
    private IPatternTerminalLogicHost host;

    @Shadow
    private void loadProcessingPattern(AEProcessingPattern pattern) {
    }

    @Inject(method = "loadEncodedPattern", at = @At("HEAD"))
    protected void chexsonsaeutils$onLoadEncodedPattern(ItemStack pattern, CallbackInfo ci) {
        if (pattern.isEmpty()) {
            return;
        }
        var details = PatternDetailsHelper.decodePattern(pattern, this.host.getLevel());
        if (details instanceof MatterMassPatternDetails matterMassPattern) {
            var aePattern = matterMassPattern.getAEProcessingPattern(this.host.getLevel());
            if (aePattern != null) {
                this.loadProcessingPattern(aePattern);
            }
        }
    }
}
