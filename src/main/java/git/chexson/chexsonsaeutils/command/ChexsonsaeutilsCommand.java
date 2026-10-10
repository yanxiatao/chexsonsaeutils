package git.chexson.chexsonsaeutils.command;

import com.mojang.brigadier.CommandDispatcher;
import git.chexson.chexsonsaeutils.cell.CellCommand;
import git.chexson.chexsonsaeutils.crafting.graphplan.replay.ReplayCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Owns the single public command root for all mod diagnostics and maintenance tools. */
public final class ChexsonsaeutilsCommand {
    private ChexsonsaeutilsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chexsonsaeutils")
                .then(CellCommand.command())
                .then(ReplayCommand.command()));
    }
}
