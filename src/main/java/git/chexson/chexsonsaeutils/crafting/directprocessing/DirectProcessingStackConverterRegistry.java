package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.appmek.AppliedMekanisticsChemicalKeyBridge;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DirectProcessingStackConverterRegistry {

    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String APPLIED_MEKANISTICS_MOD_ID = "appmek";
    private static final DirectProcessingStackConverterRegistry DEFAULT = createDefaultRegistry();

    private final List<StackConverter> converters;

    public DirectProcessingStackConverterRegistry(List<StackConverter> converters) {
        this.converters = List.copyOf(converters == null ? List.of() : converters);
    }

    public static DirectProcessingStackConverterRegistry directProcessingDefaults() {
        return DEFAULT;
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
            GenericStack converted = converter == null ? null : converter.toGenericStack(value);
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

    private static DirectProcessingStackConverterRegistry createDefaultRegistry() {
        List<StackConverter> converters = new ArrayList<>();
        ModList modList = ModList.get();
        if (modList != null
                && modList.isLoaded(MEKANISM_MOD_ID)
                && modList.isLoaded(APPLIED_MEKANISTICS_MOD_ID)) {
            converters.add(new AppliedMekanisticsChemicalStackConverter());
        }
        return new DirectProcessingStackConverterRegistry(converters);
    }

    public interface StackConverter {
        @Nullable
        GenericStack toGenericStack(Object value);
    }

    private static final class AppliedMekanisticsChemicalStackConverter implements StackConverter {

        private final AppliedMekanisticsChemicalKeyBridge bridge = AppliedMekanisticsChemicalKeyBridge.instance();

        @Override
        public @Nullable GenericStack toGenericStack(Object value) {
            if (!(value instanceof ChemicalStack chemicalStack) || chemicalStack.isEmpty() || !bridge.isAvailable()) {
                return null;
            }
            AEKey key = bridge.createKey(chemicalStack);
            return key == null ? null : new GenericStack(key, Math.max(1L, chemicalStack.getAmount()));
        }
    }
}
