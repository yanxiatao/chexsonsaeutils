package git.chexson.chexsonsaeutils.crafting.directprocessing.appmek;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import mekanism.api.chemical.ChemicalStack;
import org.jetbrains.annotations.Nullable;

/** 以已有化学键桥接实现扩展转换，仅由装配层在两个化学模组均加载后创建。 */
public final class AppliedMekanisticsChemicalStackConverterImpl
        implements DirectProcessingStackConverterRegistry.StackConverter {

    private final AppliedMekanisticsChemicalKeyBridge bridge = AppliedMekanisticsChemicalKeyBridge.instance();

    @Override
    public @Nullable GenericStack toGenericStack(Object value) {
        if (!(value instanceof ChemicalStack chemicalStack) || chemicalStack.isEmpty()) {
            return null;
        }
        AEKey key = bridge.createKey(chemicalStack);
        return key == null ? null : new GenericStack(key, chemicalStack.getAmount());
    }
}
