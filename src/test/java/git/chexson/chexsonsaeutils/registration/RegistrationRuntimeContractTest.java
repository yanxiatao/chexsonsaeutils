package git.chexson.chexsonsaeutils.registration;

import appeng.block.AEBaseEntityBlock;
import git.chexson.chexsonsaeutils.bootstrap.mattermass.MatterMassPatternProviderMenuRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderBlockEntityRegistration;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternRegistration;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingRecipeRegistration;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternRegistration;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassRegistration;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassViewMenuHost;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ExtremeAssemblerMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.menu.mattermassprovider.MatterMassPatternProviderMenu;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderPartRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderRegistration;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册完成后的真实契约：直接读取注册表和已绑定把手，不解析生产源码或重放全局注册事件。
 *
 * <p>期望来自本批修改前的注册集合及 DeferredRegister 挂接顺序，逐个写明实际 ID。
 * 序只比较同一种注册表中的本 mod 条目，不把跨注册表事件序或源码邻接行当运行契约。
 * 首次执行必须确认 NeoForge 的 testedMod 已加载；加载缺失直接失败，不跳过或补造注册状态。
 * 所有用例仅观察现有全局状态，不更改配置、注册器、菜单绑定或 decoder 列表。
 */
final class RegistrationRuntimeContractTest {

    private record RegistryContract(Registry<?> registry, List<String> paths) {
    }

    /** 八类真实注册值共 40 个；顺序按当前各注册器的挂接顺序及其条目顺序展开。 */
    private static final List<RegistryContract> CONTRACTS = List.of(
            new RegistryContract(BuiltInRegistries.BLOCK, List.of(
                    "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool",
                    "auto_item_gen", "matter_mass_pattern_provider", "custom_pattern_provider")),
            new RegistryContract(BuiltInRegistries.ITEM, List.of(
                    "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool",
                    "multi_level_emitter", "player_simulation_plane", "infinity_cell", "auto_item_gen", "matter_mass_pattern_provider",
                    "custom_pattern", "custom_pattern_provider_part", "custom_pattern_provider",
                    "matter_mass_pattern", "matter_mass")),
            new RegistryContract(BuiltInRegistries.BLOCK_ENTITY_TYPE, List.of(
                    "auto_item_gen", "extreme_assembler", "ae_direct_processing_machine",
                    "ae2_parallel_cpu_tool", "matter_mass_pattern_provider", "custom_pattern_provider")),
            new RegistryContract(BuiltInRegistries.MENU, List.of(
                    "simulation_amount", "player_simulation_plane",
                    "extreme_assembler", "ae_direct_processing_machine", "ae2_parallel_cpu_tool_cpu",
                    "custom_pattern_provider", "custom_pattern_encoder", "custom_pattern_upgrade",
                    "multi_level_emitter_menu", "matter_mass_pattern_provider", "matter_mass_view")),
            new RegistryContract(BuiltInRegistries.CREATIVE_MODE_TAB, List.of("chexsonsaeutils")),
            new RegistryContract(BuiltInRegistries.DATA_COMPONENT_TYPE, List.of(
                    "encoded_custom_pattern", "custom_pattern_pages",
                    "encoded_matter_mass_pattern", "matter_mass_uuid")),
            new RegistryContract(BuiltInRegistries.RECIPE_TYPE, List.of("direct_processing_mock")),
            new RegistryContract(BuiltInRegistries.RECIPE_SERIALIZER, List.of("direct_processing_mock"))
    );

    @Test
    void registeredIdsAndBoundHandlesMatchActualRegistryValues() {
        requireLoadedMod();
        Map<ResourceLocation, Set<ResourceLocation>> declared = new LinkedHashMap<>();
        for (DeferredRegister<?> registration : registrations()) {
            ResourceLocation kind = registration.getRegistryName();
            Registry<?> runtime = BuiltInRegistries.REGISTRY.get(kind);
            assertNotNull(runtime, "运行注册表缺失：" + kind);
            Set<ResourceLocation> ids = declared.computeIfAbsent(kind, ignored -> new LinkedHashSet<>());
            for (var holder : registration.getEntries()) {
                assertTrue(ids.add(holder.getId()), "重复登记：" + kind + "/" + holder.getId());
                assertTrue(holder.isBound(), "注册事件未绑定把手：" + kind + "/" + holder.getId());
                assertSame(holder.get(), runtime.get(holder.getId()),
                        "把手与实际注册值不一致：" + kind + "/" + holder.getId());
            }
        }
        Set<ResourceLocation> expectedKinds = new LinkedHashSet<>();
        for (RegistryContract contract : CONTRACTS) {
            ResourceLocation kind = contract.registry().key().location();
            expectedKinds.add(kind);
            Set<ResourceLocation> expected = new LinkedHashSet<>(ids(contract.paths()));
            assertEquals(expected, new LinkedHashSet<>(registeredIds(contract.registry())),
                    "实际登记集合发生变化：" + kind);
            assertEquals(expected, declared.get(kind), "注册器清单未覆盖实际登记：" + kind);
        }
        assertEquals(expectedKinds, declared.keySet(), "新增或遗漏的注册种类必须进入运行契约");
    }

    @Test
    void registrationOrderIsPreservedWithinEachRegistryKind() {
        requireLoadedMod();
        for (RegistryContract contract : CONTRACTS) {
            assertEquals(ids(contract.paths()), registeredIds(contract.registry()),
                    "同种注册表中的实际登记顺序发生变化：" + contract.registry().key().location());
        }
    }

