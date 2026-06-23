package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.GenericStack;
import appeng.menu.guisync.GuiSync;
import appeng.menu.guisync.PacketWritable;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.crafting.parallelcpu.ParallelCraftingCPU;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

public class ParallelCraftingCPUMenu extends CraftingCPUMenu {

    private static final String ACTION_SELECT_CPU = "selectCpu";
    private static final CraftingCpuList EMPTY_CPU_LIST = new CraftingCpuList(Collections.emptyList());
    private static final Comparator<CraftingCpuListEntry> CPU_COMPARATOR = Comparator
            .comparing((CraftingCpuListEntry entry) -> entry.currentJob() == null)
            .thenComparing((CraftingCpuListEntry entry) -> entry.name() == null)
            .thenComparing(entry -> entry.name() != null ? entry.name().getString() : "")
            .thenComparingInt(CraftingCpuListEntry::serial);

    public static final MenuType<ParallelCraftingCPUMenu> TYPE = MenuTypeBuilder
            .create(ParallelCraftingCPUMenu::new, AE2ParallelCpuToolBlockEntity.class)
            .withMenuTitle(ignored -> Component.translatable("block.chexsonsaeutils.ae2_parallel_cpu_tool"))
            .build(Chexsonsaeutils.MODID);

    private final WeakHashMap<ICraftingCPU, Integer> cpuSerialMap = new WeakHashMap<>();
    private int nextCpuSerial = 1;
    private List<ParallelCraftingCPU> lastCpuSet = List.of();
    private int lastUpdate;
    private final AE2ParallelCpuToolBlockEntity host;

    @GuiSync(15)
    public CraftingCpuList cpuList = EMPTY_CPU_LIST;

    @Nullable
    private ICraftingCPU selectedCpu;

    @GuiSync(16)
    private int selectedCpuSerial = -1;

    @GuiSync(17)
    public CpuSelectionMode selectionMode = CpuSelectionMode.ANY;

