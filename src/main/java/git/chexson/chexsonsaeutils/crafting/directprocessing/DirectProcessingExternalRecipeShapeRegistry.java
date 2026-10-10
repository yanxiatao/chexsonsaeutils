package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DirectProcessingExternalRecipeShapeRegistry {

    private final List<ExternalRecipeShapeSupport> shapeSupports;

    public DirectProcessingExternalRecipeShapeRegistry(List<ExternalRecipeShapeSupport> shapeSupports) {
        this.shapeSupports = List.copyOf(shapeSupports);
    }

    @Nullable
    public ShapeResult readShape(@Nullable RecipeTypeCandidate candidate, @Nullable Recipe<?> recipe) {
        if (candidate == null || recipe == null) {
            return null;
        }
        for (ExternalRecipeShapeSupport support : shapeSupports) {
            ShapeResult result = support.readShape(candidate, recipe);
            if (result != null && result.handled()) {
                return result;
            }
        }
        return null;
    }

    /** 第三方配方形状读取契约，允许核心按装配顺序消费支持能力而不依赖模组实现。 */
    public interface ExternalRecipeShapeSupport {
        /**
         * 读取候选配方的输入输出；未处理时返回 null 或 unhandled，处理但不可读时返回 unreadable。
         * 已处理结果会终止后续支持器，避免将已知不可读配方误交给其他支持器。
         */
        @Nullable
        ShapeResult readShape(RecipeTypeCandidate candidate, Recipe<?> recipe);
    }

    public record ShapeResult(
            boolean handled,
            boolean supported,
            List<List<appeng.api.stacks.GenericStack>> inputChoices,
            List<appeng.api.stacks.GenericStack> outputs
    ) {
        public ShapeResult {
            inputChoices = inputChoices == null ? List.of() : List.copyOf(inputChoices);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        public static ShapeResult unhandled() {
            return new ShapeResult(false, false, List.of(), List.of());
        }

        public static ShapeResult unreadable() {
            return new ShapeResult(true, false, List.of(), List.of());
        }

        public static ShapeResult supported(
                List<List<appeng.api.stacks.GenericStack>> inputChoices,
                List<appeng.api.stacks.GenericStack> outputs
        ) {
            return new ShapeResult(true, true, inputChoices, outputs);
        }
    }
}
