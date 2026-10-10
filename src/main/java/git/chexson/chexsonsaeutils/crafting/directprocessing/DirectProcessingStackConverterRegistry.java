package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public final class DirectProcessingStackConverterRegistry {

    private static final DirectProcessingStackConverterRegistry BUILTINS =
            new DirectProcessingStackConverterRegistry(List.of());

    private final List<StackConverter> converters;

    public DirectProcessingStackConverterRegistry(List<StackConverter> converters) {
        this.converters = List.copyOf(converters);
    }

    /** 仅提供物品、流体和已有 GenericStack 的内建转换，不装配第三方通道。 */
    public static DirectProcessingStackConverterRegistry builtins() {
        return BUILTINS;
    }

    @Nullable
    public GenericStack convert(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? convert(optional.get()) : null;
        }
        GenericStack builtin = convertBuiltin(value);
        if (builtin != null) {
            return builtin;
        }
        for (StackConverter converter : converters) {
            GenericStack converted = converter.toGenericStack(value);
            if (converted != null && converted.what() != null && converted.amount() > 0L) {
                return new GenericStack(converted.what(), converted.amount());
            }
        }
        return null;
    }

    @Nullable
    private static GenericStack convertBuiltin(Object value) {
        if (value instanceof GenericStack genericStack) {
            return genericStack.what() == null || genericStack.amount() <= 0L
                    ? null
                    : new GenericStack(genericStack.what(), genericStack.amount());
        }
        if (value instanceof ItemStack itemStack) {
            if (itemStack.isEmpty()) {
                return null;
            }
            AEItemKey key = AEItemKey.of(itemStack);
            return key == null ? null : new GenericStack(key, Math.max(1L, itemStack.getCount()));
        }
        if (value instanceof FluidStack fluidStack) {
            if (fluidStack.isEmpty()) {
                return null;
            }
            AEFluidKey key = AEFluidKey.of(fluidStack);
            return key == null ? null : new GenericStack(key, Math.max(1L, fluidStack.getAmount()));
        }
        if (value instanceof ItemLike itemLike) {
            return convertBuiltin(itemLike.asItem().getDefaultInstance());
        }
        return null;
    }

    /** 扩展堆栈转换契约，使核心无需链接第三方堆栈类型。 */
    public interface StackConverter {
        /** 将支持的值转成 AE 堆栈；不支持或没有有效数量时返回 null，首个有效结果终止后续转换。 */
        @Nullable
        GenericStack toGenericStack(Object value);
    }
}
