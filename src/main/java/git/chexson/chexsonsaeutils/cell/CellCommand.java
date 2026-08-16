package git.chexson.chexsonsaeutils.cell;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class CellCommand {

    private CellCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chexsonsaeutils")
                .then(Commands.literal("cell")
                        .then(Commands.literal("list")
                                .executes(ctx -> listCells(ctx.getSource())))
                        .then(Commands.literal("info")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .executes(ctx -> cellInfo(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "uuid")))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .requires(s -> s.hasPermission(2))
                                        .executes(ctx -> clearCell(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "uuid")))))));
    }

    private static int listCells(CommandSourceStack source) {
        var dir = CellStoragePaths.getCellDir(getWorldRoot());
        if (!Files.isDirectory(dir)) {
            source.sendSuccess(() -> Component.literal("No cells found"), false);
            return 0;
        }
        try (var files = Files.list(dir)) {
            var count = files.filter(f -> f.getFileName().toString().endsWith(".nbt"))
                    .peek(f -> {
                        var fileName = f.getFileName().toString();
                        var uuid = fileName.substring(0, fileName.length() - ".nbt".length());
                        var size = describeSize(f);
                        source.sendSuccess(() ->
                                Component.literal(uuid + " - " + size), false);
                    })
                    .count();
            if (count == 0) {
                source.sendSuccess(() -> Component.literal("No cells found"), false);
            }
            return (int) count;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Failed to list cells: " + e.getMessage()));
            return 0;
        }
    }

    private static int cellInfo(CommandSourceStack source, String uuidStr) {
        try {
            var uuid = UUID.fromString(uuidStr);
            var nbtFile = CellStoragePaths.getNbtFile(getWorldRoot(), uuid);

            if (!Files.exists(nbtFile)) {
                source.sendFailure(Component.literal("Cell not found: " + uuid));
                return 0;
            }

            var fileSize = Files.size(nbtFile);
            var entries = InfinityCellStore.readEntryCount(nbtFile);

            source.sendSuccess(() ->
                    Component.literal("UUID: " + uuid), false);
            source.sendSuccess(() ->
                    Component.literal("Entries: " + entries), false);
            source.sendSuccess(() ->
                    Component.literal("NBT file: " + formatBytes(fileSize)), false);

            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Failed to read cell: " + e.getMessage()));
            return 0;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid UUID: " + uuidStr));
            return 0;
        }
    }

    private static int clearCell(CommandSourceStack source, String uuidStr) {
        try {
            var uuid = UUID.fromString(uuidStr);
            var nbtFile = CellStoragePaths.getNbtFile(getWorldRoot(), uuid);

            var deleted = Files.deleteIfExists(nbtFile);

            source.sendSuccess(() ->
                    Component.literal("Cleared cell " + uuid + " (deleted " + (deleted ? 1 : 0) + " files)"), true);
            return deleted ? 1 : 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Failed to clear cell: " + e.getMessage()));
            return 0;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid UUID: " + uuidStr));
            return 0;
        }
    }

    private static Path getWorldRoot() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return Path.of(".");
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
    }

    private static String describeSize(Path f) {
        try {
            return formatBytes(Files.size(f));
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String formatBytes(long bytes) {
        // ponytail: just KiB, no MB/GB/template complexity
        if (bytes < 1024) return bytes + " B";
        return (bytes / 1024) + " KiB";
    }
}