    public ParallelCraftingCPUMenu(
            int id,
            Inventory playerInventory,
            AE2ParallelCpuToolBlockEntity host
    ) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        this.cpuList = EMPTY_CPU_LIST;
        this.selectedCpu = null;
        this.selectedCpuSerial = -1;
        this.selectionMode = host.getParallelCpuCluster().getSelectionMode();
        registerClientAction(ACTION_SELECT_CPU, Integer.class, this::selectCpu);
    }

    public AE2ParallelCpuToolBlockEntity getParallelCpuHost() {
        return host;
    }

    @Override
    protected void setCPU(ICraftingCPU c) {
        super.setCPU(c);
        this.selectedCpu = c;
        this.selectedCpuSerial = getOrAssignCpuSerial(c);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            refreshCpuList();
            clearSelectionIfMissing();
            selectDefaultCpuIfNeeded();
            selectionMode = host.getParallelCpuCluster().getSelectionMode();
        }
        super.broadcastChanges();
    }

    @Override
    public boolean allowConfiguration() {
        return false;
    }

    public int getSelectedCpuSerial() {
        return selectedCpuSerial;
    }

    public CpuSelectionMode getSelectionMode() {
        return selectionMode;
    }

    public void selectCpu(int serial) {
        if (isClientSide()) {
            selectedCpuSerial = serial;
            sendClientAction(ACTION_SELECT_CPU, serial);
            return;
        }

        ICraftingCPU newSelectedCpu = null;
        if (serial != -1) {
            for (ParallelCraftingCPU cpu : lastCpuSet) {
                if (cpuSerialMap.getOrDefault(cpu, -1) == serial) {
                    newSelectedCpu = cpu;
                    break;
                }
            }
        }

        if (newSelectedCpu != selectedCpu) {
            setCPU(newSelectedCpu);
        }
    }

    private void refreshCpuList() {
        if (host == null) {
            return;
        }

        List<ParallelCraftingCPU> visibleCpus = new ArrayList<>();
        host.getParallelCpuCluster().appendActiveVisibleCpus(visibleCpus);
        if (host.getParallelCpuCluster().canAdvertiseRemainingCapacityCpu()) {
            visibleCpus.add(host.getParallelCpuCluster().remainingCapacityCpu());
        }

        if (!lastCpuSet.equals(visibleCpus) || ++lastUpdate >= 20) {
            lastCpuSet = List.copyOf(visibleCpus);
            cpuList = createCpuList();
            lastUpdate = 0;
        }
    }

    private void clearSelectionIfMissing() {
        if (selectedCpuSerial == -1) {
            return;
        }
        if (cpuList.cpus().stream().noneMatch(cpu -> cpu.serial() == selectedCpuSerial)) {
            selectCpu(-1);
        }
    }

    private void selectDefaultCpuIfNeeded() {
        if (selectedCpuSerial != -1) {
            return;
        }
        for (CraftingCpuListEntry cpu : cpuList.cpus()) {
            if (cpu.currentJob() != null) {
                selectCpu(cpu.serial());
                return;
            }
        }
        if (!cpuList.cpus().isEmpty()) {
            selectCpu(cpuList.cpus().get(0).serial());
        }
    }

    private CraftingCpuList createCpuList() {
        ArrayList<CraftingCpuListEntry> entries = new ArrayList<>(lastCpuSet.size());
        for (ParallelCraftingCPU cpu : lastCpuSet) {
            int serial = getOrAssignCpuSerial(cpu);
            var status = cpu.getJobStatus();
            float progress = 0.0F;
            if (status != null && status.totalItems() > 0L) {
                progress = (float) (status.progress() / (double) status.totalItems());
            }
            entries.add(new CraftingCpuListEntry(
                    serial,
                    cpu.getAvailableStorage(),
                    cpu.getCoProcessors(),
                    cpu.getName(),
                    cpu.getSelectionMode(),
                    status != null ? status.crafting() : null,
                    progress,
                    status != null ? status.elapsedTimeNanos() : 0L
            ));
        }
        entries.sort(CPU_COMPARATOR);
        return new CraftingCpuList(entries);
    }

    private int getOrAssignCpuSerial(@Nullable ICraftingCPU cpu) {
        if (cpu == null) {
            return -1;
        }
        return cpuSerialMap.computeIfAbsent(cpu, ignored -> nextCpuSerial++);
    }

    public record CraftingCpuList(List<CraftingCpuListEntry> cpus) implements PacketWritable {
        public CraftingCpuList(FriendlyByteBuf data) {
            this(readFromPacket(data));
        }

        private static List<CraftingCpuListEntry> readFromPacket(FriendlyByteBuf data) {
            int count = data.readInt();
            ArrayList<CraftingCpuListEntry> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(CraftingCpuListEntry.readFromPacket(data));
            }
            return result;
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
            data.writeInt(cpus.size());
            for (CraftingCpuListEntry entry : cpus) {
                entry.writeToPacket(data);
            }
        }
    }

    public record CraftingCpuListEntry(
            int serial,
            long storage,
            int coProcessors,
            @Nullable Component name,
            CpuSelectionMode mode,
            @Nullable GenericStack currentJob,
            float progress,
            long elapsedTimeNanos
    ) {
        public static CraftingCpuListEntry readFromPacket(FriendlyByteBuf data) {
            return new CraftingCpuListEntry(
                    data.readInt(),
                    data.readLong(),
                    data.readInt(),
                    data.readBoolean() ? Component.Serializer.fromJson(data.readUtf()) : null,
                    data.readEnum(CpuSelectionMode.class),
                    GenericStack.readBuffer(data),
                    data.readFloat(),
                    data.readVarLong());
        }

        public void writeToPacket(FriendlyByteBuf data) {
            data.writeInt(serial);
            data.writeLong(storage);
            data.writeInt(coProcessors);
            data.writeBoolean(name != null);
            if (name != null) {
                data.writeUtf(Component.Serializer.toJson(name));
            }
            data.writeEnum(mode);
            GenericStack.writeBuffer(currentJob, data);
            data.writeFloat(progress);
            data.writeVarLong(elapsedTimeNanos);
        }
    }
}
