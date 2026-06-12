package git.chexson.chexsonsaeutils.gametest.crafting;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CraftingPatternDataset {

    private static final WoodFamily JUNGLE = new WoodFamily(
            "jungle",
            Items.JUNGLE_PLANKS,
            Items.JUNGLE_BUTTON,
            Items.JUNGLE_PRESSURE_PLATE,
            Items.JUNGLE_SLAB,
            Items.JUNGLE_STAIRS,
            Items.JUNGLE_DOOR,
            Items.JUNGLE_TRAPDOOR,
            Items.JUNGLE_FENCE,
            Items.JUNGLE_FENCE_GATE
    );
    private static final WoodFamily ACACIA = new WoodFamily(
            "acacia",
            Items.ACACIA_PLANKS,
            Items.ACACIA_BUTTON,
            Items.ACACIA_PRESSURE_PLATE,
            Items.ACACIA_SLAB,
            Items.ACACIA_STAIRS,
            Items.ACACIA_DOOR,
            Items.ACACIA_TRAPDOOR,
            Items.ACACIA_FENCE,
            Items.ACACIA_FENCE_GATE
    );
    private static final WoodFamily DARK_OAK = new WoodFamily(
            "dark_oak",
            Items.DARK_OAK_PLANKS,
            Items.DARK_OAK_BUTTON,
            Items.DARK_OAK_PRESSURE_PLATE,
            Items.DARK_OAK_SLAB,
            Items.DARK_OAK_STAIRS,
            Items.DARK_OAK_DOOR,
            Items.DARK_OAK_TRAPDOOR,
            Items.DARK_OAK_FENCE,
            Items.DARK_OAK_FENCE_GATE
    );
    private static final WoodFamily MANGROVE = new WoodFamily(
            "mangrove",
            Items.MANGROVE_PLANKS,
            Items.MANGROVE_BUTTON,
            Items.MANGROVE_PRESSURE_PLATE,
            Items.MANGROVE_SLAB,
            Items.MANGROVE_STAIRS,
            Items.MANGROVE_DOOR,
            Items.MANGROVE_TRAPDOOR,
            Items.MANGROVE_FENCE,
            Items.MANGROVE_FENCE_GATE
    );
    private static final WoodFamily CHERRY = new WoodFamily(
            "cherry",
            Items.CHERRY_PLANKS,
            Items.CHERRY_BUTTON,
            Items.CHERRY_PRESSURE_PLATE,
            Items.CHERRY_SLAB,
            Items.CHERRY_STAIRS,
            Items.CHERRY_DOOR,
            Items.CHERRY_TRAPDOOR,
            Items.CHERRY_FENCE,
            Items.CHERRY_FENCE_GATE
    );

    private CraftingPatternDataset() {
    }

    public static List<EncodedPatternSpec> smallMixedSet(Level level) {
        return List.of(
                encode(level, "oak_button", Items.OAK_BUTTON, grid(
                        Items.OAK_PLANKS, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "oak_pressure_plate", Items.OAK_PRESSURE_PLATE, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "oak_slab", Items.OAK_SLAB, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "bowl", Items.BOWL, grid(
                        Items.OAK_PLANKS, null, Items.OAK_PLANKS,
                        null, Items.OAK_PLANKS, null,
                        null, null, null
                )),
                encode(level, "crafting_table", Items.CRAFTING_TABLE, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        null, null, null
                )),
                encode(level, "oak_stairs", Items.OAK_STAIRS, grid(
                        Items.OAK_PLANKS, null, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS
                )),
                encode(level, "oak_door", Items.OAK_DOOR, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null
                )),
                encode(level, "oak_trapdoor", Items.OAK_TRAPDOOR, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, null, null
                )),
                encode(level, "chest", Items.CHEST, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, null, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS
                )),
                encode(level, "oak_fence", Items.OAK_FENCE, grid(
                        Items.OAK_PLANKS, Items.STICK, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.STICK, Items.OAK_PLANKS,
                        null, null, null
                )),
                encode(level, "oak_fence_gate", Items.OAK_FENCE_GATE, grid(
                        Items.STICK, Items.OAK_PLANKS, Items.STICK,
                        Items.STICK, Items.OAK_PLANKS, Items.STICK,
                        null, null, null
                )),
                encode(level, "oak_boat", Items.OAK_BOAT, grid(
                        null, null, null,
                        Items.OAK_PLANKS, null, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS
                ))
        );
    }

    public static List<EncodedPatternSpec> chainedSet(Level level) {
        return List.of(
                encode(level, "oak_planks_from_log", Items.OAK_PLANKS, grid(
                        Items.OAK_LOG, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "sticks_from_oak_planks", Items.STICK, grid(
                        null, Items.OAK_PLANKS, null,
                        null, Items.OAK_PLANKS, null,
                        null, null, null
                )),
                encode(level, "ladder_from_sticks", Items.LADDER, grid(
                        Items.STICK, null, Items.STICK,
                        Items.STICK, Items.STICK, Items.STICK,
                        Items.STICK, null, Items.STICK
                )),
                encode(level, "oak_sign_from_planks_and_stick", Items.OAK_SIGN, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, Items.STICK, null
                )),
                encode(level, "spruce_planks_from_log", Items.SPRUCE_PLANKS, grid(
                        Items.SPRUCE_LOG, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "spruce_stairs", Items.SPRUCE_STAIRS, grid(
                        Items.SPRUCE_PLANKS, null, null,
                        Items.SPRUCE_PLANKS, Items.SPRUCE_PLANKS, null,
                        Items.SPRUCE_PLANKS, Items.SPRUCE_PLANKS, Items.SPRUCE_PLANKS
                )),
                encode(level, "birch_planks_from_log", Items.BIRCH_PLANKS, grid(
                        Items.BIRCH_LOG, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "birch_door", Items.BIRCH_DOOR, grid(
                        Items.BIRCH_PLANKS, Items.BIRCH_PLANKS, null,
                        Items.BIRCH_PLANKS, Items.BIRCH_PLANKS, null,
                        Items.BIRCH_PLANKS, Items.BIRCH_PLANKS, null
                ))
        );
    }

    public static List<EncodedPatternSpec> smallMixedChainedSet(Level level) {
        List<EncodedPatternSpec> patterns = new ArrayList<>(smallMixedSet(level));
        patterns.addAll(chainedSet(level));
        return List.copyOf(patterns);
    }

    public static List<EncodedPatternSpec> largeMixedChainedSet(Level level) {
        List<EncodedPatternSpec> patterns = new ArrayList<>(60);
        patterns.addAll(smallMixedSet(level));
        patterns.addAll(familyIndependentSet(level, JUNGLE));
        patterns.addAll(familyIndependentSet(level, ACACIA));
        patterns.addAll(familyIndependentSet(level, DARK_OAK));
        patterns.addAll(familyIndependentSet(level, MANGROVE));
        patterns.addAll(familyIndependentSet(level, CHERRY));
        patterns.addAll(chainedSet(level));
        return List.copyOf(patterns);
    }

    public static List<EncodedPatternSpec> ae2NativePatternSet(ServerLevel level) {
        List<EncodedPatternSpec> patterns = new ArrayList<>();
        patterns.add(new EncodedPatternSpec(
                "oak_slab_substitute_off",
                encodeCraftingPattern(
                        level,
                        new Object[]{
                                Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                                null, null, null,
                                null, null, null
                        },
                        false,
                        false
                ),
                AEItemKey.of(Items.OAK_SLAB)
        ));
        patterns.add(new EncodedPatternSpec(
                "oak_slab_substitute_on",
                encodeCraftingPattern(
                        level,
                        new Object[]{
                                Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                                null, null, null,
                                null, null, null
                        },
                        true,
                        false
                ),
                AEItemKey.of(Items.OAK_SLAB)
        ));
        patterns.add(new EncodedPatternSpec(
                "stonecutting_stone_bricks",
                encodeStonecutterPattern(level, Items.STONE, Items.STONE_BRICKS, false),
                AEItemKey.of(Items.STONE_BRICKS)
        ));
        patterns.add(new EncodedPatternSpec(
                "stonecutting_stone_brick_stairs",
                encodeStonecutterPattern(level, Items.STONE, Items.STONE_BRICK_STAIRS, false),
                AEItemKey.of(Items.STONE_BRICK_STAIRS)
        ));
        patterns.add(new EncodedPatternSpec(
                "smithing_netherite_sword",
                encodeSmithingPattern(
                        level,
                        Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                        Items.DIAMOND_SWORD,
                        Items.NETHERITE_INGOT,
                        false
                ),
                AEItemKey.of(Items.NETHERITE_SWORD)
        ));
        return List.copyOf(patterns);
    }

    public static List<AEItemKey> chainEndpoints() {
        return List.of(
                AEItemKey.of(Items.LADDER),
                AEItemKey.of(Items.OAK_SIGN),
                AEItemKey.of(Items.SPRUCE_STAIRS),
                AEItemKey.of(Items.BIRCH_DOOR)
        );
    }

    public static List<AEItemKey> outputKeys(List<EncodedPatternSpec> specs) {
        LinkedHashSet<AEItemKey> keys = new LinkedHashSet<>();
        for (EncodedPatternSpec spec : specs) {
            keys.add(spec.outputKey());
        }
        return List.copyOf(keys);
    }

    public static List<AEItemKey> ae2NativeOutputs() {
        return List.of(
                AEItemKey.of(Items.OAK_SLAB),
                AEItemKey.of(Items.STONE_BRICKS),
                AEItemKey.of(Items.STONE_BRICK_STAIRS),
                AEItemKey.of(Items.NETHERITE_SWORD)
        );
    }

    public static List<ItemStack> patternsOnly(List<EncodedPatternSpec> specs) {
        List<ItemStack> patterns = new ArrayList<>(specs.size());
        for (EncodedPatternSpec spec : specs) {
            patterns.add(spec.encodedPattern().copy());
        }
        return List.copyOf(patterns);
    }

    public static List<GenericStack> chainPlanningSeedInputs() {
        return List.of(
                new GenericStack(AEItemKey.of(Items.OAK_LOG), 16),
                new GenericStack(AEItemKey.of(Items.SPRUCE_LOG), 8),
                new GenericStack(AEItemKey.of(Items.BIRCH_LOG), 8)
        );
    }

    public static List<EncodedPatternSpec> woodenPickaxePlanningSet(Level level) {
        return List.of(
                encode(level, "oak_planks_from_log", Items.OAK_PLANKS, grid(
                        Items.OAK_LOG, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "sticks_from_oak_planks", Items.STICK, grid(
                        null, Items.OAK_PLANKS, null,
                        null, Items.OAK_PLANKS, null,
                        null, null, null
                )),
                encode(level, "wooden_pickaxe", Items.WOODEN_PICKAXE, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, Items.STICK, null,
                        null, Items.STICK, null
                ))
        );
    }

    public static List<EncodedPatternSpec> deepLecternPlanningSet(Level level) {
        return List.of(
                encode(level, "oak_planks_from_log", Items.OAK_PLANKS, grid(
                        Items.OAK_LOG, null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "oak_slab", Items.OAK_SLAB, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "paper_from_sugar_cane", Items.PAPER, grid(
                        Items.SUGAR_CANE, Items.SUGAR_CANE, Items.SUGAR_CANE,
                        null, null, null,
                        null, null, null
                )),
                encode(level, "book_from_paper_leather", Items.BOOK, grid(
                        Items.PAPER, Items.PAPER, Items.PAPER,
                        Items.LEATHER, null, null,
                        null, null, null
                )),
                encode(level, "bookshelf_from_books_planks", Items.BOOKSHELF, grid(
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        Items.BOOK, Items.BOOK, Items.BOOK,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS
                )),
                encode(level, "lectern_from_bookshelf_and_slabs", Items.LECTERN, grid(
                        Items.OAK_SLAB, Items.OAK_SLAB, Items.OAK_SLAB,
                        null, Items.BOOKSHELF, null,
                        null, Items.OAK_SLAB, null
                ))
        );
    }

    public static List<GenericStack> woodenPickaxePlanningSeedInputs(long oakLogCount) {
        return List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), Math.max(1L, oakLogCount)));
    }

    private static List<EncodedPatternSpec> familyIndependentSet(Level level, WoodFamily family) {
        return List.of(
                encode(level, family.id() + "_button", family.button(), grid(
                        family.planks(), null, null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, family.id() + "_pressure_plate", family.pressurePlate(), grid(
                        family.planks(), family.planks(), null,
                        null, null, null,
                        null, null, null
                )),
                encode(level, family.id() + "_slab", family.slab(), grid(
                        family.planks(), family.planks(), family.planks(),
                        null, null, null,
                        null, null, null
                )),
                encode(level, family.id() + "_stairs", family.stairs(), grid(
                        family.planks(), null, null,
                        family.planks(), family.planks(), null,
                        family.planks(), family.planks(), family.planks()
                )),
                encode(level, family.id() + "_door", family.door(), grid(
                        family.planks(), family.planks(), null,
                        family.planks(), family.planks(), null,
                        family.planks(), family.planks(), null
                )),
                encode(level, family.id() + "_trapdoor", family.trapdoor(), grid(
                        family.planks(), family.planks(), family.planks(),
                        family.planks(), family.planks(), family.planks(),
                        null, null, null
                )),
                encode(level, family.id() + "_fence", family.fence(), grid(
                        family.planks(), Items.STICK, family.planks(),
                        family.planks(), Items.STICK, family.planks(),
                        null, null, null
                )),
                encode(level, family.id() + "_fence_gate", family.fenceGate(), grid(
                        Items.STICK, family.planks(), Items.STICK,
                        Items.STICK, family.planks(), Items.STICK,
                        null, null, null
                ))
        );
    }

    private static EncodedPatternSpec encode(Level level, String id, ItemLike output, ItemStack[] grid) {
        ItemStack encodedPattern = AbstractHighCapacityCraftingHostBlockEntity.encodeCraftingPatternForTest(level, grid);
        if (encodedPattern.isEmpty()) {
            throw new IllegalStateException("Failed to encode crafting pattern for dataset entry " + id);
        }
        return new EncodedPatternSpec(id, encodedPattern, AEItemKey.of(output));
    }

    private static ItemStack[] grid(ItemLike slot0, ItemLike slot1, ItemLike slot2,
                                    ItemLike slot3, ItemLike slot4, ItemLike slot5,
                                    ItemLike slot6, ItemLike slot7, ItemLike slot8) {
        return new ItemStack[]{
                stack(slot0), stack(slot1), stack(slot2),
                stack(slot3), stack(slot4), stack(slot5),
                stack(slot6), stack(slot7), stack(slot8)
        };
    }

    private static ItemStack stack(ItemLike itemLike) {
        return itemLike == null ? ItemStack.EMPTY : new ItemStack(itemLike);
    }

    private static ItemStack encodeCraftingPattern(
            ServerLevel level,
            Object[] ingredients,
            boolean allowSubstitutions,
            boolean allowFluidSubstitutions
    ) {
        ItemStack[] stacks = new ItemStack[9];
        for (int i = 0; i < stacks.length; i++) {
            Object ingredient = i < ingredients.length ? ingredients[i] : null;
            if (ingredient instanceof ItemLike itemLike) {
                stacks[i] = new ItemStack(itemLike);
            } else if (ingredient instanceof ItemStack itemStack) {
                stacks[i] = itemStack.copy();
            } else {
                stacks[i] = ItemStack.EMPTY;
            }
        }
        NonNullList<ItemStack> grid = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            grid.set(i, stacks[i]);
        }
        CraftingInput recipeInput = CraftingInput.of(3, 3, grid);
        RecipeHolder<CraftingRecipe> recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, recipeInput, level)
                .orElseThrow(() -> new IllegalStateException("missing crafting recipe for native dataset"));
        ItemStack result = recipe.value().assemble(recipeInput, level.registryAccess());
        return PatternDetailsHelper.encodeCraftingPattern(
                recipe,
                stacks,
                result,
                allowSubstitutions,
                allowFluidSubstitutions
        );
    }

    private static ItemStack encodeStonecutterPattern(
            Level level,
            ItemLike inputItem,
            ItemLike outputItem,
            boolean allowSubstitutes
    ) {
        SingleRecipeInput input = new SingleRecipeInput(new ItemStack(inputItem));
        RecipeHolder<StonecutterRecipe> foundRecipe = null;
        for (RecipeHolder<StonecutterRecipe> holder : level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, input, level)) {
            StonecutterRecipe recipe = holder.value();
            if (recipe.getResultItem(level.registryAccess()).is(outputItem.asItem())) {
                foundRecipe = holder;
                break;
            }
        }
        if (foundRecipe == null) {
            throw new IllegalStateException("missing stonecutter recipe for native dataset");
        }
        return PatternDetailsHelper.encodeStonecuttingPattern(
                foundRecipe,
                AEItemKey.of(inputItem),
                AEItemKey.of(outputItem),
                allowSubstitutes
        );
    }

    private static ItemStack encodeSmithingPattern(
            Level level,
            ItemLike template,
            ItemLike base,
            ItemLike addition,
            boolean allowSubstitutes
    ) {
        SmithingRecipeInput input = new SmithingRecipeInput(new ItemStack(template), new ItemStack(base), new ItemStack(addition));
        RecipeHolder<SmithingRecipe> foundRecipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMITHING, input, level)
                .orElseThrow(() -> new IllegalStateException("missing smithing recipe for native dataset"));
        ItemStack result = foundRecipe.value().assemble(input, level.registryAccess());
        return PatternDetailsHelper.encodeSmithingTablePattern(
                foundRecipe,
                AEItemKey.of(template),
                AEItemKey.of(base),
                AEItemKey.of(addition),
                AEItemKey.of(result),
                allowSubstitutes
        );
    }

    public record EncodedPatternSpec(String id, ItemStack encodedPattern, AEItemKey outputKey) {
    }

    private record WoodFamily(
            String id,
            ItemLike planks,
            ItemLike button,
            ItemLike pressurePlate,
            ItemLike slab,
            ItemLike stairs,
            ItemLike door,
            ItemLike trapdoor,
            ItemLike fence,
            ItemLike fenceGate
    ) {
    }
}
