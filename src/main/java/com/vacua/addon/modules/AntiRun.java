package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import com.vacua.addon.utils.RotationUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class AntiRun extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgIntercept = settings.createGroup("Intercept");
    private final SettingGroup sgTrap = settings.createGroup("Trap");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("搜索目标的范围.")
        .defaultValue(30.0).range(1.0, 200.0).build());

    private final Setting<Boolean> autoEnableChase = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-enable-chase").description("自动启用ChasePlayer.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoDisableChase = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable-chase").description("陷阱完成后自动关闭ChasePlayer.")
        .defaultValue(false).build());

    private final Setting<Boolean> notifyNoObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-no-obsidian").description("没有黑曜石时通知.")
        .defaultValue(true).build());

    // Intercept
    private final Setting<Boolean> intercept = sgIntercept.add(new BoolSetting.Builder()
        .name("intercept").description("在敌人路径上拦截放置方块.")
        .defaultValue(true).build());

    private final Setting<Integer> interceptDistance = sgIntercept.add(new IntSetting.Builder()
        .name("intercept-distance").description("拦截提前量(blocks).")
        .defaultValue(4).range(1, 10).build());

    private final Setting<Integer> interceptWallHeight = sgIntercept.add(new IntSetting.Builder()
        .name("intercept-wall-height").description("拦截墙高度.")
        .defaultValue(3).range(1, 5).build());

    private final Setting<Double> interceptRange = sgIntercept.add(new DoubleSetting.Builder()
        .name("intercept-range").description("开始拦截的距离.")
        .defaultValue(7.0).range(2.0, 20.0).build());

    private final Setting<Boolean> interceptGround = sgIntercept.add(new BoolSetting.Builder()
        .name("intercept-ground").description("在地面路径上预放置.")
        .defaultValue(true).build());

    // Trap
    private final Setting<Boolean> trapEnabled = sgTrap.add(new BoolSetting.Builder()
        .name("trap").description("敌人减速后包裹.")
        .defaultValue(true).build());

    private final Setting<Double> predictTicks = sgTrap.add(new DoubleSetting.Builder()
        .name("predict-ticks").description("预判目标位置提前量(秒).")
        .defaultValue(0.3).range(0.1, 2.0).build());

    private final Setting<Boolean> autoUpdateTrap = sgTrap.add(new BoolSetting.Builder()
        .name("auto-update-trap").description("持续更新陷阱位置跟随预判.")
        .defaultValue(true).build());

    private final Setting<TrapMode> trapMode = sgTrap.add(new EnumSetting.Builder<TrapMode>()
        .name("trap-mode").description("包裹模式.")
        .defaultValue(TrapMode.Surround).build());

    private final Setting<Integer> trapDelay = sgTrap.add(new IntSetting.Builder()
        .name("trap-delay").description("包裹延迟(tick).")
        .defaultValue(0).range(0, 10).build());

    private final Setting<Boolean> airPlace = sgTrap.add(new BoolSetting.Builder()
        .name("air-place").description("空气放置(不需要相邻方块).")
        .defaultValue(true).build());

    private final Setting<Boolean> multiPlace = sgTrap.add(new BoolSetting.Builder()
        .name("multi-place").description("多放置(每tick多个方块).")
        .defaultValue(true).build());

    private final Setting<Integer> blocksPerTick = sgTrap.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("每tick放置方块数.")
        .defaultValue(4).range(1, 8)
        .visible(multiPlace::get).build());

    private final Setting<Boolean> rotate = sgTrap.add(new BoolSetting.Builder()
        .name("rotate").description("放置时旋转.")
        .defaultValue(true).build());

    private final Setting<Boolean> noSuicide = sgTrap.add(new BoolSetting.Builder()
        .name("no-suicide").description("防止把自己也包住.")
        .defaultValue(true).build());

    // Render
    private final Setting<Boolean> renderTrap = sgRender.add(new BoolSetting.Builder()
        .name("render-trap").description("渲染陷阱位置.")
        .defaultValue(true).build());

    private final Setting<SettingColor> trapColor = sgRender.add(new ColorSetting.Builder()
        .name("trap-color").description("陷阱颜色.")
        .defaultValue(new SettingColor(255, 0, 255, 80)).build());

    private final Setting<SettingColor> trapOutlineColor = sgRender.add(new ColorSetting.Builder()
        .name("trap-outline-color").description("陷阱轮廓颜色.")
        .defaultValue(new SettingColor(255, 0, 255, 200)).build());

    private final Setting<Boolean> renderIntercept = sgRender.add(new BoolSetting.Builder()
        .name("render-intercept").description("渲染拦截位置.")
        .defaultValue(true).build());

    private final Setting<SettingColor> interceptColor = sgRender.add(new ColorSetting.Builder()
        .name("intercept-color").description("拦截颜色.")
        .defaultValue(new SettingColor(255, 255, 0, 60)).build());

    // State
    private PlayerEntity currentTarget;
    private final Set<BlockPos> trapPositions = new LinkedHashSet<>();
    private final Set<BlockPos> placedPositions = new LinkedHashSet<>();
    private final Set<BlockPos> renderPositions = new LinkedHashSet<>();
    private int trapTimer;
    private boolean isTrapping;
    private Vec3d targetLastPos;
    private Vec3d smoothVelocity;
    private Vec3d targetAcceleration;
    private boolean noObsidianNotified;

    public enum TrapMode { Surround, FullBox, Cage, Pillar }

    public AntiRun() {
        super(VacuaAddon.CATEGORY, "anti-run", "反逃跑 - 黑曜石拦截和包裹逃跑的敌人");
    }

    @Override
    public void onActivate() {
        trapPositions.clear();
        placedPositions.clear();
        renderPositions.clear();
        trapTimer = 0;
        isTrapping = false;
        targetLastPos = null;
        smoothVelocity = Vec3d.ZERO;
        targetAcceleration = Vec3d.ZERO;
        noObsidianNotified = false;
    }

    @Override
    public void onDeactivate() {
        if (autoDisableChase.get()) {
            ChasePlayer chase = Modules.get().get(ChasePlayer.class);
            if (chase != null && chase.isActive()) chase.toggle();
        }
        trapPositions.clear();
        placedPositions.clear();
        renderPositions.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        currentTarget = getClosestPlayer();
        if (currentTarget == null) return;

        // Check obsidian
        if (findObsidian() == -1) {
            if (notifyNoObsidian.get() && !noObsidianNotified) {
                noObsidianNotified = true;
                info("没有黑曜石，无法拦截/包裹");
            }
            return;
        }
        noObsidianNotified = false;

        // Auto enable ChasePlayer
        if (autoEnableChase.get()) {
            ChasePlayer chase = Modules.get().get(ChasePlayer.class);
            if (chase != null && !chase.isActive()) chase.toggle();
        }

        // Smooth velocity tracking (exponential moving average)
        Vec3d rawVelocity = Vec3d.ZERO;
        if (targetLastPos != null) {
            rawVelocity = currentTarget.getPos().subtract(targetLastPos);
            // EMA smoothing factor: 0.3 for quick response
            smoothVelocity = smoothVelocity.multiply(0.7).add(rawVelocity.multiply(0.3));
            // Acceleration = change in velocity
            targetAcceleration = rawVelocity.subtract(smoothVelocity).multiply(20.0);
        }
        targetLastPos = currentTarget.getPos();

        double dist = mc.player.distanceTo(currentTarget);
        boolean isSlowed = isTargetSlowed();

        if (!isTrapping) {
            if (intercept.get() && dist < interceptRange.get() && dist > 1.5) {
                doIntercept();
            }

            if (trapEnabled.get() && (isSlowed || dist < 2.5)) {
                isTrapping = true;
                trapTimer = trapDelay.get();
                calculateTrapPositions();
            }
        } else {
            if (trapTimer > 0) { trapTimer--; return; }
            placeTrapBlocks();

            // Reset if target escaped far
            if (currentTarget != null && mc.player.distanceTo(currentTarget) > 5.0) {
                isTrapping = false;
                trapPositions.clear();
                placedPositions.clear();
            }
        }
    }

    private void doIntercept() {
        if (currentTarget == null || mc.player == null) return;

        Vec3d vel = smoothVelocity.lengthSquared() > 0.001
            ? smoothVelocity.normalize()
            : currentTarget.getPos().subtract(mc.player.getPos()).normalize();

        int placed = 0;
        int maxTotal = interceptDistance.get() * interceptWallHeight.get() * 3;

        for (int i = 1; i <= interceptDistance.get(); i++) {
            Vec3d interceptPos = currentTarget.getPos().add(vel.multiply(i));

            for (int y = 0; y < interceptWallHeight.get(); y++) {
                BlockPos blockPos = new BlockPos(
                    (int) Math.floor(interceptPos.x),
                    (int) Math.floor(interceptPos.y) + y,
                    (int) Math.floor(interceptPos.z)
                );

                if (canPlace(blockPos)) {
                    if (rotate.get()) {
                        float[] r = RotationUtil.getRotation(mc.player.getEyePos(), blockPos.toCenterPos());
                        Rotations.rotate(r[0], r[1]);
                    }
                    placeBlock(blockPos);
                    renderPositions.add(blockPos);
                    placed++;
                }

                // Side blocks for wall effect
                if (y == 0 && i <= 2) {
                    for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                        BlockPos side = blockPos.offset(dir);
                        if (canPlace(side) && placed < maxTotal) {
                            placeBlock(side);
                            renderPositions.add(side);
                            placed++;
                        }
                    }
                }
            }
        }

        // Ground pre-place: place obsidian on the ground in target's path
        if (interceptGround.get()) {
            for (int i = 1; i <= Math.min(interceptDistance.get(), 3); i++) {
                Vec3d groundPos = currentTarget.getPos().add(vel.multiply(i));
                BlockPos groundBlock = new BlockPos(
                    (int) Math.floor(groundPos.x),
                    (int) Math.floor(groundPos.y) - 1,
                    (int) Math.floor(groundPos.z)
                );
                if (canPlace(groundBlock) && placed < maxTotal) {
                    placeBlock(groundBlock);
                    renderPositions.add(groundBlock);
                    placed++;
                }
            }
        }
    }

    private void calculateTrapPositions() {
        if (currentTarget == null) return;
        trapPositions.clear();

        Vec3d futurePos = getPredictedPos();
        BlockPos predictedPos = new BlockPos(
            (int) Math.floor(futurePos.x),
            (int) Math.floor(futurePos.y),
            (int) Math.floor(futurePos.z)
        );

        Vec3d vel = smoothVelocity.lengthSquared() > 0.001 ? smoothVelocity : Vec3d.ZERO;
        Direction moveDir = getHorizontalDirection(vel);
        Direction[] sortedDirs = moveDir != null ? getDirsSortedByMovement(moveDir) : horizontals();

        switch (trapMode.get()) {
            case Surround -> {
                for (Direction dir : sortedDirs) {
                    trapPositions.add(predictedPos.offset(dir));
                    trapPositions.add(predictedPos.up().offset(dir));
                    trapPositions.add(predictedPos.up(2).offset(dir));
                }
            }
            case FullBox -> {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && z == 0) continue;
                        for (int y = 0; y <= 2; y++) {
                            trapPositions.add(predictedPos.add(x, y, z));
                        }
                    }
                }
            }
            case Cage -> {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        for (int y = -1; y <= 2; y++) {
                            if (x == 0 && z == 0 && (y == 0 || y == 1)) continue;
                            trapPositions.add(predictedPos.add(x, y, z));
                        }
                    }
                }
            }
            case Pillar -> {
                for (Direction dir : sortedDirs) {
                    BlockPos pos = predictedPos.offset(dir);
                    trapPositions.add(pos);
                    trapPositions.add(pos.up());
                    trapPositions.add(pos.up(2));
                }
                for (Direction dir : sortedDirs) {
                    trapPositions.add(predictedPos.up(3).offset(dir));
                }
                trapPositions.add(predictedPos.up(3));
            }
        }

        trapPositions.removeAll(placedPositions);

        if (noSuicide.get() && mc.player != null) {
            BlockPos pp = mc.player.getBlockPos();
            trapPositions.removeIf(pos ->
                pos.equals(pp) || pos.equals(pp.up()) || pos.equals(pp.down()));
        }
    }

    private Vec3d getPredictedPos() {
        double tickOffset = predictTicks.get() * 20.0;
        Vec3d vel = smoothVelocity.lengthSquared() > 0.001 ? smoothVelocity : Vec3d.ZERO;
        Vec3d pos = currentTarget.getPos().add(vel.multiply(tickOffset));
        // Add acceleration correction for curved paths
        pos = pos.add(targetAcceleration.multiply(0.5 * tickOffset * tickOffset / 400.0));
        return pos;
    }

    private boolean isTargetSlowed() {
        if (smoothVelocity == null) return false;
        double hSpeed = Math.sqrt(smoothVelocity.x * smoothVelocity.x + smoothVelocity.z * smoothVelocity.z);
        // Also check if acceleration is negative (decelerating)
        double hAccel = Math.sqrt(targetAcceleration.x * targetAcceleration.x + targetAcceleration.z * targetAcceleration.z);
        return hSpeed < 0.05 || (hAccel > 0.5 && hSpeed < 0.15);
    }

    private void placeTrapBlocks() {
        if (trapPositions.isEmpty()) {
            isTrapping = false;
            renderPositions.clear();
            if (autoDisableChase.get()) {
                ChasePlayer chase = Modules.get().get(ChasePlayer.class);
                if (chase != null && chase.isActive()) chase.toggle();
            }
            return;
        }

        if (autoUpdateTrap.get() && currentTarget != null && mc.player.distanceTo(currentTarget) > 1.0) {
            calculateTrapPositions();
        }

        int placed = 0;
        int maxPerTick = multiPlace.get() ? blocksPerTick.get() : 1;

        Iterator<BlockPos> it = trapPositions.iterator();
        while (it.hasNext() && placed < maxPerTick) {
            BlockPos pos = it.next();
            if (canPlace(pos)) {
                if (rotate.get()) {
                    float[] r = RotationUtil.getRotation(mc.player.getEyePos(), pos.toCenterPos());
                    Rotations.rotate(r[0], r[1]);
                }
                placeBlock(pos);
                placedPositions.add(pos);
                renderPositions.add(pos);
                it.remove();
                placed++;
            }
        }
    }

    private Direction getHorizontalDirection(Vec3d vel) {
        if (vel.lengthSquared() < 0.0001) return null;
        return Math.abs(vel.x) > Math.abs(vel.z)
            ? (vel.x > 0 ? Direction.EAST : Direction.WEST)
            : (vel.z > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private Direction[] getDirsSortedByMovement(Direction moveDir) {
        return new Direction[]{moveDir, moveDir.rotateYCounterclockwise(), moveDir.rotateYClockwise(), moveDir.getOpposite()};
    }

    private Direction[] horizontals() {
        return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    }

    private boolean canPlace(BlockPos pos) {
        if (mc.world == null) return false;
        if (!mc.world.isAir(pos)) return false;
        if (mc.player.getEyePos().distanceTo(pos.toCenterPos()) > 5.5) return false;
        if (airPlace.get()) return true;
        for (Direction dir : Direction.values()) {
            if (!mc.world.isAir(pos.offset(dir))) return true;
        }
        return false;
    }

    private void placeBlock(BlockPos pos) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        int slot = findObsidian();
        if (slot == -1) return;

        int old = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;

        Direction side = getPlacementSide(pos);
        if (side == null) { mc.player.getInventory().selectedSlot = old; return; }

        BlockPos neighbor = pos.offset(side);
        Vec3d hitVec = neighbor.toCenterPos().add(
            side.getOpposite().getVector().getX() * 0.5,
            side.getOpposite().getVector().getY() * 0.5,
            side.getOpposite().getVector().getZ() * 0.5
        );

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(hitVec, side.getOpposite(), neighbor, false));
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.getInventory().selectedSlot = old;
    }

    private Direction getPlacementSide(BlockPos pos) {
        if (mc.world == null) return null;
        if (airPlace.get()) {
            Vec3d dir = mc.player.getEyePos().subtract(pos.toCenterPos()).normalize();
            return Math.abs(dir.x) > Math.abs(dir.z)
                ? (dir.x > 0 ? Direction.WEST : Direction.EAST)
                : (dir.z > 0 ? Direction.NORTH : Direction.SOUTH);
        }
        for (Direction d : Direction.values()) {
            if (!mc.world.isAir(pos.offset(d))) return d.getOpposite();
        }
        return null;
    }

    private int findObsidian() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.OBSIDIAN) return i;
        }
        return -1;
    }

    private PlayerEntity getClosestPlayer() {
        if (mc.player == null || mc.world == null) return null;
        PlayerEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && entity != mc.player) {
                double dist = mc.player.squaredDistanceTo(entity);
                if (dist <= range.get() * range.get() && dist < closestDist) {
                    closest = player;
                    closestDist = dist;
                }
            }
        }
        return closest;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        if (renderTrap.get()) {
            Color fill = new Color(trapColor.get());
            Color outline = new Color(trapOutlineColor.get());
            for (BlockPos pos : trapPositions) {
                if (renderPositions.contains(pos)) continue;
                event.renderer.box(new net.minecraft.util.math.Box(pos), fill, outline, ShapeMode.Both, 0);
            }
        }

        if (renderIntercept.get()) {
            Color fill = new Color(interceptColor.get());
            Color outline = new Color(interceptColor.get().r, interceptColor.get().g, interceptColor.get().b, 200);
            for (BlockPos pos : renderPositions) {
                event.renderer.box(new net.minecraft.util.math.Box(pos), fill, outline, ShapeMode.Both, 0);
            }
        }

        if (currentTarget != null) {
            event.renderer.box(currentTarget.getBoundingBox(),
                new Color(255, 0, 0, 30), new Color(255, 0, 0, 150), ShapeMode.Both, 0);

            Vec3d futurePos = getPredictedPos();
            event.renderer.box(
                new net.minecraft.util.math.Box(futurePos.add(-0.3, 0, -0.3), futurePos.add(0.3, 1.8, 0.3)),
                new Color(255, 255, 0, 20), new Color(255, 255, 0, 150), ShapeMode.Both, 0);
        }
    }
}
