package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.crafting.pattern.AECraftingPattern;

/**
 * Marks virtual formal-machine patterns that must resolve provider ownership through an existing crafting pattern.
 */
public interface IFormalMachineDelegatingPattern {

    /**
     * Returns the stable AE2 crafting pattern that owns provider lookup for this virtual pattern.
     */
    AECraftingPattern basePattern();
}
