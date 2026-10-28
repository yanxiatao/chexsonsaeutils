package git.chexson.chexsonsaeutils.network;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedSignature;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeImportedStack;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternEncoderUpdatePayload;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternSlotChangePacket;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntriesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixMatchesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStacksPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixVisibleRequestPayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStatePayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStateRequestPayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderUpdatePayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网络协议的行为场景；由类别测试统一调用。 */
public final class ProtocolScenarios {
    private ProtocolScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network",
                () -> NetworkPayloadByteRoundtripD6Cases.verify(temporaryRoot)
        );
    }

    /** NetworkPayloadByteRoundtripD6Cases 的独立场景夹具，按类别统一执行。 */
    public static final class NetworkPayloadByteRoundtripD6Cases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        private static final List<RegistryFriendlyByteBuf> BUFFERS = new ArrayList<>();
        private static final RegistryFriendlyByteBuf[] BUF_HOLDER = new RegistryFriendlyByteBuf[1];
        private static final String[] BUF_JSON = new String[1];
        private static final PatternMatrixMatchesPayload[] BUF_MATCHES = new PatternMatrixMatchesPayload[1];
        private static final MachineRecipeImportedSignature[] BUF_SIG = new MachineRecipeImportedSignature[1];

        void releaseBuffers() {
            for (var buffer : BUFFERS) {
                buffer.release();
            }
            BUFFERS.clear();
            BUF_HOLDER[0] = null;
            BUF_JSON[0] = null;
            BUF_MATCHES[0] = null;
            BUF_SIG[0] = null;
        }

        private static RegistryFriendlyByteBuf buffer() {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
            BUFFERS.add(buffer);
            return buffer;
        }
        private static RegistryFriendlyByteBuf buffer0() {
            return BUF_HOLDER[0] = buffer();
        }
        private interface ThrowingProbe {
            void run() throws Throwable;
        }
        private interface ThrowingCall {
            Object call() throws Throwable;
        }
        private interface PayloadBody {
            void run(RegistryFriendlyByteBuf buffer) throws Throwable;
        }
        private static void check(String name, Object expected, Object actual) {
            if (expected instanceof int[] a && actual instanceof int[] b) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, name);
            } else if (expected instanceof long[] a && actual instanceof long[] b) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, name);
            } else {
                assertEquals(expected, actual, name);
            }
        }
        private static void roundtripOne(String name, PayloadBody body)
                throws Throwable {
            var buffer = buffer();
            body.run(buffer);
            assertEquals(0, buffer.readableBytes(), name + " 未消费完编码字节");
        }
        private static String observe(ThrowingProbe write, ThrowingCall read) throws Throwable {
            if (write != null) {
                try {
                    write.run();
                } catch (Exception failure) {
                    return "write-threw:" + failure;
                }
            }
            try {
                Object value = read.call();
                String result = String.valueOf(value);
                return result.startsWith("field-diff") ? result : "read-accepted:" + result;
            } catch (Exception failure) {
                return "read-threw:" + failure;
            }
        }
        private static void regProbe(String gate, String field,
                ThrowingProbe write, ThrowingCall read, String expected) throws Throwable {
            String observed = observe(write, read);
            assertTrue(observed.startsWith(expected), gate + " " + field + " " + observed);
        }
        private static void siteProbe(String field,
                ThrowingProbe write, ThrowingCall read) throws Throwable {
            regProbe( "载荷写入边界", field, write, read, "write-threw");
        }
        private static void unboundedProbe(String field,
                ThrowingProbe write, ThrowingCall read) throws Throwable {
            String expected = field.startsWith("MachineRecipeConfigImportRequest.")
                    || field.startsWith("MachineRecipeImportedSignature.") ? "write-threw" : "read-accepted";
            regProbe( "大载荷", field, write, read, expected);
        }

        void byteRoundtripAndOvercapProbesJudgeAllElevenPayloads() throws Throwable {
            MachineRecipeImportedSignature sig = new MachineRecipeImportedSignature(
                    ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_type"),
                    List.of(new MachineRecipeImportedStack(AEItemKey.of(Items.GLASS), 3L)),
                    List.of(new MachineRecipeImportedStack(AEFluidKey.of(new FluidStack(Fluids.WATER, 1)), 5L)));
            String canonicalJson = MachineRecipeImportedSignature.toJson(REGISTRIES, List.of(sig));
            MachineRecipeConfigImportRequest legalRequest = new MachineRecipeConfigImportRequest(
                    ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_machine_item"),
                    ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_machine_block"),
                    List.of(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_recipe")),
                    7, "direct", "any", true, canonicalJson);

            roundtripOne( "DirectProcessingJeiImportPayload", (buf) -> {
                DirectProcessingJeiImportPayload payload = new DirectProcessingJeiImportPayload(11, legalRequest);
                DirectProcessingJeiImportPayload.STREAM_CODEC.encode(buf, payload);
                DirectProcessingJeiImportPayload back = DirectProcessingJeiImportPayload.STREAM_CODEC.decode(buf);
                check( "DirectProcessingJeiImportPayload.containerId", 11, back.containerId());
                check( "DirectProcessingJeiImportPayload.request", true,
                        payload.request().equals(back.request()));
                MachineRecipeConfigImportRequest rb = back.request();
                check( "MachineRecipeConfigImportRequest.machineItemId", legalRequest.machineItemId(),
                        rb.machineItemId());
                check( "MachineRecipeConfigImportRequest.machineBlockId", legalRequest.machineBlockId(),
                        rb.machineBlockId());
                check( "MachineRecipeConfigImportRequest.recipeTypeIds", legalRequest.recipeTypeIds(),
                        rb.recipeTypeIds());
                check( "MachineRecipeConfigImportRequest.defaultTicks", legalRequest.defaultTicks(),
                        rb.defaultTicks());
                check( "MachineRecipeConfigImportRequest.ioMode", legalRequest.ioMode(), rb.ioMode());
                check( "MachineRecipeConfigImportRequest.keyTypes", legalRequest.keyTypes(), rb.keyTypes());
                check( "MachineRecipeConfigImportRequest.enabled", legalRequest.enabled(), rb.enabled());
                check( "MachineRecipeConfigImportRequest.signatureHintsJson",
                        legalRequest.signatureHintsJson(), rb.signatureHintsJson());
            });

            roundtripOne( "CustomPatternSlotChangePacket", (buf) -> {
                CustomPatternSlotChangePacket payload = new CustomPatternSlotChangePacket(AEItemKey.of(Items.CHEST), 5);
                CustomPatternSlotChangePacket.STREAM_CODEC.encode(buf, payload);
                CustomPatternSlotChangePacket back = CustomPatternSlotChangePacket.STREAM_CODEC.decode(buf);
                check( "CustomPatternSlotChangePacket.key", payload.key(), back.key());
                check( "CustomPatternSlotChangePacket.slot", 5, back.slot());
            });

            roundtripOne( "CustomPatternEncoderUpdatePayload", (buf) -> {
                List<GenericStack> sparse = List.of(
                        new GenericStack(AEItemKey.of(Items.GLASS), 42L),
                        new GenericStack(AEFluidKey.of(new FluidStack(Fluids.LAVA, 2)), 7L));
                CustomPatternEncoderUpdatePayload payload = new CustomPatternEncoderUpdatePayload(
                        3, sparse, new int[]{1, -1, 3}, new int[]{9, 8}, true);
                CustomPatternEncoderUpdatePayload.STREAM_CODEC.encode(buf, payload);
                CustomPatternEncoderUpdatePayload back = CustomPatternEncoderUpdatePayload.STREAM_CODEC.decode(buf);
                check( "CustomPatternEncoderUpdatePayload.containerId", 3, back.containerId());
                check( "CustomPatternEncoderUpdatePayload.sparseInputs", sparse, back.sparseInputs());
                check( "CustomPatternEncoderUpdatePayload.slotMapping", new int[]{1, -1, 3},
                        back.slotMapping());
                check( "CustomPatternEncoderUpdatePayload.extractSlots", new int[]{9, 8}, back.extractSlots());
                check( "CustomPatternEncoderUpdatePayload.overflowStacks", true, back.overflowStacks());
            });

            roundtripOne( "PlanningOrderStateRequestPayload", (buf) -> {
                PlanningOrderStateRequestPayload payload = new PlanningOrderStateRequestPayload(123456789L);
                PlanningOrderStateRequestPayload.STREAM_CODEC.encode(buf, payload);
                PlanningOrderStateRequestPayload back = PlanningOrderStateRequestPayload.STREAM_CODEC.decode(buf);
                check( "PlanningOrderStateRequestPayload.requestId", 123456789L, back.requestId());
            });

            roundtripOne( "PlanningOrderUpdatePayload", (buf) -> {
                PlanningOrderUpdatePayload payload = new PlanningOrderUpdatePayload(9L, 2L,
                        List.of("GRAPH", "NATIVE"));
                PlanningOrderUpdatePayload.STREAM_CODEC.encode(buf, payload);
                PlanningOrderUpdatePayload back = PlanningOrderUpdatePayload.STREAM_CODEC.decode(buf);
                check( "PlanningOrderUpdatePayload.requestId", 9L, back.requestId());
                check( "PlanningOrderUpdatePayload.expectedRevision", 2L, back.expectedRevision());
                check( "PlanningOrderUpdatePayload.ids", payload.ids(), back.ids());
            });

            roundtripOne( "PlanningOrderStatePayload", (buf) -> {
                PlanningOrderStatePayload payload = new PlanningOrderStatePayload(
                        4L, PlanningOrderStatePayload.Result.REVISION_MISMATCH, 77L, true,
                        List.of("GRAPH", "NATIVE"));
                PlanningOrderStatePayload.STREAM_CODEC.encode(buf, payload);
                PlanningOrderStatePayload back = PlanningOrderStatePayload.STREAM_CODEC.decode(buf);
                check( "PlanningOrderStatePayload.requestId", 4L, back.requestId());
                check( "PlanningOrderStatePayload.result", payload.result(), back.result());
                check( "PlanningOrderStatePayload.revision", 77L, back.revision());
                check( "PlanningOrderStatePayload.editable", true, back.editable());
                check( "PlanningOrderStatePayload.ids", payload.ids(), back.ids());
            });

            roundtripOne( "PatternMatrixSearchPayload", (buf) -> {
                PatternMatrixSearchPayload payload = PatternMatrixSearchPayload.of(2, "glowstone");
                PatternMatrixSearchPayload.STREAM_CODEC.encode(buf, payload);
                PatternMatrixSearchPayload back = PatternMatrixSearchPayload.STREAM_CODEC.decode(buf);
                check( "PatternMatrixSearchPayload.containerId", 2, back.containerId());
                check( "PatternMatrixSearchPayload.query", "glowstone", back.query());
            });

            roundtripOne( "PatternMatrixVisibleRequestPayload", (buf) -> {
                PatternMatrixVisibleRequestPayload payload = new PatternMatrixVisibleRequestPayload(2,
                        List.of(0, 5, 9));
                PatternMatrixVisibleRequestPayload.STREAM_CODEC.encode(buf, payload);
                PatternMatrixVisibleRequestPayload back = PatternMatrixVisibleRequestPayload.STREAM_CODEC.decode(buf);
                check( "PatternMatrixVisibleRequestPayload.containerId", 2, back.containerId());
                check( "PatternMatrixVisibleRequestPayload.slots", payload.slots(), back.slots());
            });

            roundtripOne( "PatternMatrixEntriesPayload", (buf) -> {
                List<PatternMatrixEntry> entries = List.of(
                        new PatternMatrixEntry(3, new ItemStack(Items.CHEST, 2), 1, 2),
                        new PatternMatrixEntry(7, ItemStack.EMPTY, 0, 5));
                PatternMatrixEntriesPayload payload = new PatternMatrixEntriesPayload(4, 16000, entries);
                PatternMatrixEntriesPayload.STREAM_CODEC.encode(buf, payload);
                PatternMatrixEntriesPayload back = PatternMatrixEntriesPayload.STREAM_CODEC.decode(buf);
                check( "PatternMatrixEntriesPayload.containerId", 4, back.containerId());
                check( "PatternMatrixEntriesPayload.totalSlots", 16000, back.totalSlots());
                check( "PatternMatrixEntriesPayload.entries.size", 2, back.entries().size());
                for (int i = 0; i < Math.min(2, back.entries().size()); i++) {
                    PatternMatrixEntry e = entries.get(i);
                    PatternMatrixEntry b = back.entries().get(i);
                    check( "PatternMatrixEntry[" + i + "].slot", e.slot(), b.slot());
                    check( "PatternMatrixEntry[" + i + "].displayStack", true,
                            ItemStack.isSameItemSameComponents(e.displayStack(), b.displayStack()));
                    check( "PatternMatrixEntry[" + i + "].statusCode", e.statusCode(), b.statusCode());
                    check( "PatternMatrixEntry[" + i + "].reasonCode", e.reasonCode(), b.reasonCode());
                }
            });

            roundtripOne( "PatternMatrixMatchesPayload", (buf) -> {
                PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(5, 3, new long[]{0b101L, 0xFFL});
                PatternMatrixMatchesPayload.STREAM_CODEC.encode(buf, payload);
                PatternMatrixMatchesPayload back = PatternMatrixMatchesPayload.STREAM_CODEC.decode(buf);
                check( "PatternMatrixMatchesPayload.containerId", 5, back.containerId());
                check( "PatternMatrixMatchesPayload.matchCount", 3, back.matchCount());
                check( "PatternMatrixMatchesPayload.matchBits", payload.matchBits(), back.matchBits());
                check( "PatternMatrixMatchesPayload.bitsArray", new long[]{0b101L, 0xFFL}, back.bitsArray());
            });

            roundtripOne( "PatternMatrixRealStacksPayload", (buf) -> {
                List<PatternMatrixRealStack> stacks = List.of(
                        new PatternMatrixRealStack(1, new ItemStack(Items.GLASS)),
                        new PatternMatrixRealStack(2, ItemStack.EMPTY));
                PatternMatrixRealStacksPayload payload = new PatternMatrixRealStacksPayload(6, stacks);
                PatternMatrixRealStacksPayload.STREAM_CODEC.encode(buf, payload);
                PatternMatrixRealStacksPayload back = PatternMatrixRealStacksPayload.STREAM_CODEC.decode(buf);
                check( "PatternMatrixRealStacksPayload.containerId", 6, back.containerId());
                check( "PatternMatrixRealStacksPayload.stacks.size", 2, back.stacks().size());
                for (int i = 0; i < Math.min(2, back.stacks().size()); i++) {
                    check( "PatternMatrixRealStack[" + i + "].slot", stacks.get(i).slot(),
                            back.stacks().get(i).slot());
                    check( "PatternMatrixRealStack[" + i + "].stack", true,
                            ItemStack.isSameItemSameComponents(stacks.get(i).stack(), back.stacks().get(i).stack()));
                }
            });


            regProbe( "D6b-R1-result-read-bound",
                    "PlanningOrderStatePayload.result[40chars]",
                    null,
                    () -> {
                        RegistryFriendlyByteBuf b = buffer();
                        b.writeLong(1);
                        b.writeUtf("R".repeat(40));
                        b.writeLong(2);
                        b.writeBoolean(true);
                        b.writeVarInt(0);
                        return PlanningOrderStatePayload.STREAM_CODEC.decode(b);
                    },
                    "read-threw");
            regProbe( "D6b-R1-state-id-read-bound",
                    "PlanningOrderStatePayload.ids[70chars]",
                    null,
                    () -> {
                        RegistryFriendlyByteBuf b = buffer();
                        b.writeLong(1);
                        b.writeUtf("STATE");
                        b.writeLong(2);
                        b.writeBoolean(true);
                        b.writeVarInt(1);
                        b.writeUtf("x".repeat(70));
                        return PlanningOrderStatePayload.STREAM_CODEC.decode(b);
                    },
                    "read-threw");
            regProbe( "D6b-R1-state-id-write-bound",
                    "PlanningOrderStatePayload.ids[70chars-write]",
                    () -> PlanningOrderStatePayload.STREAM_CODEC.encode(buffer0(),
                            new PlanningOrderStatePayload(1, PlanningOrderStatePayload.Result.STATE, 2L, true,
                                    List.of("x".repeat(70)))),
                    () -> PlanningOrderStatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]),
                    "write-threw");
            regProbe( "D6b-R2-state-count-read-bound",
                    "PlanningOrderStatePayload.ids[count=17]",
                    null,
                    () -> {
                        RegistryFriendlyByteBuf b = buffer();
                        b.writeLong(1);
                        b.writeUtf("STATE");
                        b.writeLong(2);
                        b.writeBoolean(true);
                        b.writeVarInt(17);
                        for (int i = 0; i < 17; i++) {
                            b.writeUtf("GRAPH");
                        }
                        return PlanningOrderStatePayload.STREAM_CODEC.decode(b);
                    },
                    "read-threw");
            regProbe( "D6b-R2-state-count-write-bound",
                    "PlanningOrderStatePayload.ids[count=17-write]",
                    () -> PlanningOrderStatePayload.STREAM_CODEC.encode(buffer0(),
                            new PlanningOrderStatePayload(1, PlanningOrderStatePayload.Result.STATE, 2L, true,
                                    Collections.nCopies(17, "GRAPH"))),
                    () -> PlanningOrderStatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]),
                    "write-threw");
            regProbe( "D6b-R1-update-id-read-bound",
                    "PlanningOrderUpdatePayload.ids[70chars]",
                    null,
                    () -> {
                        RegistryFriendlyByteBuf b = buffer();
                        b.writeLong(1);
                        b.writeLong(2);
                        b.writeVarInt(1);
                        b.writeUtf("y".repeat(70));
                        return PlanningOrderUpdatePayload.STREAM_CODEC.decode(b);
                    },
                    "read-threw");
            regProbe( "D6b-R1-update-id-write-bound",
                    "PlanningOrderUpdatePayload.ids[70chars-write]",
                    () -> PlanningOrderUpdatePayload.STREAM_CODEC.encode(buffer0(),
                            new PlanningOrderUpdatePayload(1L, 2L, List.of("y".repeat(70)))),
                    () -> PlanningOrderUpdatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]),
                    "write-threw");
            regProbe( "D6b-R2-update-count-read-bound",
                    "PlanningOrderUpdatePayload.ids[count=17]",
                    null,
                    () -> {
                        RegistryFriendlyByteBuf b = buffer();
                        b.writeLong(1);
                        b.writeLong(2);
                        b.writeVarInt(17);
                        for (int i = 0; i < 17; i++) {
                            b.writeUtf("GRAPH");
                        }
                        return PlanningOrderUpdatePayload.STREAM_CODEC.decode(b);
                    },
                    "read-threw");
            regProbe( "D6b-R2-update-count-write-bound",
                    "PlanningOrderUpdatePayload.ids[count=17-write]",
                    () -> PlanningOrderUpdatePayload.STREAM_CODEC.encode(buffer0(),
                            new PlanningOrderUpdatePayload(1L, 2L, Collections.nCopies(17, "GRAPH"))),
                    () -> PlanningOrderUpdatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]),
                    "write-threw");
            siteProbe(
                    "PatternMatrixSearchPayload.query[200chars]",
                    () -> PatternMatrixSearchPayload.STREAM_CODEC.encode(buffer0(),
                            new PatternMatrixSearchPayload(2, "q".repeat(200))),
                    () -> {
                        PatternMatrixSearchPayload back = PatternMatrixSearchPayload.STREAM_CODEC.decode(BUF_HOLDER[0]);
                        if (back.query().length() != 200) {
                            return "field-diff(query " + 200 + "->" + back.query().length() + ")";
                        }
                        return back;
                    });
            siteProbe(
                    "PatternMatrixVisibleRequestPayload.slots[count=300]",
                    () -> {
                        Integer[] many = new Integer[300];
                        for (int i = 0; i < 300; i++) {
                            many[i] = i;
                        }
                        PatternMatrixVisibleRequestPayload.STREAM_CODEC.encode(BUF_HOLDER[0] = buffer(),
                                new PatternMatrixVisibleRequestPayload(2, List.of(many)));
                    },
                    () -> {
                        PatternMatrixVisibleRequestPayload back =
                                PatternMatrixVisibleRequestPayload.STREAM_CODEC.decode(BUF_HOLDER[0]);
                        if (back.slots().size() != 300) {
                            return "field-diff(slots 300->" + back.slots().size() + ")";
                        }
                        return back;
                    });
            regProbe( "D6b-R4-json-identity",
                    "MachineRecipeConfigImportRequest.signatureHintsJson[legacy-shape]",
                    () -> {
                        String legacyJson = "[{\"recipe_type\":\"chexsonsaeutils:probe\","
                                + "\"inputs\":[{\"key_id\":\"minecraft:glass\",\"key_type\":\"item\",\"amount\":3}],"
                                + "\"outputs\":[{\"key_id\":\"minecraft:water\",\"key_type\":\"fluid\",\"amount\":5}]}]";
                        MachineRecipeConfigImportRequest req = new MachineRecipeConfigImportRequest(
                                null, null, List.of(), 7, "direct", "any", true, legacyJson);
                        DirectProcessingJeiImportPayload payload = new DirectProcessingJeiImportPayload(11, req);
                        BUF_HOLDER[0] = buffer();
                        DirectProcessingJeiImportPayload.STREAM_CODEC.encode(BUF_HOLDER[0], payload);
                        BUF_JSON[0] = legacyJson;
                    },
                    () -> {
                        DirectProcessingJeiImportPayload back =
                                DirectProcessingJeiImportPayload.STREAM_CODEC.decode(BUF_HOLDER[0]);
                        if (BUF_JSON[0].equals(back.request().signatureHintsJson())) {
                            return back.request();
                        }
                        return "field-diff(write=" + BUF_JSON[0].length() + "chars,read="
                                + back.request().signatureHintsJson().length() + "chars)";
                    },
                    "read-accepted");
            regProbe( "D6b-R5-full-bitmap-through-wire",
                    "PatternMatrixMatchesPayload.matchBits[words=4]",
                    () -> {
                        BUF_MATCHES[0] = PatternMatrixMatchesPayload.of(1, 0, new long[4]);
                        BUF_HOLDER[0] = buffer();
                        PatternMatrixMatchesPayload.STREAM_CODEC.encode(BUF_HOLDER[0], BUF_MATCHES[0]);
                    },
                    () -> {
                        PatternMatrixMatchesPayload back =
                                PatternMatrixMatchesPayload.STREAM_CODEC.decode(BUF_HOLDER[0]);
                        if (back.matchBits().size() != 4 || !Arrays.equals(back.bitsArray(), new long[4])) {
                            return "field-diff(of() words 4->" + back.matchBits().size() + ")";
                        }
                        return back;
                    },
                    "read-accepted");


            regProbe( "D6b-R2b-vanilla-io-cap-witness",
                            "MachineRecipeImportedSignature.inputs[count=65]",
                            () -> {
                                List<MachineRecipeImportedStack> many = new ArrayList<>();
                                for (int i = 0; i < 65; i++) {
                                    many.add(new MachineRecipeImportedStack(AEItemKey.of(Items.GLASS), i + 1L));
                                }
                                BUF_SIG[0] = new MachineRecipeImportedSignature(
                                        ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_type"),
                                        many, List.of(new MachineRecipeImportedStack(AEItemKey.of(Items.CHEST), 1L)));
                                BUF_HOLDER[0] = buffer();
                                MachineRecipeImportedSignature.STREAM_CODEC.encode(BUF_HOLDER[0], BUF_SIG[0]);
                            },
                            () -> MachineRecipeImportedSignature.STREAM_CODEC.decode(BUF_HOLDER[0]),
                            "write-threw");

            unboundedProbe( "CustomPatternEncoderUpdatePayload.sparseInputs[300]",
                    () -> {
                        List<GenericStack> many = new ArrayList<>();
                        for (int i = 0; i < 300; i++) {
                            many.add(new GenericStack(AEItemKey.of(Items.GLASS), i + 1L));
                        }
                        BUF_HOLDER[0] = buffer();
                        CustomPatternEncoderUpdatePayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new CustomPatternEncoderUpdatePayload(1, many, new int[]{1}, new int[]{2}, false));
                    },
                    () -> CustomPatternEncoderUpdatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "CustomPatternEncoderUpdatePayload.slotMapping[300]",
                    () -> {
                        int[] big = new int[300];
                        Arrays.fill(big, 7);
                        BUF_HOLDER[0] = buffer();
                        CustomPatternEncoderUpdatePayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new CustomPatternEncoderUpdatePayload(1, List.of(), big, new int[]{1}, false));
                    },
                    () -> CustomPatternEncoderUpdatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "CustomPatternEncoderUpdatePayload.extractSlots[300]",
                    () -> {
                        int[] big = new int[300];
                        Arrays.fill(big, -2);
                        BUF_HOLDER[0] = buffer();
                        CustomPatternEncoderUpdatePayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new CustomPatternEncoderUpdatePayload(1, List.of(), new int[]{1}, big, false));
                    },
                    () -> CustomPatternEncoderUpdatePayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "MachineRecipeConfigImportRequest.recipeTypeIds[257]",
                    () -> {
                        List<ResourceLocation> ids = new ArrayList<>();
                        for (int i = 0; i < 257; i++) {
                            ids.add(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "r" + i));
                        }
                        BUF_HOLDER[0] = buffer();
                        MachineRecipeConfigImportRequest.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new MachineRecipeConfigImportRequest(null, null, ids, 7, "direct", "any", false, ""));
                    },
                    () -> MachineRecipeConfigImportRequest.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "MachineRecipeConfigImportRequest.ioMode[40000chars]",
                    () -> {
                        BUF_HOLDER[0] = buffer();
                        MachineRecipeConfigImportRequest.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new MachineRecipeConfigImportRequest(null, null, List.of(), 7,
                                        "i".repeat(40000), "any", false, ""));
                    },
                    () -> MachineRecipeConfigImportRequest.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "MachineRecipeConfigImportRequest.keyTypes[40000chars]",
                    () -> {
                        BUF_HOLDER[0] = buffer();
                        MachineRecipeConfigImportRequest.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new MachineRecipeConfigImportRequest(null, null, List.of(), 7, "direct",
                                        "k".repeat(40000), false, ""));
                    },
                    () -> MachineRecipeConfigImportRequest.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "PatternMatrixEntriesPayload.entries[300]",
                    () -> {
                        List<PatternMatrixEntry> many = new ArrayList<>();
                        for (int i = 0; i < 300; i++) {
                            many.add(new PatternMatrixEntry(i, new ItemStack(Items.GLASS), 0, 0));
                        }
                        BUF_HOLDER[0] = buffer();
                        PatternMatrixEntriesPayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new PatternMatrixEntriesPayload(1, 300, many));
                    },
                    () -> PatternMatrixEntriesPayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "PatternMatrixMatchesPayload.matchBits[300words]",
                    () -> {
                        Long[] words = new Long[300];
                        Arrays.fill(words, 0x5AL);
                        BUF_HOLDER[0] = buffer();
                        PatternMatrixMatchesPayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new PatternMatrixMatchesPayload(1, 1, List.of(words)));
                    },
                    () -> PatternMatrixMatchesPayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "PatternMatrixRealStacksPayload.stacks[300]",
                    () -> {
                        List<PatternMatrixRealStack> many = new ArrayList<>();
                        for (int i = 0; i < 300; i++) {
                            many.add(new PatternMatrixRealStack(i, new ItemStack(Items.GLASS)));
                        }
                        BUF_HOLDER[0] = buffer();
                        PatternMatrixRealStacksPayload.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new PatternMatrixRealStacksPayload(1, many));
                    },
                    () -> PatternMatrixRealStacksPayload.STREAM_CODEC.decode(BUF_HOLDER[0]));
            unboundedProbe( "MachineRecipeImportedSignature.outputs[65]",
                    () -> {
                        List<MachineRecipeImportedStack> many = new ArrayList<>();
                        for (int i = 0; i < 65; i++) {
                            many.add(new MachineRecipeImportedStack(AEItemKey.of(Items.CHEST), i + 1L));
                        }
                        BUF_HOLDER[0] = buffer();
                        MachineRecipeImportedSignature.STREAM_CODEC.encode(BUF_HOLDER[0],
                                new MachineRecipeImportedSignature(
                                        ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "probe_type"),
                                        List.of(new MachineRecipeImportedStack(AEItemKey.of(Items.GLASS), 1L)), many));
                    },
                    () -> MachineRecipeImportedSignature.STREAM_CODEC.decode(BUF_HOLDER[0]));

        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("NetworkPayloadByteRoundtripD6Cases",
                () -> org.junit.jupiter.api.Assertions.assertAll("NetworkPayloadByteRoundtripD6Cases.byteRoundtripAndOvercapProbesJudgeAllElevenPayloads", () -> {
                            var scenario = new NetworkPayloadByteRoundtripD6Cases();
                            try {
                                scenario.byteRoundtripAndOvercapProbesJudgeAllElevenPayloads();
                            } finally {
                                scenario.releaseBuffers();
                            }
                        })
            );
        }
    }
}
