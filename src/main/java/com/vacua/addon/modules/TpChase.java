package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import com.vacua.addon.utils.RotationUtil;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * TpChase - TP追人
 *
 * 开启后自动循环发送移动包瞬移(clicktp方式)到敌人身边。
 */
public class TpChase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("搜索目标的范围.")
        .defaultValue(50.0).range(1.0, 200.0).build());

    private final Setting<Double> minDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-distance").description("小于该距离时不再TP.")
        .defaultValue(2.0).range(0.5, 20.0).build());

    private final Setting<Double> tpDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("tp-distance").description("TP到目标身边的距离.")
        .defaultValue(1.5).range(0.5, 5.0).build());

    private final Setting<Double> yOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-offset").description("落点垂直偏移.")
        .defaultValue(0.0).range(-5.0, 5.0).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("两次TP之间的间隔(tick).")
        .defaultValue(10).range(0, 100).build());

    private final Setting<Integer> steps = sgGeneral.add(new IntSetting.Builder()
        .name("steps").description("每次TP发送的移动包步数.")
        .defaultValue(4).range(1, 20).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("TP时看向目标(静默旋转).")
        .defaultValue(true).build());

    private final Setting<Boolean> safe = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-place").description("只TP到空气落点(避免卡进方块).")
        .defaultValue(true).build());

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick").description("发送微小位移包防踢.")
        .defaultValue(true).build());

    // Render
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("渲染落点.")
        .defaultValue(true).build());

    private final Setting<SettingColor> renderColor = sgRender.add(new ColorSetting.Builder()
        .name("color").description("落点颜色.")
        .defaultValue(new SettingColor(0, 255, 255, 120)).build());

    // State
    private int cooldown;
    private int antiKickTimer;
    private Vec3d destination;

    private static final double[] ANGLES = {0, 90, -90, 180, 45, -45, 135, -135};

    public TpChase() {
        super(VacuaAddon.CATEGORY, "tp-chase", "TP追人 - 自动循环发包瞬移到敌人身边");
    }

    @Override
    public void onActivate() {
        cooldown = 0;
        antiKickTimer = 0;
        destination = null;
    }

    @Override
    public void onDeactivate() {
        destination = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (cooldown > 0) cooldown--;

        PlayerEntity target = getClosestPlayer();
        if (target == null) {
            runAntiKick();
            return;
        }

        double dist = mc.player.distanceTo(target);
        if (dist < minDistance.get() || cooldown > 0) {
            runAntiKick();
            return;
        }

        teleport(target, dist);
        cooldown = delay.get();
    }

    private void teleport(PlayerEntity target, double dist) {
        Vec3d to = findDestination(target);
        destination = to;

        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        if (rotate.get()) {
            Vec3d eye = to.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
            float[] r = RotationUtil.getRotation(eye, target.getEyePos());
            yaw = r[0];
            pitch = r[1];
        }

        Vec3d from = mc.player.getPos();
        int count = Math.max(steps.get(), (int) Math.ceil(dist / 6.0));
        boolean onGround = mc.player.isOnGround();

        for (int i = 1; i <= count; i++) {
            Vec3d p = from.lerp(to, (double) i / count);
            sendMove(p, yaw, pitch, i == count && onGround);
        }

        mc.player.setPosition(to.x, to.y, to.z);
        mc.player.setVelocity(Vec3d.ZERO);
        mc.player.fallDistance = 0;
    }

    private void sendMove(Vec3d pos, float yaw, float pitch, boolean onGround) {
        if (mc.getNetworkHandler() == null) return;
        if (rotate.get()) {
            mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.Full(pos.x, pos.y, pos.z, yaw, pitch, onGround));
        } else {
            mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, onGround));
        }
    }

    private Vec3d findDestination(PlayerEntity target) {
        Vec3d base = target.getPos().add(0, yOffset.get(), 0);

        Vec3d dir = mc.player.getPos().subtract(target.getPos());
        dir = new Vec3d(dir.x, 0, dir.z);
        if (dir.lengthSquared() < 1.0E-4) dir = Vec3d.fromPolar(0, target.getYaw() + 180);
        dir = dir.normalize();

        if (!safe.get()) return base.add(dir.multiply(tpDistance.get()));

        double[] dists = {tpDistance.get(), tpDistance.get() + 1.0, tpDistance.get() + 2.0};
        for (double d : dists) {
            for (double angle : ANGLES) {
                Vec3d p = base.add(rotateY(dir, angle).multiply(d));
                if (isClear(p)) return p;
            }
        }

        return base.add(dir.multiply(tpDistance.get()));
    }

    private Vec3d rotateY(Vec3d dir, double angleDegrees) {
        double rad = Math.toRadians(angleDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3d(dir.x * cos - dir.z * sin, dir.y, dir.x * sin + dir.z * cos);
    }

    private boolean isClear(Vec3d pos) {
        if (mc.world == null) return false;
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y + 0.1, pos.z);
        BlockPos head = BlockPos.ofFloored(pos.x, pos.y + 1.7, pos.z);
        return isPassable(feet) && isPassable(head);
    }

    private boolean isPassable(BlockPos pos) {
        return mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private void runAntiKick() {
        if (!antiKick.get() || mc.player == null || mc.getNetworkHandler() == null) return;
        if (mc.player.isOnGround()) {
            antiKickTimer = 0;
            return;
        }

        if (++antiKickTimer >= 40) {
            antiKickTimer = 0;
            Vec3d p = mc.player.getPos();
            mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(p.x, p.y - 0.01, p.z, false));
        }
    }

    private PlayerEntity getClosestPlayer() {
        PlayerEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        double maxSq = range.get() * range.get();

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

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || destination == null || mc.player == null) return;

        Color color = new Color(renderColor.get());
        Color outline = new Color(renderColor.get().r, renderColor.get().g, renderColor.get().b, 255);
        Vec3d min = destination.add(-0.3, 0, -0.3);
        Vec3d max = destination.add(0.3, 1.8, 0.3);
        event.renderer.box(new net.minecraft.util.math.Box(min, max), color, outline, ShapeMode.Both, 0);
    }
}
