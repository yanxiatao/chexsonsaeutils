package git.chexson.chexsonsaeutils.parts.playersimulation;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import java.util.Comparator;
import java.util.UUID;

/** 使用 NeoForge 玩家事件与原版交互入口；保持 FakePlayer 身份，不借用真实玩家权限。 */
public final class SimulationActorImpl implements SimulationActor {
    private final Actor player;
    private final ServerLevel level;
    private BlockPos targetPos;
    private Direction face;
    private Direction miningFace;
    private SimulationSettings.ClickFace clickFace;
    private BlockState miningState;
    private float progress;
    private int heldTicks;
    private boolean pressed;

    public SimulationActorImpl(ServerLevel level, UUID identity, CompoundTag saved) {
        this.level = level;
        this.player = new Actor(level, new GameProfile(identity, "[ME-" + identity.toString().substring(0,8) + "]"));
        if (!saved.isEmpty()) player.load(saved.copy());
        player.setUUID(identity);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        player.simulationCooldowns().load(saved.getCompound("simulationCooldowns"));
    }
    @Override public Inventory inventory() { return player.getInventory(); }
    @Override public void position(BlockPos host, Direction face, SimulationSettings.ClickFace clickFace, boolean sneaking) {
        this.face = face;
        this.clickFace = clickFace;
        targetPos = host.relative(face);
        Vec3 eye = Vec3.atCenterOf(host).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.501));
        player.setShiftKeyDown(sneaking);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        player.setOnGround(true);
        player.setYRot(switch (face) { case SOUTH -> 0; case WEST -> 90; case NORTH -> 180; case EAST -> -90; default -> 0; });
        player.setXRot(face == Direction.UP ? -90 : face == Direction.DOWN ? 90 : 0);
    }
    @Override public void hold(ItemStack stack) {
        player.refreshHand(stack);
        if (player.isUsingItem()) player.resumeUse(stack);
    }
    @Override public void tickClock() { player.advanceClock(); }
    @Override public boolean busy() { return pressed || miningState != null || player.isUsingItem(); }
    @Override public String act(SimulationSettings.Click click, SimulationSettings.Target target,
                                SimulationSettings.Cooldown cooldown, SimulationSettings.Press press, int holdTicks, boolean repeatAllowed) {
        if (press == SimulationSettings.Press.HOLD && !pressed) { pressed = true; heldTicks = 0; }
        String result = advance(click,target,cooldown,repeatAllowed);
        if ("blocked".equals(result)) cancel();
        else if (press == SimulationSettings.Press.TAP || holdTicks >= 0 && ++heldTicks >= holdTicks) release(click);
        return result;
    }
    private String advance(SimulationSettings.Click click, SimulationSettings.Target target,
                           SimulationSettings.Cooldown cooldown, boolean repeatAllowed) {
        if (cooldown == SimulationSettings.Cooldown.IGNORE && !player.getMainHandItem().isEmpty())
            player.getCooldowns().removeCooldown(player.getMainHandItem().getItem());
        if (player.isUsingItem()) {
            player.advanceUse();
            return "using";
        }
        if (miningState != null) return mine();
        if (!repeatAllowed) return "holding";
        if (cooldown == SimulationSettings.Cooldown.MATCH
                && ((!player.getMainHandItem().isEmpty() && player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem()))
                || (click == SimulationSettings.Click.LEFT && target == SimulationSettings.Target.ENTITY && player.getAttackStrengthScale(0) < 1)))
            return "cooldown";
        if (cooldown == SimulationSettings.Cooldown.IGNORE) player.fillAttackClock();
        if (target == SimulationSettings.Target.ENTITY) {
            Entity entity = findEntity();
            if (entity == null) return "no_target";
            if (click == SimulationSettings.Click.LEFT) {
                player.attack(entity);
                player.swing(InteractionHand.MAIN_HAND);
                return "attacking";
            }
            Vec3 relative = entity.getBoundingBox().getCenter().subtract(entity.position());
            var result = CommonHooks.onInteractEntityAt(player,entity,relative,InteractionHand.MAIN_HAND);
            if (result == null) result = entity.interactAt(player,relative,InteractionHand.MAIN_HAND);
            if (!result.consumesAction() && result != InteractionResult.FAIL) result = player.interactOn(entity, InteractionHand.MAIN_HAND);
            if (!result.consumesAction() && result != InteractionResult.FAIL) useAir();
        } else if (target == SimulationSettings.Target.BLOCK) {
            var hit = blockHit();
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(targetPos)) return "no_target";
            if (click == SimulationSettings.Click.LEFT) return startMine(hit);
            if (!level.mayInteract(player, targetPos) || !level.getWorldBorder().isWithinBounds(targetPos)) return "blocked";
            var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            if (!result.consumesAction() && result != InteractionResult.FAIL) useAir();
        } else if (click == SimulationSettings.Click.RIGHT) {
            useAir();
        } else {
            player.swing(InteractionHand.MAIN_HAND);
        }
        return player.isUsingItem() ? "using" : "interacting";
    }
    private void useAir() {
        player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
    }
    private BlockHitResult blockHit() {
        Vec3 start = player.getEyePosition();
        var detected = level.clip(new ClipContext(start, start.add(Vec3.atLowerCornerOf(face.getNormal()).scale(1.5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (detected.getType() != HitResult.Type.BLOCK || !detected.getBlockPos().equals(targetPos)
                || clickFace == SimulationSettings.ClickFace.AUTO) return detected;
        var shape = level.getBlockState(targetPos).getShape(level,targetPos,net.minecraft.world.phys.shapes.CollisionContext.of(player));
        return SimulationClickFaces.hit(detected,shape,clickFace);
    }
    private Entity findEntity() {
        Vec3 start = player.getEyePosition();
        return level.getEntities(player, new AABB(targetPos), e -> e.isAlive() && e.isPickable() && !e.isSpectator())
                .stream().filter(e -> {
                    Vec3 center = e.getBoundingBox().getCenter();
                    var obstruction = level.clip(new ClipContext(start, center, ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE, player));
                    return obstruction.getType() == HitResult.Type.MISS
                            || start.distanceToSqr(obstruction.getLocation()) + 0.01 >= start.distanceToSqr(center);
                }).min(Comparator.comparingDouble(e -> e.distanceToSqr(start))).orElse(null);
    }

    private String startMine(BlockHitResult hit) {
        if (!level.mayInteract(player, targetPos) || !level.getWorldBorder().isWithinBounds(targetPos)
                || player.blockActionRestricted(level, targetPos, GameType.SURVIVAL)) return "blocked";
        var event = CommonHooks.onLeftClickBlock(player, targetPos, hit.getDirection(),
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        if (event.isCanceled() || event.getUseItem() == TriState.FALSE) return "blocked";
        miningFace = hit.getDirection();
        miningState = level.getBlockState(targetPos);
        net.minecraft.world.item.enchantment.EnchantmentHelper.onHitBlock(level,player.getMainHandItem(),player,player,
                EquipmentSlot.MAINHAND,Vec3.atCenterOf(targetPos),miningState,
                item -> player.onEquippedItemBroken(item,EquipmentSlot.MAINHAND));
        if (event.getUseBlock() != TriState.FALSE) miningState.attack(level, targetPos, player);
        progress = 0;
        return mine();
    }
    private String mine() {
        if (level.getBlockState(targetPos) != miningState) {
            CommonHooks.onLeftClickBlock(player,targetPos,miningFace,ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            clearMining();
            return "holding";
        }
        if (!level.mayInteract(player, targetPos) || !level.getWorldBorder().isWithinBounds(targetPos)) {
            cancel();
            return "blocked";
        }
        progress += miningState.getDestroyProgress(player, level, targetPos);
        if (progress >= 1) {
            var event = CommonHooks.onLeftClickBlock(player, targetPos, miningFace,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            boolean broken = !event.isCanceled() && event.getUseItem() != TriState.FALSE && player.gameMode.destroyBlock(targetPos);
            clearMining();
            return broken ? "broken" : "blocked";
        }
        level.destroyBlockProgress(player.getId(), targetPos, Math.min(9, (int)(progress * 10)));
        return "mining";
    }
    private void clearMining() {
        if (miningState != null && targetPos != null) level.destroyBlockProgress(player.getId(), targetPos, -1);
        miningState = null;
        progress = 0;
    }
    private void release(SimulationSettings.Click click) {
        if (click == SimulationSettings.Click.LEFT && miningState != null)
            CommonHooks.onLeftClickBlock(player,targetPos,miningFace,ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        if (player.isUsingItem()) player.releaseUsingItem();
        clearMining();
        pressed = false;
        heldTicks = 0;
    }
    @Override public void cancel() {
        if (miningState != null && targetPos != null)
            CommonHooks.onLeftClickBlock(player,targetPos,miningFace,ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        clearMining();
        player.stopUsingItem();
        pressed = false;
        heldTicks = 0;
    }
    @Override public void suspendHand() { player.detachHand(); }
    @Override public CompoundTag save() {
        var tag = new CompoundTag();
        player.saveWithoutId(tag);
        // 背包由部件唯一持有；此处不保存玩家侧物品副本。
        tag.remove("Inventory");
        tag.put("simulationCooldowns",player.simulationCooldowns().save());
        return tag;
    }
    private static final class Actor extends FakePlayer {
        private boolean clockPending;
        private ItemStack attributed = ItemStack.EMPTY;
        Actor(ServerLevel level, GameProfile profile) { super(level, profile); }
        @Override protected net.minecraft.world.item.ItemCooldowns createItemCooldowns() { return new SimulationCooldowns(); }
        SimulationCooldowns simulationCooldowns() { return (SimulationCooldowns) getCooldowns(); }
        void refreshHand(ItemStack stack) {
            // 原版装备变更只在物品组件变化时移除旧效果，避免每 tick 重启模组附魔效果。
            boolean changed = !ItemStack.isSameItemSameComponents(attributed,stack);
            if (changed) {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent(this,EquipmentSlot.MAINHAND,attributed.copy(),stack));
                net.minecraft.world.item.enchantment.EnchantmentHelper.stopLocationBasedEffects(attributed,this,EquipmentSlot.MAINHAND);
                attributed.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    var instance = getAttribute(attribute);
                    if (instance != null) instance.removeModifier(modifier.id());
                });
            }
            setItemInHand(InteractionHand.MAIN_HAND, stack);
            if (changed) {
                stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    var instance = getAttribute(attribute);
                    if (instance != null && !instance.hasModifier(modifier.id())) instance.addTransientModifier(modifier);
                });
            }
            net.minecraft.world.item.enchantment.EnchantmentHelper.runLocationChangedEffects((ServerLevel)level(),stack,this,EquipmentSlot.MAINHAND);
            attributed = stack.copy();
            if (clockPending) {
                clockPending = false;
                net.neoforged.neoforge.event.EventHooks.firePlayerTickPre(this);
                net.minecraft.world.item.enchantment.EnchantmentHelper.tickEffects((ServerLevel)level(),this);
                getInventory().tick();
                tickEffects();
                net.neoforged.neoforge.event.EventHooks.firePlayerTickPost(this);
            }
        }
        void advanceClock() { clockPending = true; attackStrengthTicker++; tickCount++; getCooldowns().tick(); }
        void fillAttackClock() { attackStrengthTicker = 1000; }
        void resumeUse(ItemStack stack) { useItem = stack; }
        void advanceUse() { updateUsingItem(getMainHandItem()); }
        void detachHand() {
            setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            useItem = ItemStack.EMPTY;
        }
    }
}
