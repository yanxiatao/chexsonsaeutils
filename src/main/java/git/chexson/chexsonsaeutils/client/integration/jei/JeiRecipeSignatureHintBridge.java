package git.chexson.chexsonsaeutils.client.integration.jei;

import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingJeiImportRecipeTypeGuard;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingStackConverterRegistry;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.gui.ingredient.IRecipeSlotTooltipCallback;
import mezz.jei.api.gui.widgets.ISlottedWidgetFactory;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class JeiRecipeSignatureHintBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(JeiRecipeSignatureHintBridge.class);
    private static final int MAX_CANDIDATE_SIGNATURES_PER_RECIPE = 256;
    static final int MAX_IMPORTED_SIGNATURE_HINTS =
            Math.min(1024, MachineRecipeConfigImportRequest.MAX_NETWORK_SIGNATURES);
    private final DirectProcessingStackConverterRegistry stackConverters;

    public JeiRecipeSignatureHintBridge() {
        this(DirectProcessingStackConverterRegistry.directProcessingDefaults());
    }

    JeiRecipeSignatureHintBridge(DirectProcessingStackConverterRegistry stackConverters) {
        this.stackConverters = stackConverters == null
                ? DirectProcessingStackConverterRegistry.directProcessingDefaults()
                : stackConverters;
    }

    public List<MachineRecipeImportedSignature> collectSignatureHintsForMachine(
            @Nullable IJeiRuntime runtime,
            @Nullable ResourceLocation machineItemId,
            @Nullable ResourceLocation machineBlockId,
            JeiMachineRecipeTypeHintBridge hintBridge
    ) {
        if (runtime == null || hintBridge == null) {
            return List.of();
        }
        IRecipeManager recipeManager = runtime.getRecipeManager();
        if (recipeManager == null) {
            return List.of();
        }
        List<JeiMachineRecipeTypeHint> machineHints =
                hintBridge.collectHintsForMachine(runtime, machineItemId, machineBlockId);
        if (machineHints.isEmpty()) {
            return List.of();
        }
        Map<ResourceLocation, IRecipeCategory<?>> categoriesById = new LinkedHashMap<>();
        recipeManager.createRecipeCategoryLookup().get().forEach(category -> {
            if (category != null && category.getRecipeType() != null && category.getRecipeType().getUid() != null) {
                categoriesById.putIfAbsent(category.getRecipeType().getUid(), category);
            }
        });
        Set<MachineRecipeImportedSignature> collected = new LinkedHashSet<>();
        for (JeiMachineRecipeTypeHint hint : machineHints) {
            if (hint == null || !DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(hint.recipeTypeId())) {
                continue;
            }
            IRecipeCategory<?> category = categoriesById.get(hint.recipeTypeId());
            if (category == null) {
                continue;
            }
            collected.addAll(collectCategorySignatures(recipeManager, category, hint.recipeTypeId()));
            if (collected.size() > MAX_IMPORTED_SIGNATURE_HINTS) {
                return List.of();
            }
        }
        return collected.isEmpty() ? List.of() : List.copyOf(collected);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<MachineRecipeImportedSignature> collectCategorySignatures(
            IRecipeManager recipeManager,
            IRecipeCategory category,
            ResourceLocation recipeTypeId
    ) {
        if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(recipeTypeId)) {
            return List.of();
        }
        Optional<RecipeType<?>> jeiRecipeType = recipeManager.getRecipeType(recipeTypeId);
        if (jeiRecipeType.isEmpty()) {
            return List.of();
        }
        IRecipeLookup lookup = recipeManager.createRecipeLookup((RecipeType) jeiRecipeType.get());
        if (lookup == null) {
            return List.of();
        }
        Set<MachineRecipeImportedSignature> collected = new LinkedHashSet<>();
        for (Object recipe : lookup.get().toList()) {
            collected.addAll(collectRecipeSignatures(category, recipe, recipeTypeId));
            if (collected.size() > MAX_IMPORTED_SIGNATURE_HINTS) {
                return List.of();
            }
        }
        return collected.isEmpty() ? List.of() : List.copyOf(collected);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<MachineRecipeImportedSignature> collectRecipeSignatures(
            IRecipeCategory category,
            Object recipe,
            ResourceLocation recipeTypeId
    ) {
        if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(recipeTypeId)) {
            return List.of();
        }
        CapturingRecipeLayoutBuilder builder = new CapturingRecipeLayoutBuilder(stackConverters);
        try {
            category.setRecipe(builder, recipe, EmptyFocusGroup.INSTANCE);
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to collect JEI recipe signature hint for {}", recipeTypeId, exception);
            return List.of();
        }
        return builder.toSignatures(recipeTypeId);
    }

    List<MachineRecipeImportedSignature> captureRecipeLayoutForTest(
            ResourceLocation recipeTypeId,
            Consumer<IRecipeLayoutBuilder> layoutPopulator
    ) {
        if (!DirectProcessingJeiImportRecipeTypeGuard.isSupportedRecipeType(recipeTypeId)) {
            return List.of();
        }
        CapturingRecipeLayoutBuilder builder = new CapturingRecipeLayoutBuilder(stackConverters);
        if (layoutPopulator != null) {
            layoutPopulator.accept(builder);
        }
        return builder.toSignatures(recipeTypeId);
    }

    private static final class CapturingRecipeLayoutBuilder implements IRecipeLayoutBuilder {

        private final DirectProcessingStackConverterRegistry stackConverters;
        private final List<CapturingRecipeSlotBuilder> slots = new ArrayList<>();

        private CapturingRecipeLayoutBuilder(DirectProcessingStackConverterRegistry stackConverters) {
            this.stackConverters = stackConverters;
        }

        @Override
        public IRecipeSlotBuilder addSlot(RecipeIngredientRole role) {
            CapturingRecipeSlotBuilder slotBuilder = new CapturingRecipeSlotBuilder(role, stackConverters);
            slots.add(slotBuilder);
            return slotBuilder;
        }

        @Override
        public IRecipeSlotBuilder addSlotToWidget(RecipeIngredientRole role, ISlottedWidgetFactory<?> widgetFactory) {
            return addSlot(role);
        }

        @Override
        public IIngredientAcceptor<?> addInvisibleIngredients(RecipeIngredientRole role) {
            return addSlot(role);
        }

        @Override
        public void moveRecipeTransferButton(int x, int y) {
        }

        @Override
        public void setShapeless() {
        }

        @Override
        public void setShapeless(int posX, int posY) {
        }

        @Override
        public void createFocusLink(IIngredientAcceptor<?>... ingredientAcceptors) {
        }

        private List<MachineRecipeImportedSignature> toSignatures(ResourceLocation recipeTypeId) {
            List<List<MachineRecipeImportedStack>> inputChoices = new ArrayList<>();
            List<List<MachineRecipeImportedStack>> catalystChoices = new ArrayList<>();
            List<MachineRecipeImportedStack> outputs = new ArrayList<>();
            for (CapturingRecipeSlotBuilder slot : slots) {
                List<MachineRecipeImportedStack> normalized = slot.normalizedChoices();
                if (slot.role == RecipeIngredientRole.OUTPUT) {
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    if (normalized.size() != 1) {
                        return List.of();
                    }
                    outputs.add(normalized.getFirst());
                    continue;
                }
                if (slot.role != RecipeIngredientRole.INPUT && slot.role != RecipeIngredientRole.CATALYST) {
                    continue;
                }
                if (normalized.isEmpty()) {
                    continue;
                }
                if (slot.role == RecipeIngredientRole.INPUT) {
                    inputChoices.add(normalized);
                    continue;
                }
                catalystChoices.add(normalized);
            }
            List<List<MachineRecipeImportedStack>> selectedInputs = inputChoices.isEmpty() ? catalystChoices : inputChoices;
            if (selectedInputs.isEmpty() || outputs.isEmpty()) {
                return List.of();
            }
            int candidateCount = 1;
            for (List<MachineRecipeImportedStack> normalized : selectedInputs) {
                if (candidateCount > MAX_CANDIDATE_SIGNATURES_PER_RECIPE / normalized.size()) {
                    return List.of();
                }
                candidateCount *= normalized.size();
            }
            Set<MachineRecipeImportedSignature> signatures = new LinkedHashSet<>();
            expand(recipeTypeId, selectedInputs, outputs, 0, new ArrayList<>(), signatures);
            return signatures.isEmpty() ? List.of() : List.copyOf(signatures);
        }

        private void expand(
                ResourceLocation recipeTypeId,
                List<List<MachineRecipeImportedStack>> inputChoices,
                List<MachineRecipeImportedStack> outputs,
                int index,
                List<MachineRecipeImportedStack> selectedInputs,
                Set<MachineRecipeImportedSignature> signatures
        ) {
            if (signatures.size() >= MAX_CANDIDATE_SIGNATURES_PER_RECIPE) {
                return;
            }
            if (index >= inputChoices.size()) {
                signatures.add(new MachineRecipeImportedSignature(recipeTypeId, selectedInputs, outputs));
                return;
            }
            for (MachineRecipeImportedStack choice : inputChoices.get(index)) {
                selectedInputs.add(choice);
                expand(recipeTypeId, inputChoices, outputs, index + 1, selectedInputs, signatures);
                selectedInputs.removeLast();
                if (signatures.size() >= MAX_CANDIDATE_SIGNATURES_PER_RECIPE) {
                    return;
                }
            }
        }
    }

    private static final class CapturingRecipeSlotBuilder implements IRecipeSlotBuilder {

        private final RecipeIngredientRole role;
        private final DirectProcessingStackConverterRegistry stackConverters;
        private final List<MachineRecipeImportedStack> capturedIngredients = new ArrayList<>();
        private int x;
        private int y;
        private int width = 18;
        private int height = 18;

        private CapturingRecipeSlotBuilder(
                RecipeIngredientRole role,
                DirectProcessingStackConverterRegistry stackConverters
        ) {
            this.role = role == null ? RecipeIngredientRole.RENDER_ONLY : role;
            this.stackConverters = stackConverters;
        }

        private List<MachineRecipeImportedStack> normalizedChoices() {
            if (capturedIngredients.isEmpty()) {
                return List.of();
            }
            List<MachineRecipeImportedStack> normalized = new ArrayList<>(capturedIngredients.size());
            Set<MachineRecipeImportedStack> seen = new LinkedHashSet<>();
            for (MachineRecipeImportedStack stack : capturedIngredients) {
                if (stack != null && seen.add(stack)) {
                    normalized.add(stack);
                }
            }
            return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
        }

        @Override
        public <I> IRecipeSlotBuilder addIngredients(IIngredientType<I> ingredientType, List<I> ingredients) {
            if (ingredients != null) {
                for (I ingredient : ingredients) {
                    addIngredient(ingredientType, ingredient);
                }
            }
            return this;
        }

        @Override
        public <I> IRecipeSlotBuilder addIngredient(IIngredientType<I> ingredientType, I ingredient) {
            capture(ingredient);
            return this;
        }

        @Override
        public IRecipeSlotBuilder addIngredientsUnsafe(List<?> ingredients) {
            if (ingredients != null) {
                for (Object ingredient : ingredients) {
                    capture(ingredient);
                }
            }
            return this;
        }

        @Override
        public IRecipeSlotBuilder addTypedIngredients(List<ITypedIngredient<?>> typedIngredients) {
            if (typedIngredients != null) {
                for (ITypedIngredient<?> typedIngredient : typedIngredients) {
                    capture(typedIngredient);
                }
            }
            return this;
        }

        @Override
        public IRecipeSlotBuilder addOptionalTypedIngredients(List<Optional<ITypedIngredient<?>>> typedIngredients) {
            if (typedIngredients != null) {
                for (Optional<ITypedIngredient<?>> typedIngredient : typedIngredients) {
                    capture(typedIngredient);
                }
            }
            return this;
        }

        @Override
        public IRecipeSlotBuilder addFluidStack(Fluid fluid) {
            return addFluidStack(fluid, 1000L);
        }

        @Override
        public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount) {
            return addFluidStack(fluid, amount, DataComponentPatch.EMPTY);
        }

        @Override
        public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount, DataComponentPatch patch) {
            if (fluid != null && fluid != net.minecraft.world.level.material.Fluids.EMPTY
                    && amount > 0L && amount <= Integer.MAX_VALUE) {
                capture(new FluidStack(fluid, (int) amount));
            }
            return this;
        }

        @Override
        public IRecipeSlotBuilder addTooltipCallback(IRecipeSlotTooltipCallback tooltipCallback) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder addRichTooltipCallback(IRecipeSlotRichTooltipCallback tooltipCallback) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setSlotName(String name) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setStandardSlotBackground() {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setOutputSlotBackground() {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setBackground(IDrawable background, int xOffset, int yOffset) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setOverlay(IDrawable overlay, int xOffset, int yOffset) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setFluidRenderer(long capacity, boolean showCapacity, int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            return this;
        }

        @Override
        public <T> IRecipeSlotBuilder setCustomRenderer(
                IIngredientType<T> ingredientType,
                IIngredientRenderer<T> ingredientRenderer
        ) {
            return this;
        }

        @Override
        public IRecipeSlotBuilder setPosition(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        private void capture(@Nullable Object ingredient) {
            if (ingredient == null) {
                return;
            }
            if (ingredient instanceof Optional<?> optional) {
                optional.ifPresent(this::capture);
                return;
            }
            if (ingredient instanceof ITypedIngredient<?> typedIngredient) {
                capture(typedIngredient.getIngredient());
                return;
            }
            MachineRecipeImportedStack imported = MachineRecipeImportedStack.fromGenericStack(
                    stackConverters == null ? null : stackConverters.convert(ingredient)
            );
            if (imported != null) {
                capturedIngredients.add(imported);
            }
        }
    }

    private enum EmptyFocusGroup implements IFocusGroup {
        INSTANCE;

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<IFocus<?>> getAllFocuses() {
            return List.of();
        }

        @Override
        public Stream<IFocus<?>> getFocuses(RecipeIngredientRole role) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> ingredientType) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> ingredientType, RecipeIngredientRole role) {
            return Stream.empty();
        }
    }
}
