package git.chexson.chexsonsaeutils.crafting.graphplan.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Operator-facing runtime capture replay commands. */
public final class ReplayCommand {
    public static final String CAPTURE_DIRECTORY_PROPERTY = "chexsonsaeutils.replay.captureDir";
    public static final String REPORT_DIRECTORY_PROPERTY = "chexsonsaeutils.replay.reportDir";
    public static final String DEFAULT_PLANNER_PROPERTY = "chexsonsaeutils.replay.planner";
    public static final String WARMUPS_PROPERTY = "chexsonsaeutils.replay.warmups";
    public static final String SAMPLES_PROPERTY = "chexsonsaeutils.replay.samples";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConcurrentHashMap<String, Report> REPORTS = new ConcurrentHashMap<>();

    private ReplayCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("replay")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("run")
                        .then(Commands.argument("capture", StringArgumentType.word())
                                .executes(context -> run(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "capture"),
                                        System.getProperty(DEFAULT_PLANNER_PROPERTY, "linear")))
                                .then(Commands.argument("planner", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("all");
                                            for (RuntimeReplayRunner.Planner planner
                                                    : RuntimeReplayRunner.Planner.values()) {
                                                builder.suggest(planner.commandName());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> run(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "capture"),
                                                StringArgumentType.getString(context, "planner"))))))
                .then(Commands.literal("report")
                        .then(Commands.argument("capture", StringArgumentType.word())
                                .executes(context -> report(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "capture")))));
    }

    private static int list(CommandSourceStack source) {
        Path directory = captureDirectory(source);
        try {
            List<Capture> captures = captures(directory);
            if (captures.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No replay captures in " + directory), false);
                return 0;
            }
            for (Capture capture : captures) {
                source.sendSuccess(() -> Component.literal(
                        capture.id() + " - schema=" + capture.schemaVersion()
                                + ", target=" + capture.target()
                                + ", amount=" + capture.amount()
                                + ", file=" + capture.file().getFileName()), false);
            }
            return captures.size();
        } catch (IOException failure) {
            source.sendFailure(Component.literal("Failed to list replay captures: " + failure.getMessage()));
            return 0;
        }
    }

    private static int run(CommandSourceStack source, String captureArgument, String plannerArgument) {
        final List<RuntimeReplayRunner.Planner> planners;
        final Capture capture;
        try {
            planners = "all".equalsIgnoreCase(plannerArgument)
                    ? List.of(RuntimeReplayRunner.Planner.values())
                    : List.of(RuntimeReplayRunner.Planner.parse(plannerArgument));
            capture = resolveCapture(captureDirectory(source), captureArgument);
        } catch (IllegalArgumentException | IOException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
            return 0;
        }

        final CapturedPlanReplay.Loaded fixture;
        final int warmups;
        final int samples;
        try {
            fixture = CapturedPlanReplay.load(capture.file(), source.registryAccess());
            warmups = nonNegativeProperty(WARMUPS_PROPERTY, 2);
            samples = positiveProperty(SAMPLES_PROPERTY, 8);
        } catch (IOException | RuntimeException failure) {
            source.sendFailure(Component.literal("Failed to configure replay " + capture.id() + ": "
                    + rootMessage(failure)));
            return 0;
        }

        String plannerNames = String.join(",", planners.stream()
                .map(RuntimeReplayRunner.Planner::commandName).toList());
        source.sendSuccess(() -> Component.literal(
                "Started replay " + capture.id() + " with " + plannerNames
                        + " (patterns=" + fixture.patternCount()
                        + ", keys=" + fixture.keyCount()
                        + ", warmups=" + warmups
                        + ", samples=" + samples
                        + ", authoritative=" + fixture.authoritativePerformance() + ")"), true);
        RuntimeReplayRunner.runExperiment(fixture, planners, source.getLevel(), warmups, samples)
                .whenComplete((experiment, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        String message = rootMessage(failure);
                        Report report = Report.failure(capture, plannerNames, fixture, message);
                        REPORTS.put(capture.id(), report);
                        writeReport(source, report);
                        source.sendFailure(Component.literal(
                                "Replay " + capture.id() + " failed: " + message));
                        return;
                    }
                    Report report = Report.success(capture, fixture, experiment);
                    REPORTS.put(capture.id(), report);
                    Path written = writeReport(source, report);
                    source.sendSuccess(() -> Component.literal(
                            "Replay " + capture.id() + " completed: "
                                    + summarize(experiment)
                                    + (written == null ? "" : ", report=" + written)), true);
                }));
        return 1;
    }

    private static int report(CommandSourceStack source, String captureArgument) {
        try {
            Capture capture = resolveCapture(captureDirectory(source), captureArgument);
            Report current = REPORTS.get(capture.id());
            if (current == null) {
                Path reportFile = reportPath(source, capture.id());
                if (!Files.isRegularFile(reportFile)) {
                    source.sendFailure(Component.literal(
                            "No replay report for " + capture.id() + "; run it first"));
                    return 0;
                }
                try (Reader reader = Files.newBufferedReader(reportFile, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    source.sendSuccess(() -> Component.literal(GSON.toJson(json)), false);
                }
                return 1;
            }
            source.sendSuccess(() -> Component.literal(GSON.toJson(current.toJson())), false);
            return current.success() ? 1 : 0;
        } catch (IOException failure) {
            source.sendFailure(Component.literal("Failed to read replay report: " + failure.getMessage()));
            return 0;
        }
    }

    private static List<Capture> captures(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Capture> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    result.add(readMetadata(file));
                } catch (IOException | RuntimeException ignored) {
                    // Capture directories may also contain reports or partial writes.
                }
            }
        }
        result.sort(Comparator.comparing(Capture::file));
        return List.copyOf(result);
    }

    private static Capture readMetadata(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("keys") || !root.has("patterns") || !root.has("target")) {
                throw new IOException("not a replay capture");
            }
            String fallback = stripExtension(file.getFileName().toString());
            return new Capture(
                    root.has("captureId") ? root.get("captureId").getAsString() : fallback,
                    file,
                    root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1,
                    root.get("target").getAsInt(),
                    root.has("amount") ? root.get("amount").getAsLong() : 0L,
                    objectOrEmpty(root, "environment"),
                    objectOrEmpty(root, "integrity"));
        }
    }

    private static JsonObject objectOrEmpty(JsonObject root, String name) {
        return root.has(name) && root.get(name).isJsonObject()
                ? root.getAsJsonObject(name).deepCopy()
                : new JsonObject();
    }

    private static Capture resolveCapture(Path directory, String argument) throws IOException {
        Path direct = directory.resolve(argument).normalize();
        if (direct.startsWith(directory.normalize()) && Files.isRegularFile(direct)) {
            return readMetadata(direct);
        }
        Path json = directory.resolve(argument + ".json").normalize();
        if (json.startsWith(directory.normalize()) && Files.isRegularFile(json)) {
            return readMetadata(json);
        }
        for (Capture capture : captures(directory)) {
            if (capture.id().equalsIgnoreCase(argument)
                    || stripExtension(capture.file().getFileName().toString()).equalsIgnoreCase(argument)) {
                return capture;
            }
        }
        throw new IllegalArgumentException("Replay capture not found: " + argument);
    }

    private static Path captureDirectory(CommandSourceStack source) {
        String configured = System.getProperty(CAPTURE_DIRECTORY_PROPERTY, "logs");
        Path configuredPath = Path.of(configured);
        return configuredPath.isAbsolute()
                ? configuredPath.normalize()
                : source.getServer().getWorldPath(LevelResource.ROOT).resolve(configuredPath).normalize();
    }

    private static Path reportDirectory(CommandSourceStack source) {
        String configured = System.getProperty(REPORT_DIRECTORY_PROPERTY, "logs/replay-reports");
        Path configuredPath = Path.of(configured);
        return configuredPath.isAbsolute()
                ? configuredPath.normalize()
                : source.getServer().getWorldPath(LevelResource.ROOT).resolve(configuredPath).normalize();
    }

    private static Path reportPath(CommandSourceStack source, String captureId) {
        return reportDirectory(source).resolve(safeName(captureId) + "-latest.json");
    }

    private static Path writeReport(CommandSourceStack source, Report report) {
        Path path = reportPath(source, report.captureId());
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(report.toJson(), writer);
            }
            return path;
        } catch (IOException failure) {
            source.sendFailure(Component.literal("Replay completed but report write failed: "
                    + failure.getMessage()));
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int nonNegativeProperty(String name, int fallback) {
        int value = integerProperty(name, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }

    private static int positiveProperty(String name, int fallback) {
        int value = integerProperty(name, fallback);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
        return value;
    }

    private static int integerProperty(String name, int fallback) {
        String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer: " + configured, failure);
        }
    }

    private static String summarize(RuntimeReplayRunner.Experiment experiment) {
        return String.join("; ", experiment.statistics().stream().map(statistics ->
                statistics.planner().commandName()
                        + (statistics.planner().productionEntry() ? "" : "(independent)")
                        + " p50=" + formatMillis(statistics.p50Nanos()) + "ms"
                        + ", p95=" + formatMillis(statistics.p95Nanos()) + "ms"
                        + ", max=" + formatMillis(statistics.maxNanos()) + "ms"
                        + ", failures=" + statistics.failures() + "/" + statistics.samples()
                        + ", semantic=" + (statistics.semanticConsistent() ? "consistent" : "different"))
                .toList());
    }

    private static String formatMillis(long nanos) {
        return nanos < 0L ? "n/a" : String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record Capture(
            String id,
            Path file,
            int schemaVersion,
            int target,
            long amount,
            JsonObject environment,
            JsonObject integrity
    ) {
    }

    private record Report(
            String captureId,
            String captureFile,
            int schemaVersion,
            String planners,
            String completedAt,
            boolean success,
            boolean authoritativePerformance,
            int patternCount,
            int keyCount,
            long stockDropped,
            RuntimeReplayRunner.Experiment experiment,
            JsonObject environment,
            JsonObject integrity,
            String error
    ) {
        static Report success(Capture capture, CapturedPlanReplay.Loaded fixture,
                RuntimeReplayRunner.Experiment experiment) {
            String planners = String.join(",", experiment.statistics().stream()
                    .map(statistics -> statistics.planner().commandName()).toList());
            return new Report(capture.id(), capture.file().toString(), capture.schemaVersion(), planners,
                    Instant.now().toString(), true, fixture.authoritativePerformance(), fixture.patternCount(),
                    fixture.keyCount(), fixture.stockDropped(), experiment,
                    capture.environment().deepCopy(), capture.integrity().deepCopy(), null);
        }

        static Report failure(Capture capture, String planners, CapturedPlanReplay.Loaded fixture, String error) {
            return new Report(capture.id(), capture.file().toString(), capture.schemaVersion(), planners,
                    Instant.now().toString(), false, fixture.authoritativePerformance(), fixture.patternCount(),
                    fixture.keyCount(), fixture.stockDropped(), null,
                    capture.environment().deepCopy(), capture.integrity().deepCopy(), error);
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("captureId", this.captureId);
            json.addProperty("captureFile", this.captureFile);
            json.addProperty("schemaVersion", this.schemaVersion);
            json.addProperty("planners", this.planners);
            json.addProperty("completedAt", this.completedAt);
            json.addProperty("success", this.success);
            json.addProperty("authoritativePerformance", this.authoritativePerformance);
            json.addProperty("timingBoundary", "submission through CraftingPlanSummary conversion");
            json.addProperty("patternCount", this.patternCount);
            json.addProperty("keyCount", this.keyCount);
            json.addProperty("stockDropped", this.stockDropped);
            json.add("environment", this.environment.deepCopy());
            json.add("integrity", this.integrity.deepCopy());
            if (this.experiment != null) {
                json.addProperty("warmups", this.experiment.warmups());
                json.addProperty("samples", this.experiment.samples());
                JsonArray executionOrder = new JsonArray();
                this.experiment.executionOrder().forEach(executionOrder::add);
                json.add("executionOrder", executionOrder);

                JsonArray statistics = new JsonArray();
                this.experiment.statistics().stream()
                        .map(Report::statisticsJson)
                        .forEach(statistics::add);
                json.add("statistics", statistics);

                JsonArray observations = new JsonArray();
                this.experiment.observations().stream()
                        .map(Report::sampleJson)
                        .forEach(observations::add);
                json.add("observations", observations);
            }
            if (this.error != null) {
                json.addProperty("error", this.error);
            }
            return json;
        }

        private static JsonObject statisticsJson(RuntimeReplayRunner.PlannerStatistics statistics) {
            JsonObject json = new JsonObject();
            json.addProperty("planner", statistics.planner().commandName());
            json.addProperty("productionEntry", statistics.planner().productionEntry());
            json.addProperty("samples", statistics.samples());
            json.addProperty("failures", statistics.failures());
            json.addProperty("failureRate", statistics.failureRate());
            addTiming(json, "coldNanos", statistics.coldNanos());
            addTiming(json, "p50Nanos", statistics.p50Nanos());
            addTiming(json, "p95Nanos", statistics.p95Nanos());
            addTiming(json, "maxNanos", statistics.maxNanos());
            addTiming(json, "calculationP50Nanos", statistics.calculationP50Nanos());
            addTiming(json, "compileP50Nanos", statistics.compileP50Nanos());
            addTiming(json, "solveP50Nanos", statistics.solveP50Nanos());
            addTiming(json, "retentionP50Nanos", statistics.retentionP50Nanos());
            addTiming(json, "convertP50Nanos", statistics.convertP50Nanos());
            addTiming(json, "summaryP50Nanos", statistics.summaryP50Nanos());
            json.addProperty("semanticConsistent", statistics.semanticConsistent());
            if (statistics.semanticSummary() != null) {
                json.addProperty("semanticSummary", statistics.semanticSummary());
            }
            if (statistics.error() != null) {
                json.addProperty("error", statistics.error());
            }
            return json;
        }

        private static JsonObject sampleJson(RuntimeReplayRunner.Sample sample) {
            JsonObject json = new JsonObject();
            json.addProperty("planner", sample.planner().commandName());
            json.addProperty("productionEntry", sample.planner().productionEntry());
            json.addProperty("phase", sample.phase());
            json.addProperty("round", sample.round());
            json.addProperty("success", sample.success());
            if (sample.result() != null) {
                json.addProperty("elapsedNanos", sample.result().elapsedNanos());
                json.addProperty("queueNanos", sample.result().queueNanos());
                json.addProperty("calculationNanos", sample.result().calculationNanos());
                addTiming(json, "compileNanos", sample.result().compileNanos());
                addTiming(json, "solveNanos", sample.result().solveNanos());
                addTiming(json, "retentionNanos", sample.result().retentionNanos());
                addTiming(json, "convertNanos", sample.result().convertNanos());
                json.addProperty("summaryNanos", sample.result().summaryNanos());
                json.addProperty("semanticSummary", sample.result().detail());
            }
            if (sample.error() != null) {
                json.addProperty("error", sample.error());
            }
            return json;
        }

        private static void addTiming(JsonObject json, String name, long nanos) {
            if (nanos >= 0L) {
                json.addProperty(name, nanos);
            }
        }
    }
}
