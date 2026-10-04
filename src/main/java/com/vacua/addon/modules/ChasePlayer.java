package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ChasePlayer - 追逐玩家模块 (增强版)
 *
 * 速度预测、加速度追踪、始终能追上、下方追逐、不锁定视角。
 */
public class ChasePlayer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPredict = settings.createGroup("Prediction");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("搜索目标的范围.")
        .defaultValue(50.0).range(1.0, 200.0).build());

    private final Setting<Double> flySpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fly-speed").description("飞行速度.")
        .defaultValue(2.5).range(0.5, 30.0).build());

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("垂直飞行速度.")
        .defaultValue(1.0).range(0.1, 10.0).build());

    private final Setting<Boolean> autoSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sprint").description("自动冲刺.")
        .defaultValue(true).build());

    private final Setting<Boolean> chaseBelow = sgGeneral.add(new BoolSetting.Builder()
        .name("chase-below").description("在目标下方追逐.")
        .defaultValue(false).build());

    private final Setting<Double> belowOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("below-offset").description("下方追逐偏移量.")
        .defaultValue(2.0).range(0.5, 10.0)
        .visible(chaseBelow::get).build());

    // Prediction
    private final Setting<Boolean> predictVelocity = sgPredict.add(new BoolSetting.Builder()
        .name("predict-velocity").description("预测目标速度.")
        .defaultValue(true).build());

    private final Setting<Integer> historySize = sgPredict.add(new IntSetting.Builder()
        .name("history-size").description("速度采样历史长度.")
        .defaultValue(10).range(3, 50)
        .visible(predictVelocity::get).build());

    private final Setting<Double> predictTicks = sgPredict.add(new DoubleSetting.Builder()
        .name("predict-ticks").description("预测未来tick数.")
        .defaultValue(5.0).range(1.0, 30.0)
        .visible(predictVelocity::get).build());

    private final Setting<Boolean> predictAcceleration = sgPredict.add(new BoolSetting.Builder()
        .name("predict-acceleration").description("预测加速度(转弯/加速).")
        .defaultValue(true)
        .visible(predictVelocity::get).build());

    private final Setting<Boolean> speedMultiplier = sgPredict.add(new BoolSetting.Builder()
        .name("speed-multiplier").description("自动加速确保追上.")
        .defaultValue(true).build());

    private final Setting<Double> multiplierValue = sgPredict.add(new DoubleSetting.Builder()
        .name("multiplier-value").description("速度倍率.")
        .defaultValue(1.2).range(1.0, 5.0)
        .visible(speedMultiplier::get).build());

    private final Setting<Boolean> smoothSpeed = sgPredict.add(new BoolSetting.Builder()
        .name("smooth-speed").description("平滑速度过渡.")
        .defaultValue(true).build());

    // Render
    private final Setting<Boolean> renderLine = sgRender.add(new BoolSetting.Builder()
        .name("render-line").description("渲染连线.")
        .defaultValue(true).build());

    private final Setting<ShapeMode> lineShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("渲染模式.")
        .defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("color").description("连线颜色.")
        .defaultValue(new SettingColor(255, 0, 0, 255)).build());

    private final Setting<SettingColor> lineFillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("填充颜色.")
        .defaultValue(new SettingColor(255, 0, 0, 50)).build());

    // State
    private final Deque<Vec3d> positionHistory = new ArrayDeque<>();
    private Vec3d lastVelocity = Vec3d.ZERO;
    private Vec3d smoothedAcceleration = Vec3d.ZERO;
    private double currentSpeed = 0;

    public ChasePlayer() {
        super(VacuaAddon.CATEGORY, "chase-player", "追逐玩家 - 速度预测/加速度追踪/下方追逐");
    }

    @Override
    public void onActivate() {
        positionHistory.clear();
        lastVelocity = Vec3d.ZERO;
        smoothedAcceleration = Vec3d.ZERO;
        currentSpeed = 0;
    }

    @Override
    public void onDeactivate() {
        positionHistory.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.options.sprintKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        PlayerEntity target = getClosestPlayer(mc);
        if (target == null) return;

        boolean wPressed = mc.options.forwardKey.isPressed();
        if (!wPressed) return;

        // Record history
        positionHistory.addLast(target.getPos());
        while (positionHistory.size() > historySize.get()) positionHistory.removeFirst();

        // Calculate predicted position
        Vec3d targetPos;
        if (predictVelocity.get() && positionHistory.size() >= 2) {
            Vec3d predictedVel = predictVelocity();
            Vec3d predictedPos = target.getPos().add(predictedVel.multiply(predictTicks.get()));

            // Acceleration correction for sharp turns
            if (predictAcceleration.get() && positionHistory.size() >= 3) {
                Vec3d accel = predictAcceleration();
                double t = predictTicks.get();
                predictedPos = predictedPos.add(accel.multiply(0.5 * t * t / 400.0));
            }

            if (chaseBelow.get()) predictedPos = predictedPos.add(0, -belowOffset.get(), 0);
            targetPos = predictedPos;
        } else {
            targetPos = target.getBoundingBox().getCenter();
            if (chaseBelow.get()) targetPos = targetPos.add(0, -belowOffset.get(), 0);
        }

        // Direction to target
        Vec3d playerPos = mc.player.getPos();
        Vec3d direction = targetPos.subtract(playerPos);
        double dist = direction.length();
        if (dist < 0.1) return;

        // Speed calculation with smooth transition
        double targetSpeed = flySpeed.get();
        if (speedMultiplier.get()) {
            double enemySpeed = getTargetSpeed();
            targetSpeed = Math.max(targetSpeed, enemySpeed * multiplierValue.get());
        }

        // Smooth speed ramping
        if (smoothSpeed.get()) {
            double diff = targetSpeed - currentSpeed;
            currentSpeed += diff * 0.15; // 15% interpolation per tick
        } else {
            currentSpeed = targetSpeed;
        }

        // Slow down when very close to avoid overshooting
        double effectiveSpeed = currentSpeed;
        if (dist < 3.0) effectiveSpeed *= dist / 3.0;

        Vec3d move = direction.normalize().multiply(effectiveSpeed);

        // Vertical
        double motionY = 0;
        if (mc.options.jumpKey.isPressed()) {
            motionY = verticalSpeed.get();
        } else if (mc.options.sneakKey.isPressed()) {
            motionY = -verticalSpeed.get();
        } else if (predictVelocity.get()) {
            double dy = targetPos.y - playerPos.y;
            if (Math.abs(dy) > 0.3) {
                motionY = Math.signum(dy) * Math.min(Math.abs(dy) * 0.3, verticalSpeed.get());
            }
        }

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().allowFlying = false;
        mc.player.setVelocity(move.x, motionY, move.z);

        if (autoSprint.get() && mc.player.getHungerManager().getFoodLevel() > 6) {
            mc.options.sprintKey.setPressed(true);
        }
    }

    private Vec3d predictVelocity() {
        if (positionHistory.size() < 2) return Vec3d.ZERO;

        Vec3d total = Vec3d.ZERO;
        Vec3d[] arr = positionHistory.toArray(new Vec3d[0]);
        int count = 0;

        // Weighted moving average: exponential weighting favors recent samples
        for (int i = 1; i < arr.length; i++) {
            Vec3d vel = arr[i].subtract(arr[i - 1]);
            double weight = Math.pow(2, i - arr.length); // exponential decay
            total = total.add(vel.multiply(weight));
            count++;
        }

        if (count == 0) return Vec3d.ZERO;

        Vec3d avgVel = total.multiply(1.0 / count).multiply(20.0);

        // Update acceleration tracking
        Vec3d rawAccel = avgVel.subtract(lastVelocity).multiply(20.0);
        smoothedAcceleration = smoothedAcceleration.multiply(0.7).add(rawAccel.multiply(0.3));
        lastVelocity = avgVel;

        return avgVel;
    }

    private Vec3d predictAcceleration() {
        if (positionHistory.size() < 3) return Vec3d.ZERO;
        return smoothedAcceleration;
    }

    private double getTargetSpeed() {
        if (positionHistory.size() < 2) return 0;
        return predictVelocity().length() / 20.0;
    }

    private PlayerEntity getClosestPlayer(MinecraftClient mc) {
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
        if (!renderLine.get()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        PlayerEntity target = getClosestPlayer(mc);
        if (target == null) return;

        Vec3d from = mc.player.getPos().add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        Vec3d to = target.getPos().add(0, target.getEyeHeight(target.getPose()) / 2.0, 0);

        Color fill = new Color(lineFillColor.get());
        Color outline = new Color(lineColor.get());
        event.renderer.line(from.x, from.y, from.z, to.x, to.y, to.z, outline);
        event.renderer.box(target.getBoundingBox(), fill, outline, lineShapeMode.get(), 0);

        if (predictVelocity.get() && positionHistory.size() >= 2) {
            Vec3d predictedVel = predictVelocity();
            Vec3d predictedPos = target.getPos().add(predictedVel.multiply(predictTicks.get()));
            if (predictAcceleration.get() && positionHistory.size() >= 3) {
                Vec3d accel = predictAcceleration();
                double t = predictTicks.get();
                predictedPos = predictedPos.add(accel.multiply(0.5 * t * t / 400.0));
            }
            if (chaseBelow.get()) predictedPos = predictedPos.add(0, -belowOffset.get(), 0);

            Box predBox = new Box(predictedPos.add(-0.3, 0, -0.3), predictedPos.add(0.3, 1.8, 0.3));
            event.renderer.box(predBox,
                new Color(0, 255, 0, 30), new Color(0, 255, 0, 150), ShapeMode.Both, 0);
        }
    }
}
