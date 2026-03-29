package git.chexson.chexsonsaeutils.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleValidation;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotTagService;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.assertContains;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessingPatternReplacementExecutionTest {
    private static final Path MAIN_CLASS = javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java");
    private static final Path DECODER_CLASS = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ProcessingPatternReplacementDecoder.java");
    private static final Path DECODER_ACCESSOR = javaSource(
            "git/chexson/chexsonsaeutils/mixin/ae2/crafting/PatternDetailsHelperAccessor.java");
    private static final Path WRAPPER_CLASS = javaSource(
            "git/chexson/chexsonsaeutils/pattern/replacement/ReplacementAwareProcessingPattern.java");
    private static final ResourceLocation SOURCE_TAG_ALPHA = id("chexsonsaeutils", "source/alpha");
    private static final ResourceLocation SOURCE_TAG_BETA = id("chexsonsaeutils", "source/beta");
    private static final ResourceLocation SOURCE_TAG_GAMMA = id("chexsonsaeutils", "source/gamma");
    private static final ResourceLocation STONE_ID = id("minecraft", "stone");
    private static final ResourceLocation ANDESITE_ID = id("minecraft", "andesite");
    private static final ResourceLocation COBBLESTONE_ID = id("minecraft", "cobblestone");
    private static final ResourceLocation DIORITE_ID = id("minecraft", "diorite");
    private static final ResourceLocation DIRT_ID = id("minecraft", "dirt");

    @Test
    void decoderReturnsWrapperOnlyWhenReplacementMetadataExists() throws IOException {
        assertContains(DECODER_CLASS, "chexsonsaeutils_processing_replacements");
        assertContains(DECODER_CLASS, "new ReplacementAwareProcessingPattern");
        assertContains(DECODER_CLASS, "return null;");
    }

    @Test
    void originalInputRemainsPrimaryCandidate() {
        List<ResourceLocation> possibleItemIds = resolvePossibleItemIds(
                createRule(orderedSet(SOURCE_TAG_ALPHA), orderedSet(DIORITE_ID))
        );

        assertEquals(STONE_ID, possibleItemIds.get(0));
    }

    @Test
    void buildPossibleItemIdsPreservesOriginalFirstWhileExcludingIllegalCandidates() {
        List<ResourceLocation> possibleItemIds = resolvePossibleItemIds(
                createRule(
                        orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA, SOURCE_TAG_GAMMA),
                        orderedSet(DIORITE_ID, COBBLESTONE_ID, DIRT_ID, COBBLESTONE_ID)
                )
        );

        assertEquals(List.of(STONE_ID, ANDESITE_ID, COBBLESTONE_ID, DIORITE_ID), possibleItemIds);
    }

    @Test
    void invalidCandidatesNeverAppearInPossibleInputs() {
        List<ResourceLocation> possibleItemIds = resolvePossibleItemIds(
                createRule(
                        orderedSet(SOURCE_TAG_GAMMA),
                        orderedSet(DIRT_ID)
                )
        );

        assertEquals(List.of(STONE_ID), possibleItemIds);
    }

    @Test
    void pushInputsToExternalInventoryRejectsIrrelevantCandidates() throws Exception {
        DummyKey primary = new DummyKey("stone");
        DummyKey substitute = new DummyKey("andesite");
        DummyKey irrelevant = new DummyKey("dirt");
        KeyCounter availableInputs = new KeyCounter();
        availableInputs.add(primary, 1L);
        availableInputs.add(substitute, 1L);
        availableInputs.add(irrelevant, 64L);

        List<PushRecord> pushed = new ArrayList<>();
        invokePushInputToSink(
                new TestInput(primary, substitute),
                availableInputs,
                1L,
                (key, amount) -> pushed.add(new PushRecord(key, amount)),
                0
        );

        assertEquals(List.of(new PushRecord(primary, 1L)), pushed);
    }

    @Test
    void partiallyInvalidRuleStillProducesOnlyValidRemainingCandidates() throws Exception {
        List<ResourceLocation> possibleItemIds = resolvePossibleItemIds(
                createRule(
                        orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_GAMMA),
                        orderedSet(COBBLESTONE_ID, DIRT_ID)
                )
        );

        assertEquals(List.of(STONE_ID, ANDESITE_ID, COBBLESTONE_ID), possibleItemIds);

        DummyKey primary = new DummyKey("stone");
        DummyKey substitute = new DummyKey("andesite");
        DummyKey shared = new DummyKey("cobblestone");
        DummyKey irrelevant = new DummyKey("dirt");
        KeyCounter availableInputs = new KeyCounter();
        availableInputs.add(irrelevant, 64L);
        availableInputs.add(shared, 1L);

        List<PushRecord> pushed = new ArrayList<>();
        invokePushInputToSink(
                new TestInput(primary, substitute, shared),
                availableInputs,
                1L,
                (key, amount) -> pushed.add(new PushRecord(key, amount)),
                0
        );

        assertEquals(List.of(new PushRecord(shared, 1L)), pushed);
    }

    @Test
    void pushInputsToExternalInventoryFailsWhenOnlyIrrelevantCandidateIsAvailable() {
        DummyKey primary = new DummyKey("stone");
        DummyKey substitute = new DummyKey("andesite");
        DummyKey irrelevant = new DummyKey("dirt");
        KeyCounter availableInputs = new KeyCounter();
        availableInputs.add(irrelevant, 64L);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> invokePushInputToSink(
                        new TestInput(primary, substitute),
                        availableInputs,
                        1L,
                        (key, amount) -> {
                        },
                        5
                )
        );

        assertEquals("Expected a valid input for processing slot 5, but none was available", error.getMessage());
    }

    @Test
    void commonSetupRegistersProcessingPatternReplacementDecoder() throws IOException {
        assertContains(DECODER_ACCESSOR, "interface PatternDetailsHelperAccessor");
        assertContains(DECODER_ACCESSOR, "chexsonsaeutils$getDecoders()");
        assertContains(MAIN_CLASS, "registerProcessingPatternReplacementDecoder");
        assertContains(MAIN_CLASS, "decoders.add(0, decoder);");
        assertContains(MAIN_CLASS, "decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass())");
        assertContains(WRAPPER_CLASS, "pushInputsToExternalInventory");
    }

    private static void invokePushInputToSink(
            IPatternDetails.IInput input,
            KeyCounter availableInputs,
            long requiredAmount,
            IPatternDetails.PatternInputSink sink,
            int sparseSlot
    ) throws Exception {
        Method method = ReplacementAwareProcessingPattern.class.getDeclaredMethod(
                "pushInputToSink",
                IPatternDetails.IInput.class,
                KeyCounter.class,
                long.class,
                IPatternDetails.PatternInputSink.class,
                int.class
        );
        method.setAccessible(true);
        try {
            method.invoke(null, input, availableInputs, requiredAmount, sink, sparseSlot);
        } catch (InvocationTargetException invocationTargetException) {
            Throwable cause = invocationTargetException.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw invocationTargetException;
        }
    }

    private static List<ResourceLocation> resolvePossibleItemIds(ProcessingPatternSlotReplacementRule rule) {
        ProcessingSlotTagService service = createService();
        return ReplacementAwareProcessingPattern.buildPossibleItemIds(
                STONE_ID,
                List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                rule,
                service,
                new ProcessingSlotRuleValidation(service)
        );
    }

    private static ProcessingPatternSlotReplacementRule createRule(
            Set<ResourceLocation> selectedTagIds,
            Set<ResourceLocation> explicitCandidateIds
    ) {
        return new ProcessingPatternSlotReplacementRule(0, STONE_ID, selectedTagIds, explicitCandidateIds);
    }

    private static ProcessingSlotTagService createService() {
        Map<ResourceLocation, List<ResourceLocation>> tagMembers = orderedMap(
                SOURCE_TAG_ALPHA, List.of(ANDESITE_ID, COBBLESTONE_ID),
                SOURCE_TAG_BETA, List.of(COBBLESTONE_ID, DIORITE_ID)
        );
        Map<ResourceLocation, Set<ResourceLocation>> itemTags = orderedMap(
                ANDESITE_ID, orderedSet(SOURCE_TAG_ALPHA),
                COBBLESTONE_ID, orderedSet(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                DIORITE_ID, orderedSet(SOURCE_TAG_BETA),
                DIRT_ID, orderedSet(SOURCE_TAG_GAMMA)
        );
        return new ProcessingSlotTagService(
                ignored -> List.of(SOURCE_TAG_ALPHA, SOURCE_TAG_BETA),
                tagId -> tagMembers.getOrDefault(tagId, List.of()),
                itemId -> itemTags.getOrDefault(itemId, Set.of())
        );
    }

    private static <K, V> Map<K, V> orderedMap(Object... entries) {
        Map<K, V> ordered = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) entries[i];
            @SuppressWarnings("unchecked")
            V value = (V) entries[i + 1];
            ordered.put(key, value);
        }
        return ordered;
    }

    @SafeVarargs
    private static <T> Set<T> orderedSet(T... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private record PushRecord(AEKey key, long amount) {
    }

    private record TestInput(GenericStack[] possibleInputs) implements IPatternDetails.IInput {
        private TestInput(AEKey... possibleKeys) {
            this(java.util.Arrays.stream(possibleKeys)
                    .map(key -> new GenericStack(key, 1L))
                    .toArray(GenericStack[]::new));
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (GenericStack possibleInput : possibleInputs) {
                if (input.matches(possibleInput)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static final class DummyKeyType extends AEKeyType {
        private static final DummyKeyType INSTANCE = new DummyKeyType();

        private DummyKeyType() {
            super(Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:execution-test")),
                    DummyKey.class,
                    Component.literal("ExecutionTest"));
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            return null;
        }
    }

    private static final class DummyKey extends AEKey {
        private final String id;

        private DummyKey(String id) {
            this.id = id;
        }

        @Override
        public AEKeyType getType() {
            return DummyKeyType.INSTANCE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:" + id));
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DummyKey dummyKey)) {
                return false;
            }
            return Objects.equals(id, dummyKey.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
