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
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomProcessingPattern;

/**
 * 框架样板接入 AE2 原生编码终端的"放回自动解析"（照 advancedae MixinPatternEncodingLogic 模式）。
 * <p>
 * 动机：AE2 PatternEncodingLogic.loadEncodedPattern 纯 instanceof 四分支只认原生四类
 * 样板，框架样板放入编码终端的样板输出槽会被静默跳过、不回填输入/输出格。本 mixin 在
 * 其 HEAD 注入（不取消）：解码出框架样板时转换为等价 AEProcessingPattern
 * （{@link CustomProcessingPattern#getAEProcessingPattern}），再调用原生
 * loadProcessingPattern 完成回填；其余类型放行原逻辑。
 */
@Mixin(value = PatternEncodingLogic.class, remap = false)
public abstract class PatternEncodingLogicMixin {

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
        if (details instanceof CustomProcessingPattern framePattern) {
            var aePattern = framePattern.getAEProcessingPattern(this.host.getLevel());
            if (aePattern != null) {
                this.loadProcessingPattern(aePattern);
            }
        }
    }
}
