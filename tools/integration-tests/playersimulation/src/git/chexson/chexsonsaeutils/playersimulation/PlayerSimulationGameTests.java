package git.chexson.chexsonsaeutils.playersimulation;

import git.chexson.chexsonsaeutils.parts.playersimulation.*;
import appeng.api.stacks.AEItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** 使用真实 ServerLevel 验证玩家入口，不模拟世界回调，也不使用反射。 */
@GameTestHolder("chexsonsaeutils")
@PrefixGameTestTemplate(false)
public final class PlayerSimulationGameTests {
    private static final BlockPos HOST = new BlockPos(2,2,1);
    private static final BlockPos FRONT = HOST.south();
    private static SimulationActor actor(GameTestHelper helper) {
        var actor = new SimulationActorImpl(helper.getLevel(),UUID.randomUUID(),new net.minecraft.nbt.CompoundTag());
        actor.position(helper.absolutePos(HOST),Direction.SOUTH,SimulationSettings.ClickFace.AUTO,false);
        return actor;
    }
    private static String act(SimulationActor actor,SimulationBuffer buffer,AEItemKey key,
                              SimulationSettings.Click click,SimulationSettings.Target target,List<ItemStack> returned) {
        actor.tickClock();
        var result = SimulationInteraction.execute(actor,buffer,key == null ? -1 : 0,1,i -> key,
                () -> actor.act(click,target,SimulationSettings.Cooldown.IGNORE,SimulationSettings.Press.HOLD,200,true),returned::add);
        if (!"using".equals(result) && !"mining".equals(result)) actor.cancel();
        return result;
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void miningUsesToolSpeedAndConsumesDurability(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.IRON_PICKAXE);
        buffer.set(0,key,new ItemStack(Items.IRON_PICKAXE));var returns = new ArrayList<ItemStack>();
        String initial = act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,returns);
        helper.assertTrue("mining".equals(initial),"石头必须持续挖掘，不能瞬间破坏");
        helper.assertBlockPresent(Blocks.STONE,FRONT);
        for (int i = 0; i < 30 && actor.busy(); i++) act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,returns);
        helper.assertBlockNotPresent(Blocks.STONE,FRONT);
        helper.assertTrue(buffer.get(0).getDamageValue() == 1,"正常挖掘必须损耗一次工具耐久");
        helper.assertTrue(returns.isEmpty(),"工具仍保留在缓存");
        helper.assertTrue(!actor.busy(),"完成挖掘后必须结束动作");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void emptyHandRightClickTogglesLever(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.LEVER.defaultBlockState());
        var actor = actor(helper);var buffer = new SimulationBuffer();
        act(actor,buffer,null,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,new ArrayList<>());
        helper.assertTrue(helper.getBlockState(FRONT).getValue(LeverBlock.POWERED),"空手右键应触发原版拉杆交互");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void entityAttackUsesMainHandAttributes(GameTestHelper helper) {
        Cow cow = helper.spawn(EntityType.COW,new net.minecraft.world.phys.Vec3(2.5,2,2.5));
        cow.setNoAi(true);float health = cow.getHealth();
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.IRON_SWORD);
        buffer.set(0,key,new ItemStack(Items.IRON_SWORD));
        act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.ENTITY,new ArrayList<>());
        helper.assertTrue(cow.getHealth() <= health - 5,"攻击必须使用铁剑属性，不能按空手伤害计算");
        helper.assertTrue(buffer.get(0).getDamageValue() == 1,"攻击应消耗铁剑耐久");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void entityRightClickReturnsMilkBucket(GameTestHelper helper) {
        Cow cow = helper.spawn(EntityType.COW,new net.minecraft.world.phys.Vec3(2.5,2,2.5));cow.setNoAi(true);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.BUCKET);
        buffer.set(0,key,new ItemStack(Items.BUCKET));var returned = new ArrayList<ItemStack>();
        act(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.ENTITY,returned);
        helper.assertTrue(buffer.get(0).isEmpty(),"挤奶应消耗缓存中的空桶");
        helper.assertTrue(returned.size() == 1 && returned.getFirst().is(Items.MILK_BUCKET),"挤奶必须返还真实奶桶");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void continuousUseSurvivesHandSettlement(GameTestHelper helper) {
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.MILK_BUCKET);
        buffer.set(0,key,new ItemStack(Items.MILK_BUCKET));var returned = new ArrayList<ItemStack>();
        act(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,returned);
        helper.assertTrue(actor.busy(),"饮用必须进入持续使用状态");
        for (int i = 0; i < 40 && actor.busy(); i++) {
            actor.tickClock();
            SimulationInteraction.execute(actor,buffer,0,1,n -> key,
                    () -> actor.act(SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,SimulationSettings.Cooldown.MATCH,SimulationSettings.Press.HOLD,40,true),returned::add);
        }
        helper.assertTrue(!actor.busy(),"饮用应自然完成，不能被默认 20 tick 的主动释放时间打断");
        helper.assertTrue(buffer.get(0).isEmpty(),"奶桶必须真实消耗");
        helper.assertTrue(returned.size() == 1 && returned.getFirst().is(Items.BUCKET),"持续使用结束应返还空桶");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void itemCooldownCanBeMatchedOrIgnored(GameTestHelper helper) {
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.ENDER_PEARL);
        buffer.set(0,key,new ItemStack(Items.ENDER_PEARL,4));var returned = new ArrayList<ItemStack>();
        act(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,returned);
        helper.assertTrue(buffer.get(0).getCount() == 3,"首次使用必须消耗一颗珍珠");
        String state = SimulationInteraction.execute(actor,buffer,0,1,i -> key,
                () -> actor.act(SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,SimulationSettings.Cooldown.MATCH,SimulationSettings.Press.HOLD,40,true),returned::add);
        helper.assertTrue("cooldown".equals(state) && buffer.get(0).getCount() == 3,"匹配 CD 时不能再次发射");
        act(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,returned);
        helper.assertTrue(buffer.get(0).getCount() == 2,"忽略 CD 时可再次执行实际物品使用");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void cancelledLeftClickDoesNotBreakOrDamageTool(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.IRON_PICKAXE);
        buffer.set(0,key,new ItemStack(Items.IRON_PICKAXE));
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock> listener = event -> {
            if (event.getPos().equals(helper.absolutePos(FRONT))) event.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try {
            String state = act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>());
            helper.assertTrue("blocked".equals(state),"取消的事件必须阻止操作");
            helper.assertBlockPresent(Blocks.STONE,FRONT);
            helper.assertTrue(!actor.busy() && buffer.get(0).getDamageValue() == 0,"取消后不能持续挖掘或消耗工具");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=150)
    @SuppressWarnings("unchecked")
    public static void networkRefillsConfiguredBufferAndRedstoneGatesUse(GameTestHelper helper) {
        helper.setBlock(HOST,appeng.core.definitions.AEBlocks.CABLE_BUS.block());
        helper.setBlock(HOST.west(),appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        var host = (appeng.blockentity.networking.CableBusBlockEntity) helper.getBlockEntity(HOST);
        host.addPart(appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT),null,null);
        var item = (appeng.api.parts.IPartItem<PlayerSimulationPart>) git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent.PLAYER_SIMULATION_PLANE_ITEM.get();
        PlayerSimulationPart part = host.addPart(item,Direction.SOUTH,null);
        helper.assertTrue(part != null,"面板必须可安装到真实线缆宿主");
        part.cycle("target"); // 面向没有实体的前方，先只验证补货。
        part.getUpgrades().setItemDirect(0,appeng.core.definitions.AEItems.REDSTONE_CARD.stack());
        part.getConfigManager().putSetting(appeng.api.config.Settings.REDSTONE_CONTROLLED,appeng.api.config.RedstoneMode.HIGH_SIGNAL);
        var key = AEItemKey.of(Items.SNOWBALL);
        part.getConfig().setStack(0,new appeng.api.stacks.GenericStack(key,4));
        var stock = appeng.util.ConfigInventory.storage(1).supportedType(appeng.api.stacks.AEKeyType.items()).build();
        stock.setStack(0,new appeng.api.stacks.GenericStack(key,16));
        helper.runAfterDelay(30,() -> {
            helper.assertTrue(part.getMainNode().isActive(),"真实网络必须供电并分配频道");
            part.getMainNode().getGrid().getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(stock));
            helper.runAfterDelay(25,() -> {
                helper.assertTrue(part.bufferedAmount(0) == 4 && stock.getAmount(0) == 12,"必须按标记数量从真实 ME 网络补货");
                helper.assertTrue("redstone".equals(part.status()),"低电平必须阻止 HIGH_SIGNAL 面板动作");
                part.cycle("target"); // ENTITY -> AIR，使用雪花球。
                helper.setBlock(HOST.east(),Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(25,() -> {
                    helper.assertTrue(stock.getAmount(0) + part.bufferedAmount(0) < 16,"红石启用后必须实际使用缓存物品");
                    helper.assertTrue(!"error".equals(part.status()),"完整网络路径不能因运行异常停机");
                    helper.succeed();
                });
            });
        });
    }
    private static ItemStack enchanted(GameTestHelper helper,net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchant,int level) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.enchant(helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(enchant),level);
        return stack;
    }
    private static int mineTicks(GameTestHelper helper,ItemStack tool) {
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(tool);
        buffer.set(0,key,tool);
        int ticks = 0;
        do {
            act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>());
            ticks++;
        } while (actor.busy() && ticks < 100);
        helper.assertBlockNotPresent(Blocks.STONE,FRONT);
        return ticks;
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void efficiencyImprovesMiningAndDoesNotLeakToNextTool(GameTestHelper helper) {
        int normal = mineTicks(helper,new ItemStack(Items.IRON_PICKAXE));
        int efficient = mineTicks(helper,enchanted(helper,net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY,5));
        helper.assertTrue(efficient < normal,"效率附魔必须实际加快持续挖掘");
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.IRON_PICKAXE);
        buffer.set(0,key,enchanted(helper,net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY,5));
        act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>());
        actor.cancel();
        helper.setBlock(FRONT,Blocks.STONE);
        buffer.set(0,key,new ItemStack(Items.IRON_PICKAXE));
        int ticks = 0;
        do { act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>()); ticks++; } while (actor.busy() && ticks < 100);
        helper.assertTrue(ticks == normal,"换成普通工具必须移除先前效率属性");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void silkTouchUsesActualCachedEnchantment(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.STONE);
        var tool = enchanted(helper,net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH,1);
        var buffer = new SimulationBuffer();var marker = AEItemKey.of(Items.IRON_PICKAXE);
        buffer.set(0,marker,tool);
        var actor = actor(helper);
        for (int i = 0; i < 100 && helper.getBlockState(FRONT).is(Blocks.STONE); i++)
            act(actor,buffer,marker,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>());
        var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(FRONT)).inflate(1));
        helper.assertTrue(drops.stream().anyMatch(e -> e.getItem().is(Items.STONE)),"精准采集必须掉落石头而非圆石");
        helper.assertTrue(buffer.get(0).isEnchanted() && buffer.get(0).getDamageValue() == 1,"结算必须保留实际附魔和耐久");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void fortuneAndUnbreakingAffectNativeMining(GameTestHelper helper) {
        var tool = enchanted(helper,net.minecraft.world.item.enchantment.Enchantments.FORTUNE,3);
        tool.enchant(helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING),3);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(tool);
        buffer.set(0,key,tool);
        helper.getLevel().getRandom().setSeed(12345L);
        actor.inventory().player.getRandom().setSeed(12345L);
        for (int block = 0; block < 16; block++) {
            helper.setBlock(FRONT,Blocks.DIAMOND_ORE);
            for (int tick = 0; tick < 100 && helper.getBlockState(FRONT).is(Blocks.DIAMOND_ORE); tick++)
                act(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,new ArrayList<>());
            helper.assertBlockNotPresent(Blocks.DIAMOND_ORE,FRONT);
        }
        int diamonds = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(FRONT)).inflate(1)).stream()
                .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(diamonds > 16,"固定随机种子下时运必须增加矿物掉落");
        helper.assertTrue(buffer.get(0).getDamageValue() < 16,"固定随机种子下耐久附魔必须减少工具损耗");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=180)
    @SuppressWarnings("unchecked")
    public static void fuzzyNetworkKeepsActualComponentsAndQuantityMenuSaves(GameTestHelper helper) {
        helper.setBlock(HOST,appeng.core.definitions.AEBlocks.CABLE_BUS.block());
        helper.setBlock(HOST.west(),appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        var host = (appeng.blockentity.networking.CableBusBlockEntity)helper.getBlockEntity(HOST);
        host.addPart(appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT),null,null);
        var item = (appeng.api.parts.IPartItem<PlayerSimulationPart>)git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent.PLAYER_SIMULATION_PLANE_ITEM.get();
        var part = host.addPart(item,Direction.SOUTH,null);
        part.cycle("target");
        var marker = AEItemKey.of(Items.IRON_PICKAXE);
        part.getConfig().setStack(0,new appeng.api.stacks.GenericStack(marker,1));
        var actualStack = enchanted(helper,net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY,4);
        actualStack.setDamageValue(200);
        var actual = AEItemKey.of(actualStack);
        var stock = appeng.util.ConfigInventory.storage(2).supportedType(appeng.api.stacks.AEKeyType.items()).build();
        stock.setStack(0,new appeng.api.stacks.GenericStack(actual,1));
        stock.setStack(1,new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_AXE),1));
        helper.runAfterDelay(30,() -> {
            part.getMainNode().getGrid().getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(stock));
            helper.runAfterDelay(25,() -> {
                helper.assertTrue(part.bufferedAmount(0) == 0 && stock.getAmount(0) == 1,"未安装模糊卡必须只匹配精确组件");
                part.getUpgrades().setItemDirect(0,appeng.core.definitions.AEItems.FUZZY_CARD.stack());
                part.getConfigManager().putSetting(appeng.api.config.Settings.FUZZY_MODE,appeng.api.config.FuzzyMode.PERCENT_50);
                helper.runAfterDelay(25,() -> {
                    helper.assertTrue(part.bufferedAmount(0) == 0,"耐久阈值不同的工具不应匹配 50% 模式");
                    part.getConfigManager().putSetting(appeng.api.config.Settings.FUZZY_MODE,appeng.api.config.FuzzyMode.IGNORE_ALL);
                    helper.runAfterDelay(25,() -> {
                    helper.assertTrue(part.bufferedAmount(0) == 1 && stock.getAmount(0) == 0 && stock.getAmount(1) == 1,"模糊卡应提取相同物品变体且不跨物品类型");
                    var saved = new net.minecraft.nbt.CompoundTag();part.writeToNBT(saved,helper.getLevel().registryAccess());
                    var restored = new SimulationBuffer();restored.read(saved,helper.getLevel().registryAccess());
                    helper.assertTrue(actual.matches(restored.get(0)) && restored.belongs(0,marker),"模糊缓存必须保留真实附魔、耐久和标记归属");
                    // 仅测试服务端菜单构造与定位，不注册在线玩家或发送客户端登录包。
                    var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"[MenuTest]")) {
                        @Override public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> writer) {
                            containerMenu = provider.createMenu(1,getInventory(),this);
                            return java.util.OptionalInt.of(1);
                        }
                    };
                    part.getConfig().setStack(1,new appeng.api.stacks.GenericStack(AEItemKey.of(Items.COBBLESTONE),4));
                    var locator = appeng.menu.locator.MenuLocators.forPart(part);
                    git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu.open(player,locator,1,part.getConfig().getStack(1));
                    helper.assertTrue(player.containerMenu instanceof git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu,"数量子菜单必须可定位实际面板");
                    var menu = (git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu)player.containerMenu;
                    menu.confirm(27);
                    helper.assertTrue(part.getConfig().getAmount(1) == 27,"数字输入应保存到标记目标数量");
                    helper.assertTrue(player.containerMenu instanceof git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu,"保存后应返回主面板");
                    git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu.open(player,locator,1,part.getConfig().getStack(1));
                    ((git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu)player.containerMenu).confirm(Integer.MAX_VALUE);
                    helper.assertTrue(part.getConfig().getAmount(1) == 64,"超量输入必须限制到最大堆叠");
                    git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu.open(player,locator,1,part.getConfig().getStack(1));
                    part.getConfig().setStack(1,new appeng.api.stacks.GenericStack(AEItemKey.of(Items.DIRT),3));
                    ((git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu)player.containerMenu).confirm(50);
                    helper.assertTrue(part.getConfig().getAmount(1) == 3,"编辑期间换物不能覆盖新的配置");
                    git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu.open(player,locator,1,part.getConfig().getStack(1));
                    ((git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu)player.containerMenu).confirm(0);
                    helper.assertTrue(part.getConfig().getStack(1) == null,"数量零应清除标记");
                    var mainMenu = (git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu)player.containerMenu;
                    var configSlot = mainMenu.getSlots(appeng.menu.SlotSemantics.CONFIG).get(1);
                    mainMenu.setCarried(new ItemStack(Items.COBBLESTONE,64));
                    mainMenu.doAction(player,appeng.helpers.InventoryAction.PICKUP_OR_SET_DOWN,configSlot.index,0);
                    helper.assertTrue(part.getConfig().getAmount(1) == 1,"左键只建立数量一的标记，不按鼠标携带数量增加");
                    part.getConfig().setStack(1,new appeng.api.stacks.GenericStack(AEItemKey.of(Items.COBBLESTONE),27));
                    mainMenu.doAction(player,appeng.helpers.InventoryAction.SPLIT_OR_PLACE_SINGLE,configSlot.index,0);
                    helper.assertTrue(part.getConfig().getStack(1) == null,"右键应清除整个标记，不能减少目标数量");
                    mainMenu.setFilter(configSlot.index,new ItemStack(Items.DIRT,32));
                    helper.assertTrue(part.getConfig().getAmount(1) == 1,"拖入标记不能作为数量编辑入口");
                    mainMenu.setCarried(ItemStack.EMPTY);
                    mainMenu.doAction(player,appeng.helpers.InventoryAction.PICKUP_OR_SET_DOWN,configSlot.index,0);
                    helper.assertTrue(part.getConfig().getStack(1) == null,"空手左键清除整个标记");
                    part.getUpgrades().setItemDirect(0,ItemStack.EMPTY);
                    part.returnBuffers();
                    helper.runAfterDelay(25,() -> {
                        helper.assertTrue(part.bufferedAmount(0) == 0 && stock.getAmount(0) == 1,"移除模糊卡并退回缓存后不得再次提取变体");
                        helper.succeed();
                    });
                });
                });
            });
        });
    }

    private static String press(SimulationActor actor,SimulationBuffer buffer,AEItemKey key,SimulationSettings.Click click,
                                SimulationSettings.Target target,SimulationSettings.Press press,int ticks,boolean due) {
        actor.tickClock();
        return SimulationInteraction.execute(actor,buffer,0,1,n -> key,
                () -> actor.act(click,target,SimulationSettings.Cooldown.IGNORE,press,ticks,due),stack -> {
                    throw new AssertionError("本测试不产生库存副产物: " + stack);
                });
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void leftTapAbortsButContinuousHoldFinishesMining(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.OBSIDIAN);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.DIAMOND_PICKAXE);
        buffer.set(0,key,new ItemStack(Items.DIAMOND_PICKAXE));
        actor.inventory().player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED).setBaseValue(0.5);
        press(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.TAP,-1,true);
        helper.assertTrue(!actor.busy(),"单击左键应立即松开并中止未完成的挖掘");
        helper.assertBlockPresent(Blocks.OBSIDIAN,FRONT);
        helper.assertTrue(buffer.get(0).getDamageValue() == 0,"单击不能直接破坏黑曜石或损耗工具");
        for (int i = 0; i < 20; i++) press(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,20,i == 0);
        helper.assertTrue(!actor.busy(),"定时按住必须按配置松开");
        helper.assertBlockPresent(Blocks.OBSIDIAN,FRONT);
        int ticks = 0;
        while (helper.getBlockState(FRONT).is(Blocks.OBSIDIAN) && ticks < 400) {
            press(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,-1,ticks == 0);
            ticks++;
        }
        helper.assertTrue(ticks > 200,"该用例必须跨越旧的 200 tick 释放上限");
        helper.assertBlockNotPresent(Blocks.OBSIDIAN,FRONT);
        helper.assertTrue(actor.busy() && buffer.get(0).getDamageValue() == 1,"持续按住不会在挖掘完成后自动松开，工具只损耗一次");
        actor.cancel();helper.assertTrue(!actor.busy(),"外部停止必须能解除持续按住");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void rightTapReleasesAndContinuousHoldKeepsShieldRaised(GameTestHelper helper) {
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.SHIELD);
        buffer.set(0,key,new ItemStack(Items.SHIELD));
        press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,SimulationSettings.Press.TAP,20,true);
        helper.assertTrue(!actor.busy() && !actor.inventory().player.isUsingItem(),"右键点击应立即松开盾牌");
        for (int i = 0; i < 250; i++) press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,SimulationSettings.Press.HOLD,-1,i == 0);
        helper.assertTrue(actor.busy() && actor.inventory().player.isUsingItem(),"持续按住 250 tick 仍应保持盾牌使用状态");
        actor.cancel();helper.assertTrue(!actor.busy(),"取消后必须停止盾牌持续使用");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void continuousHoldRespectsRepeatInterval(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.LEVER.defaultBlockState());
        var actor = actor(helper);var buffer = new SimulationBuffer();
        press(actor,buffer,null,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,-1,true);
        helper.assertTrue(helper.getBlockState(FRONT).getValue(LeverBlock.POWERED),"初次按住应执行交互");
        for (int i = 0; i < 19; i++) press(actor,buffer,null,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,-1,false);
        helper.assertTrue(helper.getBlockState(FRONT).getValue(LeverBlock.POWERED),"持续按住不能绕过加速间隔每 tick 切换拉杆");
        press(actor,buffer,null,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,-1,true);
        helper.assertTrue(!helper.getBlockState(FRONT).getValue(LeverBlock.POWERED),"间隔到期后可继续按住重复交互");
        actor.cancel();helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void modEnchantmentTicksAndEquipmentEventsUseActualItem(GameTestHelper helper) {
        var custom = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("chexsonsaeutils","simulation_test"));
        var tool = enchanted(helper,custom,1);var key = AEItemKey.of(tool);var buffer = new SimulationBuffer();buffer.set(0,key,tool);
        var actor = actor(helper);var player = actor.inventory().player;
        int[] equipmentEvents = {0};int[] playerTicks = {0};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent> equipment = event -> {
            if (event.getEntity() == player) {
                equipmentEvents[0]++;
                helper.assertTrue(event.getTo().isEnchanted(),"装备事件必须提供真实附魔物品");
            }
        };
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.PlayerTickEvent.Post> tick = event -> {
            if (event.getEntity() == player) {
                playerTicks[0]++;
                helper.assertTrue(player.getMainHandItem().isEnchanted(),"模组玩家 tick 必须看到真实主手");
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(equipment);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(tick);
        try {
            for (int i = 0; i < 5; i++) press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.AIR,SimulationSettings.Press.HOLD,-1,false);
            helper.assertTrue(equipmentEvents[0] == 1 && playerTicks[0] == 5,"装备未变更不应反复触发换装，tick 每次只推进一次");
            helper.assertTrue(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MINING_EFFICIENCY) >= 30,"自定义命名空间的附魔属性必须生效");
            helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED),"自定义附魔的通用 tick 效果必须生效");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(equipment);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(tick);
        }
        actor.cancel();helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=180)
    @SuppressWarnings("unchecked")
    public static void continuousEmptyHandPicksUpNewStockAndRedstoneStopsIt(GameTestHelper helper) {
        helper.setBlock(HOST,appeng.core.definitions.AEBlocks.CABLE_BUS.block());
        helper.setBlock(HOST.west(),appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        var host = (appeng.blockentity.networking.CableBusBlockEntity)helper.getBlockEntity(HOST);
        host.addPart(appeng.core.definitions.AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT),null,null);
        var item = (appeng.api.parts.IPartItem<PlayerSimulationPart>)git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent.PLAYER_SIMULATION_PLANE_ITEM.get();
        var part = host.addPart(item,Direction.SOUTH,null);
        part.cycle("target");part.cycle("target");part.cycle("press");
        part.cycle("hold");part.cycle("hold");part.cycle("hold");
        var key = AEItemKey.of(Items.SNOWBALL);part.getConfig().setStack(0,new appeng.api.stacks.GenericStack(key,4));
        var stock = appeng.util.ConfigInventory.storage(1).supportedType(appeng.api.stacks.AEKeyType.items()).build();
        helper.runAfterDelay(30,() -> {
            part.getMainNode().getGrid().getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(stock));
            helper.runAfterDelay(25,() -> {
                helper.assertTrue(part.bufferedAmount(0) == 0,"网络无库存时按住空手");
                stock.setStack(0,new appeng.api.stacks.GenericStack(key,16));
                helper.runAfterDelay(25,() -> {
                    helper.assertTrue(stock.getAmount(0)+part.bufferedAmount(0) < 16,"持续空手时补货应切换到实际物品并使用，不能永远锁定空手");
                    part.getUpgrades().setItemDirect(0,appeng.core.definitions.AEItems.REDSTONE_CARD.stack());
                    part.getConfigManager().putSetting(appeng.api.config.Settings.REDSTONE_CONTROLLED,appeng.api.config.RedstoneMode.HIGH_SIGNAL);
                    long remaining = stock.getAmount(0)+part.bufferedAmount(0);
                    helper.runAfterDelay(25,() -> {
                        helper.assertTrue("redstone".equals(part.status()) && stock.getAmount(0)+part.bufferedAmount(0) == remaining,"红石关闭应中断持续按住，停止真实消耗");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void fixedFacesReachNativeRightClickEventsWithoutMovingTarget(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.STICK);
        buffer.set(0,key,new ItemStack(Items.STICK));
        var hits = new ArrayList<net.minecraft.world.phys.BlockHitResult>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock> listener = event -> {
            if (event.getEntity() == actor.inventory().player) hits.add(event.getHitVec());
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try {
            for (var option : SimulationSettings.ClickFace.values()) {
                actor.position(helper.absolutePos(HOST),Direction.SOUTH,option,false);
                press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.TAP,20,true);
                var hit = hits.getLast();var expected = option.resolve(Direction.NORTH);
                helper.assertTrue(hit.getDirection() == expected && hit.getBlockPos().equals(helper.absolutePos(FRONT)),"固定面必须进入原生右键事件且不能改变目标方块");
                var point = net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(FRONT)).add(net.minecraft.world.phys.Vec3.atLowerCornerOf(expected.getNormal()).scale(0.5));
                helper.assertTrue(hit.getLocation().distanceToSqr(point) < 0.0001,"实际点击点必须在指定面的表面");
                helper.assertTrue(actor.inventory().player.getLookAngle().z > 0.99,"配置被点击面不能改变玩家视线");
            }
            helper.assertTrue(hits.size() == 7,"自动及六个固定面应分别执行一次右键回调");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void fixedFaceControlsNativeTorchPlacement(GameTestHelper helper) {
        helper.setBlock(FRONT,Blocks.STONE);
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.TORCH);
        buffer.set(0,key,new ItemStack(Items.TORCH));
        actor.position(helper.absolutePos(HOST),Direction.SOUTH,SimulationSettings.ClickFace.UP,false);
        press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.TAP,20,true);
        helper.assertBlockPresent(Blocks.TORCH,FRONT.above());
        helper.assertTrue(buffer.get(0).isEmpty(),"点击上表面放置火把必须真实消耗物品");
        helper.setBlock(FRONT.above(),Blocks.AIR);
        buffer.set(0,key,new ItemStack(Items.TORCH));
        actor.position(helper.absolutePos(HOST),Direction.SOUTH,SimulationSettings.ClickFace.EAST,false);
        press(actor,buffer,key,SimulationSettings.Click.RIGHT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.TAP,20,true);
        helper.assertBlockPresent(Blocks.WALL_TORCH,FRONT.east());
        helper.assertTrue(helper.getBlockState(FRONT.east()).getValue(net.minecraft.world.level.block.WallTorchBlock.FACING) == Direction.EAST,"点击东面应按原版放置朝东的墙火把");
        helper.assertTrue(buffer.get(0).isEmpty(),"侧面放置也必须真实消耗物品");
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void fixedFaceIsKeptForMiningStartStopAndAbort(GameTestHelper helper) {
        var actor = actor(helper);var buffer = new SimulationBuffer();var key = AEItemKey.of(Items.IRON_PICKAXE);
        var actions = new ArrayList<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action>();
        Direction[] expected = {Direction.UP};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock> listener = event -> {
            if (event.getEntity() == actor.inventory().player) {
                helper.assertTrue(event.getFace() == expected[0],"挖掘开始、完成和中止事件均应保留配置的面");
                actions.add(event.getAction());
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try {
            for (var option : SimulationSettings.ClickFace.values()) {
                if (option == SimulationSettings.ClickFace.AUTO) continue;
                expected[0] = option.resolve(Direction.NORTH);actions.clear();
                helper.setBlock(FRONT,Blocks.STONE);buffer.set(0,key,new ItemStack(Items.IRON_PICKAXE));
                actor.position(helper.absolutePos(HOST),Direction.SOUTH,option,false);
                press(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.TAP,20,true);
                helper.assertTrue(actions.contains(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.ABORT),"单击未完成的挖掘应发送同方向的中止事件");
                actions.clear();
                for (int tick = 0; tick < 30 && helper.getBlockState(FRONT).is(Blocks.STONE); tick++)
                    press(actor,buffer,key,SimulationSettings.Click.LEFT,SimulationSettings.Target.BLOCK,SimulationSettings.Press.HOLD,-1,tick == 0);
                helper.assertBlockNotPresent(Blocks.STONE,FRONT);
                helper.assertTrue(actions.contains(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START)
                        && actions.contains(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.STOP),"持续挖掘应发送同方向的开始和完成事件");
                actor.cancel();
            }
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

}
