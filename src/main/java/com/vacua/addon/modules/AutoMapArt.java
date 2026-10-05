package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import com.vacua.addon.utils.RotationUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AutoMapArt - 自动地图画
 *
 * 地面随机/绕脚下循环放置展示框地图，也可以在敌人面前 airplace 黑曜石 + 展示框地图。
 * 支持发包放置、多次放置、放置速度与移动不关闭等选项。
 */
public class AutoMapArt extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAir = settings.createGroup("AirPlace");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Boolean> groundFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("ground-frames").description("在周围地面随机放置展示框地图.")
        .defaultValue(true).build());

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place").description("在敌人面前airplace黑曜石+展示框地图.")
        .defaultValue(true).build());

    private final Setting<Boolean> feetLoop = sgGeneral.add(new BoolSetting.Builder()
        .name("feet-loop").description("脚下循环放置模式: 绕自己脚下画圈循环放置.")
        .defaultValue(false)
        .visible(groundFrames::get).build());

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius").description("地面放置半径.")
        .defaultValue(6.0).range(1.0, 16.0)
        .visible(groundFrames::get).build());

    private final Setting<Double> feetRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("feet-radius").description("脚下循环半径.")
        .defaultValue(1.5).range(0.5, 6.0)
        .visible(() -> groundFrames.get() && feetLoop.get()).build());

    private final Setting<Double> feetStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("feet-step").description("脚下循环角度步进(度), 越小越密.")
        .defaultValue(20.0).range(5.0, 90.0)
        .visible(() -> groundFrames.get() && feetLoop.get()).build());

    private final Setting<Boolean> packetPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-place").description("发包放置: 直接发交互包, 不走 interactionManager API.")
        .defaultValue(true).build());

    private final Setting<Boolean> multiPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("multi-place").description("每轮同时处理多个放置.")
        .defaultValue(false).build());

    private final Setting<Integer> placesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("places-per-tick").description("每轮最多放置次数.")
        .defaultValue(3).range(1, 8)
        .visible(multiPlace::get).build());

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval").description("放置速度: 每次操作间隔(tick), 越小越快.")
        .defaultValue(4).range(0, 40).build());

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach").description("交互距离.")
        .defaultValue(4.5).range(1.0, 6.0).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("操作时看向目标.")
        .defaultValue(true).build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back").description("关闭后还原快捷栏.")
        .defaultValue(true).build());

    private final Setting<Integer> maxFrames = sgGeneral.add(new IntSetting.Builder()
        .name("max-frames").description("最多放置数量(0=无限).")
        .defaultValue(0).range(0, 500).build());

    private final Setting<Boolean> closeOnMove = sgGeneral.add(new BoolSetting.Builder()
        .name("close-on-move").description("移动超过距离后自动关闭(默认关闭=移动不关闭).")
        .defaultValue(false).build());

    private final Setting<Double> moveDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("move-distance").description("触发自动关闭的移动距离.")
        .defaultValue(3.0).range(0.5, 20.0)
        .visible(closeOnMove::get).build());

    // AirPlace
    private final Setting<Double> targetRange = sgAir.add(new DoubleSetting.Builder()
        .name("target-range").description("敌人搜索范围.")
        .defaultValue(30.0).range(1.0, 200.0)
        .visible(airPlace::get).build());

    private final Setting<Double> frontDistance = sgAir.add(new DoubleSetting.Builder()
        .name("front-distance").description("在敌人面前放置的距离.")
        .defaultValue(1.5).range(1.0, 4.0)
        .visible(airPlace::get).build());

    private final Setting<Boolean> repeat = sgAir.add(new BoolSetting.Builder()
        .name("repeat").description("重复对同一敌人放置.")
        .defaultValue(false)
        .visible(airPlace::get).build());

    private final Setting<Integer> repeatInterval = sgAir.add(new IntSetting.Builder()
        .name("repeat-interval").description("重复放置间隔(tick).")
        .defaultValue(200).range(20, 1200)
        .visible(() -> airPlace.get() && repeat.get()).build());

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("渲染放置位置.")
        .defaultValue(true).build());

    private final Setting<SettingColor> pendingColor = sgRender.add(new ColorSetting.Builder()
        .name("pending-color").description("待放置颜色.")
        .defaultValue(new SettingColor(0, 255, 255, 80)).build());

    private final Setting<SettingColor> doneColor = sgRender.add(new ColorSetting.Builder()
        .name("done-color").description("已完成颜色.")
        .defaultValue(new SettingColor(0, 255, 0, 60)).build());

    // State
    private enum Stage { Support, Frame, Insert }

    private static class Job {
        BlockPos support;
        Direction face;
        BlockPos framePos;
        boolean airPlace;
        UUID targetId;
        Vec3d look;

        Stage stage = Stage.Frame;
        int attempts;
        int ticks;
        int nextActionTick;
        boolean acted;
        boolean done;
        boolean failed;
    }

    private final List<Job> jobs = new ArrayList<>();
    private int tick;
    private int roundTimer;
    private int framesPlaced;
    private int startSlot = -1;
    private int lastSwapTick = Integer.MIN_VALUE / 2;
    private Hand hand = Hand.MAIN_HAND;
    private boolean missingWarned;
    private double feetAngle;
    private double feetRingRadius;
    private Vec3d activatePos;
    private final List<int[]> swaps = new ArrayList<>();
    private final List<BlockPos> completed = new ArrayList<>();
    private final Set<UUID> airDone = new HashSet<>();
    private final Set<UUID> airPending = new HashSet<>();
    private final Map<UUID, Long> airDoneTime = new HashMap<>();

    private static final double[] AIR_YAWS = {0, 30, -30, 180, 90, -90};
    private static final double[] AIR_DISTS = {0, 0.5, 1.0};

    public AutoMapArt() {
        super(VacuaAddon.CATEGORY, "auto-map-art", "自动地图画 - 地面展示框放地图 + 敌人面前airplace地图");
    }

    @Override
    public void onActivate() {
        jobs.clear();
        tick = 0;
        roundTimer = 0;
        framesPlaced = 0;
        hand = Hand.MAIN_HAND;
        missingWarned = false;
        lastSwapTick = Integer.MIN_VALUE / 2;
        feetAngle = 0;
        feetRingRadius = Math.min(feetRadius.get(), ringMax());
        swaps.clear();
        completed.clear();
        airDone.clear();
        airPending.clear();
        airDoneTime.clear();
        startSlot = mc.player != null ? mc.player.getInventory().selectedSlot : -1;
        activatePos = mc.player != null ? mc.player.getPos() : null;
    }

    @Override
    public void onDeactivate() {
        jobs.clear();
        restoreSlots();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        tick++;

        if (closeOnMove.get() && activatePos != null
            && mc.player.getPos().distanceTo(activatePos) > moveDistance.get()) {
            info("移动超过 %.1f 格, 自动关闭", moveDistance.get());
            toggle();
            return;
        }

        if (roundTimer > 0) {
            roundTimer--;
            return;
        }

        if (!InvUtils.find(Items.ITEM_FRAME).found() || !InvUtils.find(Items.FILLED_MAP).found()) {
            if (!missingWarned) {
                missingWarned = true;
                warning("背包里缺少物品展示框或填充地图");
            }
            roundTimer = interval.get();
            return;
        }
        missingWarned = false;

        int budget = multiPlace.get() ? placesPerTick.get() : 1;
        int wanted = Math.max(budget, 3);
        int guard = 0;
        while (jobs.size() < wanted && guard++ < 12) {
            Job job = createJob();
            if (job == null) break;
            jobs.add(job);
        }

        int actions = 0;
        Iterator<Job> it = jobs.iterator();
        while (it.hasNext()) {
            if (actions >= budget) break;

            Job job = it.next();
            if (job.nextActionTick > tick) continue;

            processJob(job);

            if (job.done) {
                it.remove();
                complete(job);
                if (maxFrames.get() > 0 && framesPlaced >= maxFrames.get()) {
                    info("已放置 %d 个地图画", framesPlaced);
                    toggle();
                    return;
                }
            } else if (job.failed) {
                it.remove();
                discard(job);
            } else if (job.acted) {
                actions++;
            }
        }

        roundTimer = interval.get();
    }

    private void processJob(Job job) {
        job.acted = false;
        job.done = false;
        job.failed = false;
        job.ticks++;

        if (job.ticks > 400) {
            job.failed = true;
            return;
        }

        switch (job.stage) {
            case Support -> handleSupport(job);
            case Frame -> handleFrame(job);
            case Insert -> handleInsert(job);
        }
    }

    private void handleSupport(Job job) {
        if (!mc.world.getBlockState(job.support).isAir()) {
            job.stage = Stage.Frame;
            job.attempts = 0;
            job.ticks = 0;
            return;
        }

        if (!selectItem(Items.OBSIDIAN)) return;

        Vec3d towardPlayer = mc.player.getPos().subtract(job.support.toCenterPos());
        Direction toPlayer = Direction.getFacing(towardPlayer.x, 0, towardPlayer.z);

        Direction solid = null;
        for (Direction d : Direction.values()) {
            if (isSolid(job.support.offset(d))) {
                solid = d;
                break;
            }
        }

        if (solid != null) placeOn(job.support.offset(solid), solid.getOpposite());
        else placeOn(job.support, toPlayer);

        act(job, 0);
        if (job.attempts > 10) job.failed = true;
    }

    private void handleFrame(Job job) {
        ItemFrameEntity existing = findFrameEntity(job.framePos);
        if (existing != null) {
            if (!existing.getHeldItemStack().isEmpty()) {
                job.failed = true;
                return;
            }
            job.stage = Stage.Insert;
            job.attempts = 0;
            job.ticks = 0;
            return;
        }

        if (!mc.world.getBlockState(job.framePos).isAir()) {
            job.failed = true;
            return;
        }

        if (!selectItem(Items.ITEM_FRAME)) return;

        placeOn(job.support, job.face);

        act(job, 5);
        if (job.attempts > 10) job.failed = true;
    }

    private void handleInsert(Job job) {
        ItemFrameEntity frame = findFrameEntity(job.framePos);
        if (frame == null) {
            job.attempts++;
            job.nextActionTick = tick + 2;
            if (job.attempts > 15) job.failed = true;
            return;
        }

        if (!frame.getHeldItemStack().isEmpty()) {
            job.done = true;
            return;
        }

        if (!selectItem(Items.FILLED_MAP)) return;

        Vec3d look = job.look;
        Hand h = hand;
        withRotation(look, () -> {
            interactEntity(frame, h);
            mc.player.swingHand(h);
        });

        act(job, 0);
        if (job.attempts > 10) job.failed = true;
    }

    private void act(Job job, int cooldown) {
        job.acted = true;
        job.attempts++;
        job.ticks = 0;
        job.nextActionTick = tick + cooldown;
    }

    private void placeOn(BlockPos support, Direction face) {
        Vec3d hitVec = Vec3d.ofCenter(support).add(
            face.getVector().getX() * 0.5,
            face.getVector().getY() * 0.5,
            face.getVector().getZ() * 0.5
        );
        BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
        Hand h = hand;
        withRotation(hitVec, () -> {
            sendInteractBlock(h, hit);
            mc.player.swingHand(h);
        });
    }

    private void sendInteractBlock(Hand h, BlockHitResult hit) {
        if (packetPlace.get() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(h, hit, 0));
        } else {
            mc.interactionManager.interactBlock(mc.player, h, hit);
        }
    }

    private void interactEntity(ItemFrameEntity frame, Hand h) {
        if (packetPlace.get() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.interact(frame, mc.player.isSneaking(), h));
        } else {
            mc.interactionManager.interactEntity(mc.player, frame, h);
        }
    }

    private void withRotation(Vec3d look, Runnable action) {
        if (!rotate.get()) {
            action.run();
            return;
        }
        float[] r = RotationUtil.getRotation(mc.player.getEyePos(), look);
        Rotations.rotate(r[0], r[1], action);
    }

    private Job createJob() {
        if (airPlace.get()) {
            PlayerEntity target = getClosestPlayer();
            if (target != null && canTarget(target)) {
                Job airJob = createAirJob(target);
                if (airJob != null) {
                    airPending.add(target.getUuid());
                    return airJob;
                }
            }
        }

        if (groundFrames.get()) {
            if (feetLoop.get()) return createFeetLoopJob();
            for (int i = 0; i < 8; i++) {
                Job groundJob = createGroundJob();
                if (groundJob != null) return groundJob;
            }
        }
        return null;
    }

    private boolean canTarget(PlayerEntity target) {
        UUID id = target.getUuid();
        if (airPending.contains(id)) return false;
        if (!airDone.contains(id)) return true;
        if (!repeat.get()) return false;
        return System.currentTimeMillis() >= airDoneAt(id) + repeatInterval.get() * 50L;
    }

    private long airDoneAt(UUID id) {
        return airDoneTime.getOrDefault(id, 0L);
    }

    private Job createAirJob(PlayerEntity target) {
        Vec3d look = Vec3d.fromPolar(0, target.getYaw());
        double eyeY = target.getY() + target.getEyeHeight(target.getPose());

        for (double extra : AIR_DISTS) {
            double d = frontDistance.get() + extra;
            for (double yawOffset : AIR_YAWS) {
                Vec3d dir = rotateY(look, yawOffset);
                Vec3d base = target.getPos().add(dir.x * d, 0, dir.z * d);

                BlockPos support = BlockPos.ofFloored(base.x, eyeY, base.z);
                if (!mc.world.getBlockState(support).isAir()) continue;

                Direction face = Direction.getFacing(
                    target.getX() - (support.getX() + 0.5), 0,
                    target.getZ() - (support.getZ() + 0.5));
                BlockPos framePos = support.offset(face);
                if (!mc.world.getBlockState(framePos).isAir()) continue;

                Box targetBox = target.getBoundingBox().expand(0.05);
                if (targetBox.intersects(new Box(support)) || targetBox.intersects(new Box(framePos))) continue;
                if (findFrameEntity(framePos) != null) continue;
                if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(support)) > reach.get()) continue;
                if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(framePos)) > 3.0) continue;

                Job result = new Job();
                result.support = support;
                result.face = face;
                result.framePos = framePos;
                result.airPlace = true;
                result.targetId = target.getUuid();
                result.stage = Stage.Support;
                result.look = Vec3d.ofCenter(framePos);
                return result;
            }
        }
        return null;
    }

    private Job createGroundJob() {
        double rMin = Math.min(1.5, radius.get());
        double rMax = Math.max(rMin + 0.1, radius.get());
        double dist = rMin + Math.random() * (rMax - rMin);
        double angle = Math.random() * Math.PI * 2;

        double x = mc.player.getX() + Math.cos(angle) * dist;
        double z = mc.player.getZ() + Math.sin(angle) * dist;

        BlockPos ground = findGround(x, z);
        if (ground == null) return null;

        BlockPos framePos = ground.up();
        if (!isValidFramePos(framePos)) return null;

        Job result = new Job();
        result.support = ground;
        result.face = Direction.UP;
        result.framePos = framePos;
        result.stage = Stage.Frame;
        result.look = Vec3d.ofCenter(framePos);
        return result;
    }

    private Job createFeetLoopJob() {
        BlockPos center = mc.player.getBlockPos();
        double maxRing = ringMax();

        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(feetAngle);
            double x = center.getX() + 0.5 + Math.cos(ang) * feetRingRadius;
            double z = center.getZ() + 0.5 + Math.sin(ang) * feetRingRadius;
            advanceFeet(maxRing);

            BlockPos ground = findGround(x, z);
            if (ground == null) continue;

            BlockPos framePos = ground.up();
            if (!isValidFramePos(framePos)) continue;

            Job result = new Job();
            result.support = ground;
            result.face = Direction.UP;
            result.framePos = framePos;
            result.stage = Stage.Frame;
            result.look = Vec3d.ofCenter(framePos);
            return result;
        }
        return null;
    }

    private void advanceFeet(double maxRing) {
        feetAngle += feetStep.get();
        if (feetAngle >= 360.0) {
            feetAngle -= 360.0;
            feetRingRadius += 1.0;
            if (feetRingRadius > maxRing) feetRingRadius = Math.min(feetRadius.get(), maxRing);
        }
    }

    private double ringMax() {
        double max = Math.min(radius.get(), Math.min(reach.get(), 3.0));
        return Math.max(1.0, max);
    }

    private boolean isValidFramePos(BlockPos framePos) {
        BlockPos self = mc.player.getBlockPos();
        if (framePos.equals(self) || framePos.equals(self.up()) || framePos.equals(self.down())) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(framePos)) > Math.min(reach.get(), 3.0)) return false;
        for (Job job : jobs) {
            if (job.framePos.equals(framePos)) return false;
        }
        return findFrameEntity(framePos) == null;
    }

    private BlockPos findGround(double x, double z) {
        for (int dy = 3; dy >= -8; dy--) {
            BlockPos pos = BlockPos.ofFloored(x, mc.player.getY() + dy, z);
            if (isSolid(pos) && mc.world.isAir(pos.up())) return pos;
        }
        return null;
    }

    private boolean isSolid(BlockPos pos) {
        return !mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private ItemFrameEntity findFrameEntity(BlockPos framePos) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemFrameEntity frame && frame.getAttachedBlockPos().equals(framePos)) {
                return frame;
            }
        }
        return null;
    }

    private PlayerEntity getClosestPlayer() {
        PlayerEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        double maxSq = targetRange.get() * targetRange.get();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && entity != mc.player && player.getHealth() > 0) {
                double dist = mc.player.squaredDistanceTo(entity);
                if (dist <= maxSq && dist < closestDist) {
                    closest = player;
                    closestDist = dist;
                }
            }
        }
        return closest;
    }

    private Vec3d rotateY(Vec3d dir, double angleDegrees) {
        double rad = Math.toRadians(angleDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3d(dir.x * cos - dir.z * sin, dir.y, dir.x * sin + dir.z * cos);
    }

    private boolean selectItem(Item item) {
        if (mc.player == null) return false;
        hand = Hand.MAIN_HAND;

        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) {
            Hand h = hotbar.getHand();
            if (h != null) hand = h;
            if (hotbar.isHotbar()) InvUtils.swap(hotbar.slot(), false);
            return true;
        }

        FindItemResult anywhere = InvUtils.find(item);
        if (anywhere.found() && anywhere.isMain()) {
            if (tick - lastSwapTick < 2) return false;

            int slot = findFreeHotbarSlot();
            if (slot < 0) return false;

            InvUtils.quickSwap().fromId(slot).to(anywhere.slot());
            swaps.add(new int[]{slot, anywhere.slot()});
            lastSwapTick = tick;
            return false;
        }

        if (!missingWarned) {
            missingWarned = true;
            warning("背包里没有 " + item.getName().getString());
        }
        return false;
    }

    private int findFreeHotbarSlot() {
        if (mc.player == null) return -1;
        var inv = mc.player.getInventory();
        int selected = inv.selectedSlot;

        if (isFreeHotbar(inv.getStack(selected))) return selected;
        for (int i = 0; i < 9; i++) {
            if (i == selected) continue;
            if (isFreeHotbar(inv.getStack(i))) return i;
        }
        return -1;
    }

    private boolean isFreeHotbar(ItemStack stack) {
        if (stack.isEmpty()) return true;
        Item item = stack.getItem();
        return item != Items.ITEM_FRAME && item != Items.FILLED_MAP && item != Items.OBSIDIAN;
    }

    private void restoreSlots() {
        if (!swapBack.get() || mc.player == null) return;

        for (int i = swaps.size() - 1; i >= 0; i--) {
            int[] swap = swaps.get(i);
            InvUtils.quickSwap().fromId(swap[0]).to(swap[1]);
        }
        swaps.clear();

        if (startSlot >= 0) InvUtils.swap(startSlot, false);
        startSlot = -1;
    }

    private void complete(Job job) {
        completed.add(job.framePos);
        while (completed.size() > 200) completed.remove(0);

        if (job.targetId != null) {
            airDone.add(job.targetId);
            airDoneTime.put(job.targetId, System.currentTimeMillis());
            airPending.remove(job.targetId);
        }

        framesPlaced++;
    }

    private void discard(Job job) {
        if (job.targetId != null) airPending.remove(job.targetId);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        if (!jobs.isEmpty()) {
            Color pending = new Color(pendingColor.get());
            Color pendingOutline = new Color(pendingColor.get().r, pendingColor.get().g, pendingColor.get().b, 255);
            for (Job job : jobs) {
                event.renderer.box(new Box(job.framePos), pending, pendingOutline, ShapeMode.Both, 0);
                if (job.airPlace) {
                    event.renderer.box(new Box(job.support), pending, pendingOutline, ShapeMode.Both, 0);
                }
            }
        }

        Color done = new Color(doneColor.get());
        Color doneOutline = new Color(doneColor.get().r, doneColor.get().g, doneColor.get().b, 200);
        for (BlockPos pos : completed) {
            event.renderer.box(new Box(pos), done, doneOutline, ShapeMode.Both, 0);
        }
    }
}
