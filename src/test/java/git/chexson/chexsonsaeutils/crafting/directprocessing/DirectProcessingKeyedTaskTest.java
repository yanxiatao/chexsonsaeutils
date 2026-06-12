package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectProcessingKeyedTaskTest {

    @Test
    void deriveExecutionCountAcceptsCommonRatioAcrossDifferentKeyTypes() {
        GenericTestKey coolant = new GenericTestKey("coolant");
        GenericTestKey ore = new GenericTestKey("ore");

        Integer ratio = DirectProcessingStackSupport.deriveExecutionCount(
                List.of(
                        new RecipeSignatureInput(coolant, 8000L),
                        new RecipeSignatureInput(ore, 8L)
                ),
                List.of(
                        new RecipeSignatureInput(coolant, 1000L),
                        new RecipeSignatureInput(ore, 1L)
                )
        );

        assertEquals(8, ratio);
    }

    @Test
    void deriveExecutionCountRejectsInconsistentRatiosAcrossAnyKey() {
        GenericTestKey coolant = new GenericTestKey("coolant");
        GenericTestKey ore = new GenericTestKey("ore");

        Integer ratio = DirectProcessingStackSupport.deriveExecutionCount(
                List.of(
                        new RecipeSignatureInput(coolant, 2000L),
                        new RecipeSignatureInput(ore, 1L)
                ),
                List.of(
                        new RecipeSignatureInput(coolant, 1000L),
                        new RecipeSignatureInput(ore, 1L)
                )
        );

        assertNull(ratio);
    }

    @Test
    void normalizeStacksMergesSameKeyPayloadsAcrossMultipleBatches() {
        GenericTestKey slag = new GenericTestKey("slag");
        GenericTestKey steam = new GenericTestKey("steam");

        List<GenericStack> normalized = DirectProcessingStackSupport.normalizeStacks(List.of(
                new GenericStack(slag, 4L),
                new GenericStack(steam, 10L),
                new GenericStack(slag, 6L)
        ));

        assertEquals(2, normalized.size());
        assertTrue(normalized.contains(new GenericStack(slag, 10L)));
        assertTrue(normalized.contains(new GenericStack(steam, 10L)));
    }

    @Test
    void normalizeSignatureInputsMergesDuplicateKeys() {
        GenericTestKey ingot = new GenericTestKey("ingot");
        GenericTestKey circuit = new GenericTestKey("circuit");

        List<RecipeSignatureInput> normalized = DirectProcessingStackSupport.normalizeSignatureInputs(List.of(
                new RecipeSignatureInput(ingot, 1L),
                new RecipeSignatureInput(circuit, 1L),
                new RecipeSignatureInput(ingot, 1L)
        ));

        assertEquals(List.of(
                new RecipeSignatureInput(circuit, 1L),
                new RecipeSignatureInput(ingot, 2L)
        ), normalized);
    }

    @Test
    void scaledMatchAcceptsRepeatedInputsAndScaledOutputs() {
        GenericTestKey ingot = new GenericTestKey("ingot");
        GenericTestKey circuit = new GenericTestKey("circuit");
        GenericTestKey machine = new GenericTestKey("machine");

        RecipeSignature signature = new RecipeSignature(
                null,
                List.of(
                        new RecipeSignatureInput(circuit, 1L),
                        new RecipeSignatureInput(ingot, 2L)
                ),
                List.of(new GenericStack(machine, 1L))
        );
        MachineRecipeIndex index = new MachineRecipeIndex(
                new MachineIdentity(
                        ResourceLocation.withDefaultNamespace("crafting_table"),
                        ResourceLocation.withDefaultNamespace("crafting_table"),
                        null,
                        "minecraft",
                        null,
                        null
                ),
                java.util.Set.of(),
                List.of(ResourceLocation.withDefaultNamespace("crafting")),
                java.util.Set.of(signature),
                MachineSupportStatus.SUPPORTED_GENERIC,
                MachineSupportReasonCode.NONE,
                1L
        );

        ScaledSignatureMatch exact = index.findScaledMatch(
                List.of(
                        new RecipeSignatureInput(circuit, 1L),
                        new RecipeSignatureInput(ingot, 1L),
                        new RecipeSignatureInput(ingot, 1L)
                ),
                List.of(new GenericStack(machine, 1L))
        );
        ScaledSignatureMatch scaled = index.findScaledMatch(
                List.of(
                        new RecipeSignatureInput(circuit, 64L),
                        new RecipeSignatureInput(ingot, 64L),
                        new RecipeSignatureInput(ingot, 64L)
                ),
                List.of(new GenericStack(machine, 64L))
        );

        assertNotNull(exact);
        assertNotNull(scaled);
        assertEquals(1, exact.executionCount());
        assertEquals(64, scaled.executionCount());
        assertEquals(signature, scaled.signature());
    }

    @Test
    void scaleStacksExpandsOutputsByExecutionCount() {
        GenericTestKey machine = new GenericTestKey("machine");

        List<GenericStack> scaled = DirectProcessingStackSupport.scaleStacks(
                List.of(new GenericStack(machine, 1L)),
                64
        );

        assertEquals(List.of(new GenericStack(machine, 64L)), scaled);
    }

    @Test
    void importedOnlyIndexSupportsNonRegistryRecipeTypeIds() {
        GenericTestKey coal = new GenericTestKey("coal");
        GenericTestKey diamond = new GenericTestKey("diamond");
        MachineIdentity identity = new MachineIdentity(
                ResourceLocation.withDefaultNamespace("crafting_table"),
                ResourceLocation.withDefaultNamespace("crafting_table"),
                null,
                "minecraft",
                null,
                null
        );
        ResourceLocation recipeTypeId = ResourceLocation.fromNamespaceAndPath("ifeu", "saucepan");
        RecipeSignature recipeSignature = new RecipeSignature(
                null,
                List.of(new RecipeSignatureInput(coal, 1L)),
                List.of(new GenericStack(diamond, 1L))
        );
        MachineRecipeIndex index = MachineRecipeDiscoveryService.buildImportedOrUnsupportedIndex(
                identity,
                Set.of(),
                MachineSupportStatus.NEEDS_CONFIG_MAPPING,
                MachineSupportReasonCode.MAPPING_MISSING,
                new MachineRecipeUserConfigStore.LoadedImportedSignatures(
                        List.of(recipeTypeId),
                        Set.of(recipeSignature)
                )
        );

        assertEquals(MachineSupportStatus.SUPPORTED_CONFIG, index.status());
        assertEquals(List.of(recipeTypeId), index.recipeTypeIds());
        assertNotNull(index.findScaledMatch(
                List.of(new RecipeSignatureInput(new GenericTestKey("coal"), 1L)),
                List.of(new GenericStack(new GenericTestKey("diamond"), 1L))
        ));
    }

    @Test
    void importedSignaturesPromoteUnreadableScansToSupportedConfig() {
        ResourceLocation recipeTypeId = ResourceLocation.fromNamespaceAndPath("ifeu", "fermenter");
        RecipeSignature recipeSignature = new RecipeSignature(
                null,
                List.of(new RecipeSignatureInput(new GenericTestKey("wheat"), 1L)),
                List.of(new GenericStack(new GenericTestKey("bread"), 1L))
        );

        assertEquals(
                MachineSupportStatus.SUPPORTED_CONFIG,
                MachineRecipeDiscoveryService.supportedStatus(
                        new MachineRecipeDiscoveryService.CandidateScanResult(
                                List.of(),
                                Set.of(),
                                false,
                                true
                        ),
                        new MachineRecipeUserConfigStore.LoadedImportedSignatures(
                                List.of(recipeTypeId),
                                Set.of(recipeSignature)
                        ),
                        MachineSupportStatus.SUPPORTED_GENERIC
                )
        );
    }

    private static final class GenericTestKey extends AEKey {
        private static final MapCodec<GenericTestKey> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(GenericTestKey::id)
        ).apply(instance, GenericTestKey::new));

        private static final GenericTestKeyType TYPE = new GenericTestKeyType();

        private final String id;

        private GenericTestKey(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider registries) {
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
        public void writeToPacket(RegistryFriendlyByteBuf data) {
            data.writeUtf(id);
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericTestKey key && Objects.equals(id, key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static final class GenericTestKeyType extends AEKeyType {
        private GenericTestKeyType() {
            super(
                    Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:direct_processing_test")),
                    GenericTestKey.class,
                    Component.literal("DirectProcessingTest")
            );
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return GenericTestKey.CODEC;
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return new GenericTestKey(input.readUtf());
        }

        @Override
        public AEKey loadKeyFromTag(HolderLookup.Provider provider, CompoundTag tag) {
            return new GenericTestKey(tag.getString("id"));
        }
    }
}
