package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record FormalMachineAggregationStep(
        ItemStack patternDefinition,
        long executionCount,
        List<GenericStack> stepInputs,
        GenericStack stepPrimaryOutput,
        List<GenericStack> stepRemainders,
        TaskCompletionRoute stepRoute
) {

    private static final String NBT_PATTERN_DEFINITION = "patternDefinition";
    private static final String NBT_EXECUTION_COUNT = "executionCount";
    private static final String NBT_STEP_INPUTS = "stepInputs";
    private static final String NBT_STEP_PRIMARY_OUTPUT = "stepPrimaryOutput";
    private static final String NBT_STEP_REMAINDERS = "stepRemainders";
    private static final String NBT_STEP_ROUTE = "stepRoute";

    public FormalMachineAggregationStep {
        patternDefinition = patternDefinition == null ? ItemStack.EMPTY : patternDefinition.copy();
        executionCount = Math.max(1L, executionCount);
        stepInputs = copyStacks(stepInputs);
        stepRemainders = copyStacks(stepRemainders);
        stepRoute = stepRoute == null ? TaskCompletionRoute.AE_STORAGE : stepRoute;
    }

    public FormalMachineAggregationStep withRoute(TaskCompletionRoute route) {
        return new FormalMachineAggregationStep(
                patternDefinition,
                executionCount,
                stepInputs,
                stepPrimaryOutput,
                stepRemainders,
                route
        );
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        if (!patternDefinition.isEmpty()) {
            tag.put(NBT_PATTERN_DEFINITION, patternDefinition.save(new CompoundTag()));
        }
        tag.putLong(NBT_EXECUTION_COUNT, executionCount);
        tag.put(NBT_STEP_INPUTS, writeStacks(stepInputs));
        if (stepPrimaryOutput != null) {
            tag.put(NBT_STEP_PRIMARY_OUTPUT, GenericStack.writeTag(stepPrimaryOutput));
        }
        tag.put(NBT_STEP_REMAINDERS, writeStacks(stepRemainders));
        tag.putString(NBT_STEP_ROUTE, stepRoute.name());
        return tag;
    }

    public static FormalMachineAggregationStep readFromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return new FormalMachineAggregationStep(
                    ItemStack.EMPTY,
                    1L,
                    List.of(),
                    null,
                    List.of(),
                    TaskCompletionRoute.AE_STORAGE
            );
        }
        return new FormalMachineAggregationStep(
                tag.contains(NBT_PATTERN_DEFINITION)
                        ? ItemStack.of(tag.getCompound(NBT_PATTERN_DEFINITION))
                        : ItemStack.EMPTY,
                Math.max(1L, tag.getLong(NBT_EXECUTION_COUNT)),
                readStacks(tag.getList(NBT_STEP_INPUTS, Tag.TAG_COMPOUND)),
                tag.contains(NBT_STEP_PRIMARY_OUTPUT)
                        ? GenericStack.readTag(tag.getCompound(NBT_STEP_PRIMARY_OUTPUT))
                        : null,
                readStacks(tag.getList(NBT_STEP_REMAINDERS, Tag.TAG_COMPOUND)),
                tag.contains(NBT_STEP_ROUTE)
                        ? TaskCompletionRoute.valueOf(tag.getString(NBT_STEP_ROUTE))
                        : TaskCompletionRoute.AE_STORAGE
        );
    }

    private static ListTag writeStacks(List<GenericStack> stacks) {
        ListTag listTag = new ListTag();
        if (stacks == null) {
            return listTag;
        }
        for (GenericStack stack : stacks) {
            if (stack != null) {
                listTag.add(GenericStack.writeTag(stack));
            }
        }
        return listTag;
    }

    private static List<GenericStack> readStacks(ListTag listTag) {
        List<GenericStack> stacks = new ArrayList<>();
        for (Tag entry : listTag) {
            if (entry instanceof CompoundTag compoundTag) {
                GenericStack stack = GenericStack.readTag(compoundTag);
                if (stack != null) {
                    stacks.add(stack);
                }
            }
        }
        return List.copyOf(stacks);
    }

    private static List<GenericStack> copyStacks(List<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<GenericStack> copied = new ArrayList<>(stacks.size());
        for (GenericStack stack : stacks) {
            if (stack != null) {
                copied.add(new GenericStack(stack.what(), stack.amount()));
            }
        }
        return List.copyOf(copied);
    }
}
