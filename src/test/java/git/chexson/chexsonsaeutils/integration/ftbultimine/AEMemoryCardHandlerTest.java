package git.chexson.chexsonsaeutils.integration.ftbultimine;

import appeng.api.ids.AEComponents;
import appeng.util.SettingsFrom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AEMemoryCardHandlerTest {

    @Test
    void sameNameUsesTargetSpecificImportSettings() {
        Component targetName = Component.literal("target");
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, targetName)
                .build();
        TestTarget target = new TestTarget(targetName, Set.of());

        boolean applied = AEMemoryCardHandler.applyToTarget(target, settings, targetName, null);

        assertTrue(applied);
        assertSame(SettingsFrom.MEMORY_CARD, target.lastMode);
        assertSame(settings, target.lastSettings);
        assertFalse(target.genericImportCalled);
    }

    @Test
    void differentNameUsesGenericImportWhenSettingsMatch() {
        Component storedName = Component.literal("stored");
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, storedName)
                .build();
        TestTarget target = new TestTarget(Component.literal("target"), Set.of(AEComponents.EXPORTED_SETTINGS_SOURCE));

        boolean applied = AEMemoryCardHandler.applyToTarget(target, settings, storedName, null);

        assertTrue(applied);
        assertTrue(target.genericImportCalled);
        assertSame(settings, target.lastSettings);
    }

    @Test
    void differentNameWithNoGenericImportIsSkipped() {
        Component storedName = Component.literal("stored");
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, storedName)
                .build();
        TestTarget target = new TestTarget(Component.literal("target"), Set.of());

        boolean applied = AEMemoryCardHandler.applyToTarget(target, settings, storedName, null);

        assertFalse(applied);
        assertTrue(target.genericImportCalled);
    }

    @Test
    void failedTargetDoesNotPropagateException() {
        Component storedName = Component.literal("stored");
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, storedName)
                .build();
        TestTarget target = new TestTarget(storedName, Set.of());
        target.failImport = true;

        boolean applied = AEMemoryCardHandler.applyToTarget(target, settings, storedName, null);

        assertFalse(applied);
    }

    @Test
    void emptyCardWithoutSourceNameIsSkipped() {
        assertFalse(AEMemoryCardHandler.hasStoredSettings(DataComponentMap.EMPTY));
        assertFalse(AEMemoryCardHandler.hasStoredSettings(DataComponentMap.builder().build()));
        assertTrue(AEMemoryCardHandler.isEmptyMemoryCard(DataComponentMap.builder().build(), Component.literal("target")));
    }

    @Test
    void cardWithSourceNameHasStoredSettings() {
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, Component.literal("target"))
                .build();

        assertTrue(AEMemoryCardHandler.hasStoredSettings(settings));
        assertFalse(AEMemoryCardHandler.isEmptyMemoryCard(settings, Component.literal("target")));
    }

    @Test
    void settingsNameMappingUsesBlockNamesForInterfaceAndPatternProviderParts() {
        Component partName = Component.literal("part");
        Component interfaceBlockName = Component.literal("interface-block");
        Component patternProviderBlockName = Component.literal("pattern-provider-block");

        assertEquals(
                interfaceBlockName,
                AEMemoryCardHandler.partTargetName(
                        partName,
                        true,
                        interfaceBlockName,
                        false,
                        patternProviderBlockName
                )
        );
        assertEquals(
                patternProviderBlockName,
                AEMemoryCardHandler.partTargetName(
                        partName,
                        false,
                        interfaceBlockName,
                        true,
                        patternProviderBlockName
                )
        );
    }

    @Test
    void settingsNameMappingUsesPartNameForOtherParts() {
        Component partName = Component.literal("part");

        assertEquals(
                partName,
                AEMemoryCardHandler.partTargetName(
                        partName,
                        false,
                        Component.literal("interface-block"),
                        false,
                        Component.literal("pattern-provider-block")
                )
        );
    }

    @Test
    void emptySelectionIsSkipped() {
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, Component.literal("target"))
                .build();

        assertTrue(AEMemoryCardHandler.isEmptySelection(null));
        assertTrue(AEMemoryCardHandler.isEmptySelection(List.of()));
        assertEquals(0, AEMemoryCardHandler.applySettings(null, pos -> {
            throw new IllegalStateException("lookup should not run");
        }, settings, Component.literal("target"), null));
        assertEquals(0, AEMemoryCardHandler.applySettings(List.of(), pos -> {
            throw new IllegalStateException("lookup should not run");
        }, settings, Component.literal("target"), null));
    }

    @Test
    void nonAeTargetIsSkipped() {
        DataComponentMap settings = DataComponentMap.builder()
                .set(AEComponents.EXPORTED_SETTINGS_SOURCE, Component.literal("target"))
                .build();

        int applied = AEMemoryCardHandler.applySettings(
                List.of(BlockPos.ZERO),
                pos -> new Object(),
                settings,
                Component.literal("target"),
                null
        );

        assertEquals(0, applied);
    }

    private static final class TestTarget implements AEMemoryCardHandler.MemoryCardTarget {

        private final Component targetName;
        private final Set<DataComponentType<?>> genericImported;
        private boolean genericImportCalled;
        private boolean failImport;
        private SettingsFrom lastMode;
        private DataComponentMap lastSettings;

        private TestTarget(Component targetName, Set<DataComponentType<?>> genericImported) {
            this.targetName = targetName;
            this.genericImported = genericImported;
        }

        @Override
        public Component targetName() {
            return targetName;
        }

        @Override
        public void importSettings(SettingsFrom mode, DataComponentMap settings, @Nullable Player player) {
            if (failImport) {
                throw new IllegalStateException("forced failure");
            }
            lastMode = mode;
            lastSettings = settings;
        }

        @Override
        public Set<DataComponentType<?>> importGenericSettings(DataComponentMap settings, @Nullable Player player) {
            if (failImport) {
                throw new IllegalStateException("forced failure");
            }
            genericImportCalled = true;
            lastSettings = settings;
            return genericImported;
        }

        @Override
        public String debugName() {
            return "test target";
        }
    }
}
