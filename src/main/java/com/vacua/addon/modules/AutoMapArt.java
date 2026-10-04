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
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AutoMapArt - 自动地图画
 *
 * 在周围地面随机放置展示框并在上面放地图，也可以在敌人面前 airplace 黑曜石 + 展示框地图。
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

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius").description("地面放置半径.")
        .defaultValue(6.0).range(1.0, 16.0)
        .visible(groundFrames::get).build());

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval").description("每次操作间隔(tick).")
        .defaultValue(4).range(1, 40).build());

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
    }

    private Job job;
    private Stage stage;
    private int attempts;
    private int timer;
    private int framesPlaced;
    private int startSlot = -1;
    private Hand hand = Hand.MAIN_HAND;
    private boolean missingWarned;
    private final List<int[]> swaps = new ArrayList<>();
    private final List<BlockPos> completed = new ArrayList<>();
    private final Set<UUID> airDone = new HashSet<>();
    private final Map<UUID, Long> airDoneTime = new HashMap<>();

    private static final double[] AIR_YAWS = {0, 30, -30, 180, 90, -90};
    private static final double[] AIR_DISTS = {0, 0.5, 1.0};

    public AutoMapArt() {
        super(VacuaAddon.CATEGORY, "auto-map-art", "自动地图画 - 地面展示框放地图 + 敌人面前airplace地图");
    }

    @Override
    public void onActivate() {
        job = null;
        stage = null;
        attempts = 0;
        timer = 0;
        framesPlaced = 0;
        hand = Hand.MAIN_HAND;
        missingWarned = false;
        swaps.clear();
        completed.clear();
        airDone.clear();
        airDoneTime.clear();
        startSlot = mc.player != null ? mc.player.getInventory().selectedSlot : -1;
    }

    @Override
    public void onDeactivate() {
        job = null;
        stage = null;
        restoreSlots();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        if (!InvUtils.find(Items.ITEM_FRAME).found() || !InvUtils.find(Items.FILLED_MAP).found()) {
            if (!missingWarned) {
                missingWarned = true;
                warning("背包里缺少物品展示框或填充地图");
            }
            return;
        }
        missingWarned = false;

        if (job == null) {
            job = createJob();
            if (job == null) {
                timer = interval.get();
                return;
            }
            stage = job.airPlace ? Stage.Support : Stage.Frame;
            attempts = 0;
        }

        switch (stage) {
            case Support -> handleSupport();
            case Frame -> handleFrame();
            case Insert -> handleInsert();
        }
    }

    private void handleSupport() {
        if (!mc.world.getBlockState(job.support).isAir()) {
            stage = Stage.Frame;
            attempts = 0;
            timer = interval.get();
            return;
        }

        if (!selectItem(Items.OBSIDIAN)) {
            timer = interval.get();
            return;
        }

        Vec3d towardPlayer = mc.player.getPos().subtract(job.support.toCenterPos());
        Direction toPlayer = Direction.getFacing(towardPlayer.x, 0, towardPlayer.z);

        Direction clickSide = toPlayer;
        for (Direction d : Direction.values()) {
            if (!mc.world.getBlockState(job.support.offset(d)).isAir()) {
                clickSide = d;
                break;
            }
        }

        placeOn(job.support.offset(clickSide), clickSide.getOpposite());

        attempts++;
        timer = interval.get();
        if (attempts > 5) discardJob("airplace黑曜石失败");
    }

    private void handleFrame() {
        ItemFrameEntity existing = findFrameEntity(job.framePos);
        if (existing != null) {
            if (!existing.getHeldItemStack().isEmpty()) {
                discardJob(null);
                return;
            }
            stage = Stage.Insert;
            attempts = 0;
            return;
        }

        if (!mc.world.getBlockState(job.framePos).isAir()) {
            discardJob(null);
            return;
        }

        if (!selectItem(Items.ITEM_FRAME)) {
            timer = interval.get();
            return;
        }

        placeOn(job.support, job.face);

        attempts++;
        timer = Math.max(interval.get(), 5);
        if (attempts > 5) discardJob("放置展示框失败");
    }

    private void handleInsert() {
        ItemFrameEntity frame = findFrameEntity(job.framePos);
        if (frame == null) {
            attempts++;
            timer = interval.get();
            if (attempts > 10) discardJob("找不到展示框");
            return;
        }

        if (!frame.getHeldItemStack().isEmpty()) {
            completeJob();
            return;
        }

        if (!selectItem(Items.FILLED_MAP)) {
            timer = interval.get();
            return;
        }

        Vec3d look = job.look;
        Hand h = hand;
        withRotation(look, () -> {
            mc.interactionManager.interactEntity(mc.player, frame, h);
            mc.player.swingHand(h);
        });

        attempts++;
        timer = interval.get();
        if (attempts > 5) discardJob("放入地图失败");
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
            mc.interactionManager.interactBlock(mc.player, h, hit);
            mc.player.swingHand(h);
        });
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
                if (airJob != null) return airJob;
            }
        }

        if (groundFrames.get()) {
            for (int i = 0; i < 12; i++) {
                Job groundJob = createGroundJob();
                if (groundJob != null) return groundJob;
            }
        }
        return null;
    }

    private boolean canTarget(PlayerEntity target) {
        UUID id = target.getUuid();
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

        BlockPos ground = null;
        for (int dy = 3; dy >= -8; dy--) {
            BlockPos pos = BlockPos.ofFloored(x, mc.player.getY() + dy, z);
            if (isSolid(pos) && mc.world.isAir(pos.up())) {
                ground = pos;
                break;
            }
        }
        if (ground == null) return null;

        BlockPos framePos = ground.up();
        BlockPos self = mc.player.getBlockPos();
        if (framePos.equals(self) || framePos.equals(self.up())) return null;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(framePos)) > Math.min(reach.get(), 3.0)) return null;
        if (findFrameEntity(framePos) != null) return null;

        Job result = new Job();
        result.support = ground;
        result.face = Direction.UP;
        result.framePos = framePos;
        result.airPlace = false;
        result.targetId = null;
        result.look = Vec3d.ofCenter(framePos);
        return result;
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
            int hotbarSlot = mc.player.getInventory().selectedSlot;
            InvUtils.quickSwap().fromId(hotbarSlot).to(anywhere.slot());
            swaps.add(new int[]{hotbarSlot, anywhere.slot()});
            return false;
        }

        if (!missingWarned) {
            missingWarned = true;
            warning("背包里没有 " + item.getName().getString());
        }
        return false;
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

    private void completeJob() {
        if (job == null) return;

        completed.add(job.framePos);
        while (completed.size() > 200) completed.remove(0);

        if (job.airPlace && job.targetId != null) {
            airDone.add(job.targetId);
            airDoneTime.put(job.targetId, System.currentTimeMillis());
        }

        framesPlaced++;
        job = null;
        stage = null;
        attempts = 0;
        timer = interval.get();

        if (maxFrames.get() > 0 && framesPlaced >= maxFrames.get()) {
            info("已放置 %d 个地图画", framesPlaced);
            toggle();
        }
    }

    private void discardJob(String message) {
        job = null;
        stage = null;
        attempts = 0;
        timer = interval.get();
        if (message != null) info(message);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        if (job != null) {
            Color pending = new Color(pendingColor.get());
            Color pendingOutline = new Color(pendingColor.get().r, pendingColor.get().g, pendingColor.get().b, 255);
            event.renderer.box(new Box(job.framePos), pending, pendingOutline, ShapeMode.Both, 0);
            if (job.airPlace) {
                event.renderer.box(new Box(job.support), pending, pendingOutline, ShapeMode.Both, 0);
            }
        }

        Color done = new Color(doneColor.get());
        Color doneOutline = new Color(doneColor.get().r, doneColor.get().g, doneColor.get().b, 200);
        for (BlockPos pos : completed) {
            event.renderer.box(new Box(pos), done, doneOutline, ShapeMode.Both, 0);
        }
    }
}
