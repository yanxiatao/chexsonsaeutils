package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;

import java.util.List;

public interface NeoEcoPreparedBatch {

    boolean push(ListCraftingInventory inventory);

    long craftCount();

    List<GenericStack> outputs();

    List<GenericStack> remainders();

    double power();
}
