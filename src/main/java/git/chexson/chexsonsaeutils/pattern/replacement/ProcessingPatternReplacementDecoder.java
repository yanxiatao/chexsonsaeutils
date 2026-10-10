package git.chexson.chexsonsaeutils.pattern.replacement;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ProcessingPatternReplacementDecoder implements IPatternDetailsDecoder {
    /**
     * 解码重入标记：{@link #decodePattern} 委托
     * {@code PatternDetailsHelper.decodePattern} 取原始样板，而后者会遍历
     * 全部已注册 decoder（含本类）——标记生效期间本类返回 null，
     * 让 AEProcessingPatternDecoder 或第三方 decoder 接管底层解码。
     */
    private static final ThreadLocal<Boolean> DECODING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ProcessingPatternReplacementPersistence persistence;
    private final ProcessingSlotTagExpander tagService;
    private final ProcessingSlotRuleValidation validation;

    public ProcessingPatternReplacementDecoder() {
        this(new ProcessingSlotTagExpander(), new ProcessingSlotRuleValidation());
    }

    public ProcessingPatternReplacementDecoder(
            ProcessingSlotTagExpander tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this(new ProcessingPatternReplacementPersistence(tagService, validation), tagService, validation);
    }

    public ProcessingPatternReplacementDecoder(
            ProcessingPatternReplacementPersistence persistence,
            ProcessingSlotTagExpander tagService,
            ProcessingSlotRuleValidation validation
    ) {
        this.persistence = Objects.requireNonNull(persistence);
        this.tagService = Objects.requireNonNull(tagService);
        this.validation = Objects.requireNonNull(validation);
    }

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        // Phase 3 去门：任何携带替换元数据的编码样板都被本 decoder 截获，
        // 不再要求 AE2 原生处理样板 payload——第三方样板同样拿得到替换能力。
        return stack != null
                && !stack.isEmpty()
                && persistence.hasReplacementMetadata(stack);
    }

    @Override
    public @Nullable IPatternDetails decodePattern(AEItemKey definition, Level level) {
        CompoundTag patternTag = getPatternTag(definition);
        if (definition == null || !persistence.hasReplacementMetadata(patternTag)) {
            return null;
        }
        if (DECODING.get()) {
            // 委托解码的重入：跳过，交底层 decoder 产出原始样板
            return null;
        }

        DECODING.set(Boolean.TRUE);
        try {
            IPatternDetails delegate = PatternDetailsHelper.decodePattern(definition, level);
            if (delegate == null) {
                return null;
            }
            return new ReplacementAwarePattern(
                    delegate,
                    persistence.readRules(patternTag),
                    tagService,
                    validation
            );
        } finally {
            DECODING.set(Boolean.FALSE);
        }
    }

    @Override
    public @Nullable IPatternDetails decodePattern(ItemStack stack, Level level) {
        if (!isEncodedPattern(stack)) {
            return null;
        }

        AEItemKey definition = AEItemKey.of(stack);
        return definition == null ? null : decodePattern(definition, level);
    }

    private static @Nullable CompoundTag getPatternTag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? null : customData.copyTag();
    }

    private static @Nullable CompoundTag getPatternTag(AEItemKey definition) {
        if (definition == null) {
            return null;
        }
        CustomData customData = definition.get(DataComponents.CUSTOM_DATA);
        return customData == null ? null : customData.copyTag();
    }
}
