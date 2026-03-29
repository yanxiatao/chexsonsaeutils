package git.chexson.chexsonsaeutils.pattern.replacement;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ProcessingPatternReplacementDecoder implements IPatternDetailsDecoder {
    private static final String INPUTS_TAG = "in";
    private static final String OUTPUTS_TAG = "out";

    private final ProcessingPatternReplacementPersistence persistence;
    private final ProcessingSlotTagService tagService;
    private final ProcessingSlotRuleValidation validation;

    public ProcessingPatternReplacementDecoder() {
        this(new ProcessingSlotTagService(), new ProcessingSlotRuleValidation());
    }

    public ProcessingPatternReplacementDecoder(
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this(new ProcessingPatternReplacementPersistence(tagService, validation), tagService, validation);
    }

    public ProcessingPatternReplacementDecoder(
            ProcessingPatternReplacementPersistence persistence,
            ProcessingSlotTagService tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this.persistence = Objects.requireNonNull(persistence);
        this.tagService = Objects.requireNonNull(tagService);
        this.validation = Objects.requireNonNull(validation);
    }

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        // Only intercept encoded stacks carrying our replacement metadata: chexsonsaeutils_processing_replacements.
        return stack != null
                && !stack.isEmpty()
                && persistence.hasReplacementMetadata(stack)
                && hasProcessingPatternPayload(stack.getTag());
    }

    @Override
    public @Nullable IPatternDetails decodePattern(AEItemKey definition, Level level) {
        if (definition == null
                || !persistence.hasReplacementMetadata(definition.getTag())
                || !hasProcessingPatternPayload(definition.getTag())) {
            return null;
        }

        return new ReplacementAwareProcessingPattern(
                definition,
                persistence.readRules(definition.getTag()),
                tagService,
                validation
        );
    }

    @Override
    public @Nullable IPatternDetails decodePattern(ItemStack stack, Level level, boolean recursive) {
        if (!isEncodedPattern(stack)) {
            return null;
        }

        AEItemKey definition = AEItemKey.of(stack);
        return definition == null ? null : decodePattern(definition, level);
    }

    private static boolean hasProcessingPatternPayload(@Nullable CompoundTag patternTag) {
        return patternTag != null
                && patternTag.contains(INPUTS_TAG, Tag.TAG_LIST)
                && patternTag.contains(OUTPUTS_TAG, Tag.TAG_LIST);
    }
}
