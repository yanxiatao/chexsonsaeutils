package git.chexson.chexsonsaeutils.integration.directprocessing;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingExternalRecipeShapeRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.GenericRecipeShapeReader;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineAdapterRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeIndexBuilder;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 直连机的模组装配入口，集中决定可选支持的加载条件与优先级。 */
public final class DirectProcessingAssembly {

    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String EXTENDED_CRAFTING_MOD_ID = "extendedcrafting";
    private static final String APPLIED_MEKANISTICS_MOD_ID = "appmek";

    private DirectProcessingAssembly() {
    }

    /** 按当前配置组装索引构建器；配方识别和形状读取共享完整堆栈通道。 */
    public static MachineRecipeIndexBuilder createMachineRecipeIndexBuilder() {
        Predicate<String> modLoaded = ModList.get()::isLoaded;
        DirectProcessingStackConverterRegistry stackConverters = createStackConverters();
        return new MachineRecipeIndexBuilder(
                createMachineAdapterRegistry(modLoaded),
                new GenericRecipeShapeReader(
                        stackConverters,
                        createExternalRecipeShapeRegistry(modLoaded, stackConverters)
                ),
                ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_GENERIC_DISCOVERY_ENABLED.get()
        );
    }

    /** 为直连机与 JEI 提供相同的完整通道，化学转换要求 Mekanism 和 Applied Mekanistics 同时加载。 */
    public static DirectProcessingStackConverterRegistry createStackConverters() {
        return DefaultStackConvertersHolder.INSTANCE;
    }

    static DirectProcessingStackConverterRegistry createStackConverters(Predicate<String> modLoaded) {
        if (modLoaded.test(MEKANISM_MOD_ID) && modLoaded.test(APPLIED_MEKANISTICS_MOD_ID)) {
            return new DirectProcessingStackConverterRegistry(List.of(AppliedMekanisticsHolder.createConverter()));
        }
        return DirectProcessingStackConverterRegistry.builtins();
    }

    static MachineAdapterRegistry createMachineAdapterRegistry(Predicate<String> modLoaded) {
        List<MachineAdapterRegistry.MachineRecipeAdapter> adapters = new ArrayList<>();
        if (modLoaded.test(MEKANISM_MOD_ID)) {
            adapters.add(MekanismHolder.createAdapter());
        }
        if (modLoaded.test(EXTENDED_CRAFTING_MOD_ID)) {
            adapters.add(ExtendedCraftingHolder.createAdapter());
        }
        return new MachineAdapterRegistry(adapters);
    }

    static DirectProcessingExternalRecipeShapeRegistry createExternalRecipeShapeRegistry(
            Predicate<String> modLoaded,
            DirectProcessingStackConverterRegistry stackConverters
    ) {
        List<DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport> supports = new ArrayList<>();
        if (modLoaded.test(MEKANISM_MOD_ID)) {
            supports.add(MekanismHolder.createShapeSupport(stackConverters));
        }
        if (modLoaded.test(EXTENDED_CRAFTING_MOD_ID)) {
            supports.add(ExtendedCraftingHolder.createShapeSupport(stackConverters));
        }
        return new DirectProcessingExternalRecipeShapeRegistry(supports);
    }

    private static final class DefaultStackConvertersHolder {
        private static final DirectProcessingStackConverterRegistry INSTANCE =
                createStackConverters(ModList.get()::isLoaded);
    }

    private static final class MekanismHolder {
        private static MachineAdapterRegistry.MachineRecipeAdapter createAdapter() {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism
                    .MekanismMachineRecipeTypeAdapter();
        }

        private static DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport createShapeSupport(
                DirectProcessingStackConverterRegistry stackConverters
        ) {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.mekanism
                    .MekanismRecipeShapeSupport(stackConverters);
        }
    }

    private static final class ExtendedCraftingHolder {
        private static MachineAdapterRegistry.MachineRecipeAdapter createAdapter() {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting
                    .ExtendedCraftingMachineRecipeTypeAdapter();
        }

        private static DirectProcessingExternalRecipeShapeRegistry.ExternalRecipeShapeSupport createShapeSupport(
                DirectProcessingStackConverterRegistry stackConverters
        ) {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.extendedcrafting
                    .ExtendedCraftingRecipeShapeSupport(stackConverters);
        }
    }

    private static final class AppliedMekanisticsHolder {
        private static DirectProcessingStackConverterRegistry.StackConverter createConverter() {
            return new git.chexson.chexsonsaeutils.crafting.directprocessing.appmek
                    .AppliedMekanisticsChemicalStackConverterImpl();
        }
    }
}
