package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import git.chexson.chexsonsaeutils.client.integration.jei.JeiMachineRecipeTypeHint;
import git.chexson.chexsonsaeutils.client.integration.jei.JeiRuntimeHolder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineIdentity;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AEDirectProcessingMachineScreen extends AEBaseScreen<AEDirectProcessingMachineMenu> {

    private static final int PANEL_BORDER = 0xFF6F6F6F;
    private static final int PANEL_FILL = 0xFFE3E3E3;
    private static final int SLOT_BORDER = 0xFF7A7A7A;
    private static final int SLOT_FILL = 0xFFCFCFCF;

    private Button previousPageButton;
    private Button nextPageButton;
    private Button importJeiButton;
    private List<JeiMachineRecipeTypeHint> currentJeiHints = List.of();
    private boolean suppressJeiTooltipUntilMouseLeave;
    private boolean dismissJeiTooltipThisFrame;
    private TooltipSource currentFrameTooltipSource = TooltipSource.NONE;

    public AEDirectProcessingMachineScreen(
            AEDirectProcessingMachineMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/ae_direct_processing_machine.json"));
        this.widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getHost()));
    }

    @Override
    protected void init() {
        super.init();
        previousPageButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> menu.previousPage())
                .bounds(leftPos + 154, topPos + 52, 14, 16)
                .build());
        nextPageButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> menu.nextPage())
                .bounds(leftPos + 172, topPos + 52, 14, 16)
                .build());
        importJeiButton = addRenderableWidget(Button.builder(Component.literal("JEI"), button -> importCurrentJeiHints())
                .bounds(leftPos + 150, topPos + 16, 42, 16)
                .build());
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        previousPageButton.active = menu.canGoToPreviousPage();
        nextPageButton.active = menu.canGoToNextPage();
        currentJeiHints = collectCurrentJeiHints();
        importJeiButton.active = !currentJeiHints.isEmpty();
        importJeiButton.setMessage(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.jei_import_button",
                currentJeiHints.size()
        ));
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        guiGraphics.drawString(font, menu.getSummaryLine(), 8, 30, 0x404040, false);
        guiGraphics.drawString(font, menu.getVisiblePatternStatusLine(), 8, 42, 0x404040, false);
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        drawPanel(guiGraphics, offsetX + 4, offsetY + 4, 192, 68);
        // drawPanel(guiGraphics, offsetX + 4, offsetY + 82, 166, 64);
        // drawPanel(guiGraphics, offsetX + 173, offsetY + 84, 25, 94);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.MACHINE_INPUT);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.ENCODED_PATTERN);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.PLAYER_INVENTORY);
        drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.PLAYER_HOTBAR);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        currentFrameTooltipSource = resolveTooltipSource(mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        try {
            if (!shouldRenderJeiTooltip(mouseX, mouseY)) {
                return;
            }
            List<Component> tooltip = buildJeiTooltip();
            if (!tooltip.isEmpty()) {
                guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        } finally {
            dismissJeiTooltipThisFrame = false;
            currentFrameTooltipSource = TooltipSource.NONE;
        }
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> tooltip = super.getTooltipFromContainerItem(stack);
        if (currentFrameTooltipSource != TooltipSource.SLOT_ITEM) {
            return tooltip;
        }
        Slot slot = getSlotUnderMouse();
        if (!hasShiftDown() || slot == null || stack.isEmpty()) {
            return tooltip;
        }
        int pageSlotIndex = menu.getPatternPageSlotIndex(slot);
        if (pageSlotIndex < 0) {
            return tooltip;
        }
        List<Component> augmentedTooltip = new ArrayList<>(tooltip);
        appendMissingTooltipLines(augmentedTooltip, menu.getPatternSlotTooltip(pageSlotIndex));
        return List.copyOf(augmentedTooltip);
    }

    private boolean shouldRenderJeiTooltip(int mouseX, int mouseY) {
        if (importJeiButton == null) {
            return false;
        }
        if (!importJeiButton.isMouseOver(mouseX, mouseY)) {
            suppressJeiTooltipUntilMouseLeave = false;
            return false;
        }
        if (dismissJeiTooltipThisFrame
                || suppressJeiTooltipUntilMouseLeave
                || currentFrameTooltipSource != TooltipSource.JEI_BUTTON) {
            return false;
        }
        return true;
    }

    private TooltipSource resolveTooltipSource(int mouseX, int mouseY) {
        Slot slot = getSlotUnderMouse();
        if (slot != null && slot.hasItem()) {
            return TooltipSource.SLOT_ITEM;
        }
        if (importJeiButton != null && importJeiButton.isMouseOver(mouseX, mouseY)) {
            return TooltipSource.JEI_BUTTON;
        }
        return TooltipSource.NONE;
    }

    private static void appendMissingTooltipLines(List<Component> tooltip, List<Component> additions) {
        if (tooltip == null || additions == null || additions.isEmpty()) {
            return;
        }
        Set<String> existingLines = tooltip.stream()
                .filter(java.util.Objects::nonNull)
                .map(Component::getString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Component addition : additions) {
            if (addition == null) {
                continue;
            }
            String line = addition.getString();
            if (existingLines.add(line)) {
                tooltip.add(addition);
            }
        }
    }

    private void drawPanel(GuiGraphics guiGraphics, int left, int top, int width, int height) {
        guiGraphics.fill(left, top, left + width, top + height, PANEL_BORDER);
        guiGraphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, PANEL_FILL);
    }

    private void drawSlotBackgrounds(GuiGraphics guiGraphics, int offsetX, int offsetY, SlotSemantic semantics) {
        for (Slot slot : menu.getSlots(semantics)) {
            int left = offsetX + slot.x - 1;
            int top = offsetY + slot.y - 1;
            guiGraphics.fill(left, top, left + 18, top + 18, SLOT_BORDER);
            guiGraphics.fill(left + 1, top + 1, left + 17, top + 17, SLOT_FILL);
        }
    }

    private void importCurrentJeiHints() {
        MachineRecipeConfigImportRequest request = buildJeiImportRequest();
        if (request != null) {
            PacketDistributor.sendToServer(new DirectProcessingJeiImportPayload(
                    menu.getContainerIdForPayload(),
                    request
            ));
        }
        dismissJeiTooltipThisFrame = true;
        clearJeiButtonFocus();
        suppressJeiTooltipUntilMouseLeave = true;
    }

    private void clearJeiButtonFocus() {
        if (importJeiButton != null) {
            importJeiButton.setFocused(false);
        }
        setFocused((GuiEventListener) null);
    }

    private List<JeiMachineRecipeTypeHint> collectCurrentJeiHints() {
        MachineIdentity identity = menu.getHost() == null
                ? null
                : MachineIdentity.fromBindingStack(menu.getHost().getMachineBindingStack());
        if (identity == null || !JeiRuntimeHolder.hasRuntime()) {
            return List.of();
        }
        return JeiRuntimeHolder.collectVisibleHintsForMachine(identity.machineItemId(), identity.blockId());
    }

    private MachineRecipeConfigImportRequest buildJeiImportRequest() {
        MachineIdentity identity = menu.getHost() == null
                ? null
                : MachineIdentity.fromBindingStack(menu.getHost().getMachineBindingStack());
        if (identity == null || currentJeiHints.isEmpty()) {
            return null;
        }
        Set<ResourceLocation> recipeTypeIds = new LinkedHashSet<>();
        int defaultTicks = 20;
        for (JeiMachineRecipeTypeHint hint : currentJeiHints) {
            if (hint == null || hint.recipeTypeId() == null) {
                continue;
            }
            recipeTypeIds.add(hint.recipeTypeId());
            defaultTicks = Math.max(1, hint.defaultTicks());
        }
        if (recipeTypeIds.isEmpty()) {
            return null;
        }
        RegistryAccess registries = minecraft != null && minecraft.level != null
                ? minecraft.level.registryAccess()
                : RegistryAccess.EMPTY;
        String signatureHintsJson = MachineRecipeImportedSignature.toJson(
                registries,
                JeiRuntimeHolder.collectSignatureHintsForMachine(identity.machineItemId(), identity.blockId())
        );
        return new MachineRecipeConfigImportRequest(
                identity.machineItemId(),
                identity.blockId(),
                List.copyOf(recipeTypeIds),
                defaultTicks,
                "generic",
                "any",
                true,
                signatureHintsJson
        );
    }

    private List<Component> buildJeiTooltip() {
        List<Component> tooltip = new ArrayList<>();
        if (!JeiRuntimeHolder.hasRuntime()) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_unavailable"
            ));
            return tooltip;
        }
        if (currentJeiHints.isEmpty()) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_no_candidates"
            ));
            return tooltip;
        }
        tooltip.add(Component.translatable(
                "gui.chexsonsaeutils.ae_direct_processing_machine.jei_candidates_title"
        ));
        int limit = Math.min(3, currentJeiHints.size());
        for (int index = 0; index < limit; index++) {
            tooltip.add(Component.literal(currentJeiHints.get(index).recipeTypeId().toString()));
        }
        if (currentJeiHints.size() > limit) {
            tooltip.add(Component.translatable(
                    "gui.chexsonsaeutils.ae_direct_processing_machine.jei_candidates_more",
                    currentJeiHints.size() - limit
            ));
        }
        return tooltip;
    }

    private enum TooltipSource {
        NONE,
        SLOT_ITEM,
        JEI_BUTTON
    }
}
