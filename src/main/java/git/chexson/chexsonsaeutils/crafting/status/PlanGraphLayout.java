package git.chexson.chexsonsaeutils.crafting.status;

import java.util.*;

/** 非递归强连通分量布局：最终目标在顶部，循环物料按不可拆分区块聚集排列。 */
public record PlanGraphLayout(List<Position> positions, int width, int height) {
    public static final int NODE_SIZE = 30;
    public record Position(int x, int y, int layer, boolean cycle) {}
    public static PlanGraphLayout create(PlanGraph graph) {
        int n = graph.nodes().size();
        var forward = adjacency(n); var reverse = adjacency(n);
        for (var e : graph.edges()) { forward.get(e.from()).add(e.to()); reverse.get(e.to()).add(e.from()); }
        var seen = new boolean[n]; var order = new ArrayList<Integer>();
        for (int root = 0; root < n; root++) {
            if (seen[root]) continue;
            var stack = new ArrayDeque<Integer>(); stack.push(root);
            while (!stack.isEmpty()) {
                int v = stack.pop();
                if (v < 0) { order.add(~v); continue; }
                if (seen[v]) continue; seen[v] = true; stack.push(~v);
                for (int next : forward.get(v)) if (!seen[next]) stack.push(next);
            }
        }
        int[] group = new int[n]; Arrays.fill(group, -1); var sizes = new ArrayList<Integer>();
        for (int i = order.size() - 1; i >= 0; i--) {
            int root = order.get(i); if (group[root] >= 0) continue;
            int id = sizes.size(), size = 0; var stack = new ArrayDeque<Integer>(); stack.push(root); group[root] = id;
            while (!stack.isEmpty()) { int v = stack.pop(); size++;
                for (int next : reverse.get(v)) if (group[next] < 0) { group[next] = id; stack.push(next); }
            } sizes.add(size);
        }
        var links = new ArrayList<Set<Integer>>(); for (int i = 0; i < sizes.size(); i++) links.add(new HashSet<>());
        int[] degree = new int[sizes.size()], layer = new int[sizes.size()];
        boolean[] cycle = new boolean[sizes.size()];
        for (int i = 0; i < cycle.length; i++) cycle[i] = sizes.get(i) > 1;
        for (var e : graph.edges()) { int a = group[e.from()], b = group[e.to()];
            if (a == b) { if (e.from() == e.to()) cycle[a] = true; }
            else if (links.get(a).add(b)) degree[b]++;
        }
        var queue = new ArrayDeque<Integer>(); for (int i = 0; i < degree.length; i++) if (degree[i] == 0) queue.add(i);
        while (!queue.isEmpty()) { int a = queue.remove(); for (int b : links.get(a)) {
            layer[b] = Math.max(layer[b], layer[a] + 1); if (--degree[b] == 0) queue.add(b);
        } }
        // 原始节点编号交错时，逐节点排列会拆散同一环；以 SCC 为不可拆分区块排列。
        var members = adjacency(sizes.size());
        for (int i = 0; i < n; i++) members.get(group[i]).add(i);
        int maxLayer = Arrays.stream(layer).max().orElse(0);
        int target = n == 0 ? -1 : group[0];
        var rows = new TreeMap<Integer, List<Integer>>();
        for (int id = 0; id < sizes.size(); id++) {
            // 最终目标固定在顶部；其他终端副产物在下一排，材料按生产方向逐排向下。
            int row = id == target ? 0 : maxLayer - layer[id] + 1;
            rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(id);
        }
        var widths = new HashMap<Integer, Integer>(); int widest = 0;
        for (var entry : rows.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(id -> members.get(id).getFirst()));
            int width = -80;
            for (int id : entry.getValue()) width += (members.get(id).size() - 1) * 80 + NODE_SIZE + 80;
            widths.put(entry.getKey(), width); widest = Math.max(widest, width);
        }
        var positions = new Position[n]; int rowIndex = 0;
        for (var entry : rows.entrySet()) {
            int x = 24 + (widest - widths.get(entry.getKey())) / 2, y = 24 + rowIndex++ * 110;
            for (int id : entry.getValue()) {
                for (int node : members.get(id)) { positions[node] = new Position(x, y, layer[id], cycle[id]); x += 80; }
                x += NODE_SIZE; // 区块间额外留白，与同一环内部间距区分。
            }
        }
        return new PlanGraphLayout(List.of(positions), widest + 72, Math.max(0, rowIndex - 1) * 110 + NODE_SIZE + 72);
    }

    private static List<List<Integer>> adjacency(int n) { var a = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) a.add(new ArrayList<>()); return a; }
}
