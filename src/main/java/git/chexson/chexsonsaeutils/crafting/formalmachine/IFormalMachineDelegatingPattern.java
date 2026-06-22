package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;

/**
 * 模式委托接口，标识一个模式是否包装了另一个底层模式。
 * <p>
 * 当聚合或缩放模式需要暴露其原始 AE2 模式时实现此接口，
 * 使外部代码可以通过 {@link #basePattern()} 向下钻取到最底层的原生模式。
 */
public interface IFormalMachineDelegatingPattern {

    /**
     * 返回此模式所委托的底层原生模式。
     *
     * @return 底层的 IPatternDetails，不应为 null
     */
    IPatternDetails basePattern();
}