    @Test
    void everyRegisteredBlockItemPlacesItsOwnRegisteredBlock() {
        requireLoadedMod();
        for (String path : CONTRACTS.getFirst().paths()) {
            ResourceLocation id = id(path);
            var block = BuiltInRegistries.BLOCK.get(id);
            BlockItem item = assertInstanceOf(BlockItem.class, BuiltInRegistries.ITEM.get(id),
                    "方块对应物品不是 BlockItem：" + id);
            assertSame(block, item.getBlock(), "BlockItem 绑定了别的方块：" + id);
        }
    }

    @Test
    void everyBlockEntityTypeIsBoundToItsOwnAe2Block() {
        requireLoadedMod();
        for (String path : CONTRACTS.getFirst().paths()) {
            ResourceLocation id = id(path);
            AEBaseEntityBlock<?> block = assertInstanceOf(AEBaseEntityBlock.class,
                    BuiltInRegistries.BLOCK.get(id), "注册方块不是 AE2 实体方块：" + id);
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            assertNotNull(type, "方块实体类型未登记：" + id);
            assertSame(type, block.getBlockEntityType(), "AE2 bootstrap 未正确绑定实体类型：" + id);
            assertTrue(type.isValid(block.defaultBlockState()), "实体类型不接受自身方块：" + id);
        }
    }

    @Test
    void registeredMenusAreTheTypesUsedByTheirMenuImplementations() {
        requireLoadedMod();
        assertMenu("extreme_assembler", ChexsonsaeutilsContent.EXTREME_ASSEMBLER_MENU, ExtremeAssemblerMenu.TYPE);
        assertMenu("ae_direct_processing_machine", ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_MENU,
                AEDirectProcessingMachineMenu.TYPE);
        assertMenu("ae2_parallel_cpu_tool_cpu", ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_CPU_MENU,
                ParallelCraftingCPUMenu.TYPE);
        assertMenu("custom_pattern_provider", ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_MENU,
                CustomPatternProviderMenu.TYPE);
        assertMenu("custom_pattern_encoder", ChexsonsaeutilsContent.CUSTOM_PATTERN_ENCODER_MENU,
                CustomPatternEncoderMenu.TYPE);
        assertMenu("custom_pattern_upgrade", ChexsonsaeutilsContent.CUSTOM_PATTERN_UPGRADE_MENU,
                CustomPatternUpgradeMenu.TYPE);
        assertTrue(MultiLevelEmitterMenu.hasRegisteredMenuBindings(), "发射器菜单 bootstrap 未完成");
        assertMenu("multi_level_emitter_menu", ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_MENU,
                MultiLevelEmitterMenu.registeredMenuType());
        assertMenu("matter_mass_pattern_provider",
                MatterMassPatternProviderMenuRegistration.MATTER_MASS_PATTERN_PROVIDER_MENU,
                MatterMassPatternProviderMenu.TYPE);
        assertMenu("matter_mass_view", MatterMassRegistration.MATTER_MASS_VIEW_MENU, MatterMassViewMenuHost.TYPE);
    }

    /** 必须由既有 NeoForge 测试启动加载 mod；此处只验证前提，不自行注册或吞掉失败。 */
    private static void requireLoadedMod() {
        ModList mods = ModList.get();
        assertNotNull(mods, "NeoForge ModList 未建立；测试必须使用已配置的 forgejunitdev 启动入口");
        assertTrue(mods.isLoaded(ModIds.MODID), "NeoForge testedMod 未加载：" + ModIds.MODID);
    }

    /** 注册器逐个列出，直接调用公开 API，避免用反射探查字段或从源码猜测把手。 */
    private static List<DeferredRegister<?>> registrations() {
        return List.of(
                ChexsonsaeutilsContent.BLOCKS, ChexsonsaeutilsContent.ITEMS,
                ChexsonsaeutilsContent.BLOCK_ENTITY_TYPES, ChexsonsaeutilsContent.MENU_TYPES,
                DirectProcessingRecipeRegistration.RECIPE_TYPES, DirectProcessingRecipeRegistration.RECIPE_SERIALIZERS,
                ChexsonsaeutilsContent.CREATIVE_MODE_TABS, ChexsonsaeutilsContent.DATA_COMPONENT_TYPES,
                CustomPatternRegistration.ITEMS, CustomPatternRegistration.DATA_COMPONENT_TYPES,
                CustomPatternProviderPartRegistration.ITEMS,
                CustomPatternProviderRegistration.BLOCKS, CustomPatternProviderRegistration.ITEMS,
                CustomPatternProviderBlockEntityRegistration.BLOCK_ENTITY_TYPES,
                MatterMassPatternRegistration.ITEMS, MatterMassPatternRegistration.DATA_COMPONENT_TYPES,
                MatterMassPatternProviderMenuRegistration.MENU_TYPES,
                MatterMassRegistration.ITEMS, MatterMassRegistration.MENU_TYPES, MatterMassRegistration.DATA_COMPONENT_TYPES
        );
    }

    /** 数值 ID 是注册完成后的顺序证据；仅筛本 mod，不依赖其他 mod 插入的具体数值。 */
    private static <T> List<ResourceLocation> registeredIds(Registry<T> registry) {
        return registry.keySet().stream()
                .filter(id -> ModIds.MODID.equals(id.getNamespace()))
                .sorted(Comparator.<ResourceLocation>comparingInt(id -> registry.getId(registry.get(id))))
                .toList();
    }

    private static List<ResourceLocation> ids(List<String> paths) {
        return paths.stream().map(RegistrationRuntimeContractTest::id).toList();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ModIds.MODID, path);
    }

    private static void assertMenu(String path, Supplier<? extends MenuType<?>> holder, MenuType<?> expected) {
        assertSame(expected, holder.get(), "菜单把手与实现 TYPE 不一致：" + path);
        assertSame(expected, BuiltInRegistries.MENU.get(id(path)), "菜单实现 TYPE 未正确登记：" + path);
    }
}
