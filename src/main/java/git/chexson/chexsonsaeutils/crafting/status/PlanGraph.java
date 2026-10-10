package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.math.BigInteger;
import java.util.*;

/** 预览专用不可变生产关系；候选输入不是实际扣料，依赖层级不是执行时间线。 */
public record PlanGraph(List<Node> nodes, List<Edge> edges, String problem) {
    public PlanGraph(List<Node> nodes, List<Edge> edges) { this(nodes, edges, ""); }
    public static final PlanGraph EMPTY = new PlanGraph(List.of(), List.of());
    public PlanGraph {
        nodes = List.copyOf(nodes); edges = List.copyOf(edges); Objects.requireNonNull(problem);
        if (nodes.size() > 20000 || edges.size() > 60000) throw new IllegalArgumentException("合成图规模超过传输限制");
        for (var e : edges) if (e.from < 0 || e.to < 0 || e.from >= nodes.size() || e.to >= nodes.size())
            throw new IllegalArgumentException("合成图边引用无效");
    }
    /** 图解释失败不得中断核心规划；明确同步原因并记录日志，不伪造替代关系。 */
    public static void attach(appeng.menu.me.crafting.CraftingPlanSummary summary, ICraftingPlan plan) {
        if (!(summary instanceof EnhancedCraftingPlanSummary enhanced)) return;
        try { enhanced.chexsonsaeutils$setGraph(fromPlan(plan)); }
        catch (RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().error("构建合成预览图失败", error);
            enhanced.chexsonsaeutils$setGraph(new PlanGraph(List.of(), List.of(), "构建合成图失败：" + error.getMessage()));
        }
    }
    /** 物料显示三分量与目标数量；样板节点的 amount 是精确执行份数。 */
    public record Node(AEKey key, boolean pattern, BigInteger amount, BigInteger stored, BigInteger missing) {
        public Node { Objects.requireNonNull(key); positive(amount); positive(stored); positive(missing); }
    }
    /** input 表示输入边；alternative 表示该槽有多个候选，amount 是每份量乘执行份数。 */
    public record Edge(int from, int to, BigInteger amount, boolean input, boolean alternative, boolean remainder) {
        public Edge(int from, int to, BigInteger amount, boolean input, boolean alternative) { this(from, to, amount, input, alternative, false); }
        public Edge { positive(amount); }
    }
    private static void positive(BigInteger n) {
        if (n == null || n.signum() < 0) throw new IllegalArgumentException("合成图数量无效");
    }
    /** 从最终选中样板与宽域账生成图，保留全部副产物和输入候选。 */
    public static PlanGraph fromPlan(ICraftingPlan plan) {
        var counts = PlanSummaryAmounts.fromPlan(plan);
        var nodes = new ArrayList<Node>(); var edges = new ArrayList<Edge>();
        var ids = new LinkedHashMap<AEKey, Integer>();
        java.util.function.Function<AEKey, Integer> material = key -> ids.computeIfAbsent(key, k -> {
            int id = nodes.size();
            var amount = k.equals(plan.finalOutput().what()) ? WideAmountPlanCarrier.resolveFinalOutput(plan).exactValue()
                    : counts.crafting().getOrDefault(k, BigInteger.ZERO);
            nodes.add(new Node(k, false, amount, counts.stored().getOrDefault(k, BigInteger.ZERO),
                    counts.missing().getOrDefault(k, BigInteger.ZERO))); return id;
        });
        material.apply(plan.finalOutput().what());
        var keys = new HashSet<AEKey>(); keys.addAll(counts.stored().keySet()); keys.addAll(counts.missing().keySet());
        keys.stream().sorted(Comparator.comparing(k -> k.toString())).forEach(material::apply);
        var times = new LinkedHashMap<>(WideAmountPlanCarrier.resolveWideCrafts(plan).entries());
        plan.patternTimes().forEach((p, n) -> times.putIfAbsent(p, BigInteger.valueOf(n)));
        times.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getDefinition().toString())).forEach(entry -> {
            var p = entry.getKey(); var n = entry.getValue(); if (n.signum() == 0) return;
            int id = nodes.size(); nodes.add(new Node(p.getDefinition(), true, n, BigInteger.ZERO, BigInteger.ZERO));
            var selected = plan instanceof git.chexson.chexsonsaeutils.crafting.recursive.SelectedInputsPlan selection
                    ? selection.chexsonsaeutils$dyeableSelectedPatternInputs().get(p.getDefinition()) : null;
            if (selected != null && !selected.isEmpty()) selected.forEach((key, amount) -> edges.add(new Edge(material.apply(key), id,
                    n.multiply(BigInteger.valueOf(amount)), true, false)));
            else for (var input : p.getInputs()) {
                var possible = input.getPossibleInputs();
                for (var stack : possible) edges.add(new Edge(material.apply(stack.what()), id,
                        n.multiply(BigInteger.valueOf(stack.amount())).multiply(BigInteger.valueOf(input.getMultiplier())), true, possible.length > 1));
            }
            // AE2 退料按槽倍率返回一个容器，不能把输入模板的 amount 再乘进退料。
            for (var input : p.getInputs()) for (var candidate : input.getPossibleInputs()) {
                if (selected != null && !selected.isEmpty() && !selected.containsKey(candidate.what())) continue;
                var remainder = input.getRemainingKey(candidate.what());
                if (remainder != null) edges.add(new Edge(id, material.apply(remainder),
                        n.multiply(BigInteger.valueOf(input.getMultiplier())), false, input.getPossibleInputs().length > 1, true));
            }
            for (var stack : p.getOutputs()) edges.add(new Edge(id, material.apply(stack.what()),
                    n.multiply(BigInteger.valueOf(stack.amount())), false, false));
        });
        return new PlanGraph(nodes, edges);
    }
    /** 同摘要同步完整快照，数量始终以精确十进制编码。 */
    public void write(RegistryFriendlyByteBuf b) {
        // 摘要包还携带 AE2 的物料行，图扩展单独限额，避免大图使预览断线。
        var temporary = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), b.registryAccess());
        try {
            writeBody(temporary);
            if (temporary.readableBytes() > 524288) {
                com.mojang.logging.LogUtils.getLogger().warn("合成图同步超过 512 KiB：{} 字节", temporary.readableBytes());
                b.writeUtf("合成图超过 512 KiB 同步上限"); b.writeVarInt(0); b.writeVarInt(0);
            } else { b.writeUtf(problem); b.writeBytes(temporary); }
        } finally { temporary.release(); }
    }
    private void writeBody(RegistryFriendlyByteBuf b) {
        b.writeVarInt(nodes.size());
        for (var n : nodes) { AEKey.writeKey(b, n.key); b.writeBoolean(n.pattern); number(b, n.amount); number(b, n.stored); number(b, n.missing); }
        b.writeVarInt(edges.size());
        for (var e : edges) { b.writeVarInt(e.from); b.writeVarInt(e.to); number(b, e.amount); b.writeBoolean(e.input); b.writeBoolean(e.alternative); b.writeBoolean(e.remainder); }
    }
    public static PlanGraph read(RegistryFriendlyByteBuf b) {
        String problem = b.readUtf();
        int size = bounded(b.readVarInt(), 20000); var nodes = new ArrayList<Node>(size);
        for (int i = 0; i < size; i++) nodes.add(new Node(AEKey.readKey(b), b.readBoolean(), number(b), number(b), number(b)));
        size = bounded(b.readVarInt(), 60000); var edges = new ArrayList<Edge>(size);
        for (int i = 0; i < size; i++) edges.add(new Edge(b.readVarInt(), b.readVarInt(), number(b), b.readBoolean(), b.readBoolean(), b.readBoolean()));
        return new PlanGraph(nodes, edges, problem);
    }
    private static int bounded(int n, int max) { if (n < 0 || n > max) throw new IllegalArgumentException("合成图计数无效"); return n; }
    private static void number(RegistryFriendlyByteBuf b, BigInteger n) { b.writeUtf(n.toString(), 4096); }
    private static BigInteger number(RegistryFriendlyByteBuf b) { var n = new BigInteger(b.readUtf(4096)); positive(n); return n; }
}
