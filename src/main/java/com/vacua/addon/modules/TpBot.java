package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TpBot - 随机TP机器人
 *
 * 把自己客户端位置持续同步到随机目标玩家身边(可带相对偏移/随机抖动/移动预测)。
 */
public class TpBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPredict = settings.createGroup("Predict");

    // General
    private final Setting<Boolean> toggleX = sgGeneral.add(new BoolSetting.Builder()
        .name("x").description("启用X轴跟随.")
        .defaultValue(true).build());

    private final Setting<Boolean> toggleY = sgGeneral.add(new BoolSetting.Builder()
        .name("y").description("启用Y轴跟随.")
        .defaultValue(false).build());

    private final Setting<Boolean> toggleZ = sgGeneral.add(new BoolSetting.Builder()
        .name("z").description("启用Z轴跟随.")
        .defaultValue(true).build());

    private final Setting<Double> relX = sgGeneral.add(new DoubleSetting.Builder()
        .name("relative-x").description("相对目标的X偏移.")
        .defaultValue(0.0).sliderRange(-25, 25).visible(toggleX::get).build());

    private final Setting<Double> relY = sgGeneral.add(new DoubleSetting.Builder()
        .name("relative-y").description("相对目标的Y偏移.")
        .defaultValue(0.0).sliderRange(-25, 25).visible(toggleY::get).build());

    private final Setting<Double> relZ = sgGeneral.add(new DoubleSetting.Builder()
        .name("relative-z").description("相对目标的Z偏移.")
        .defaultValue(0.0).sliderRange(-25, 25).visible(toggleZ::get).build());

    private final Setting<Boolean> randomTp = sgGeneral.add(new BoolSetting.Builder()
        .name("random-tp").description("对偏移量做随机抖动.")
        .defaultValue(false).build());

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends").description("忽略好友(不传送到好友).")
        .defaultValue(true).build());

    // Predict
    private final Setting<Boolean> predict = sgPredict.add(new BoolSetting.Builder()
        .name("predict").description("预测目标移动位置.")
        .defaultValue(true).build());

    private final Setting<Integer> predictLimit = sgPredict.add(new IntSetting.Builder()
        .name("predict-limit").description("参与预测的位置采样数.")
        .defaultValue(3).min(2).sliderMax(10).visible(predict::get).build());

    private final Setting<Double> predictPower = sgPredict.add(new DoubleSetting.Builder()
        .name("predict-power").description("预测外推倍率.")
        .defaultValue(1.0).min(0.1).sliderRange(0.1, 10.0).visible(predict::get).build());

    // State
    private Entity target;
    private final Deque<Vec3d> history = new ArrayDeque<>();

    public TpBot() {
        super(VacuaAddon.CATEGORY, "tp-bot", "TP机器人 - 自动把自身位置同步到随机目标玩家身边.");
    }

    @Override
    public void onActivate() {
        target = null;
        history.clear();
        updateTarget();
    }

    @Override
    public void onDeactivate() {
        target = null;
        history.clear();
        if (mc.player != null && mc.getNetworkHandler() != null) {
            Vec3d pos = mc.player.getPos();
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                pos.x, pos.y, pos.z, mc.player.isOnGround()));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (target == null || !target.isAlive()) {
            updateTarget();
            return;
        }

        history.addLast(target.getPos());
        while (history.size() > predictLimit.get()) history.removeFirst();

        Vec3d targetPos = predictTarget();
        if (targetPos == null) return;

        double rx = toggleX.get() ? relX.get() : 0;
        double ry = toggleY.get() ? relY.get() : 0;
        double rz = toggleZ.get() ? relZ.get() : 0;

        if (randomTp.get()) {
            if (toggleX.get()) rx *= Math.random() * 2 - 1;
            if (toggleY.get()) ry *= Math.random() * 2 - 1;
            if (toggleZ.get()) rz *= Math.random() * 2 - 1;
        }

        double x = toggleX.get() ? targetPos.x + rx : mc.player.getX();
        double y = toggleY.get() ? targetPos.y + ry : mc.player.getY();
        double z = toggleZ.get() ? targetPos.z + rz : mc.player.getZ();

        mc.player.updatePosition(x, y, z);
    }

    private Vec3d predictTarget() {
        Vec3d current = target.getPos();
        if (!predict.get() || history.size() < 2) return current;

        Vec3d diff = Vec3d.ZERO;
        Vec3d prev = null;
        for (Vec3d point : history) {
            if (prev != null) diff = diff.add(point.subtract(prev));
            prev = point;
        }
        diff = diff.multiply(1.0 / Math.max(1, history.size() - 1));

        return current.add(diff.multiply(predictPower.get()));
    }

    private void updateTarget() {
        history.clear();
        target = TargetUtils.get(entity -> {
            if (entity == mc.player || entity.getType() != EntityType.PLAYER) return false;
            if (entity instanceof LivingEntity living && living.isDead()) return false;
            if (!entity.isAlive()) return false;
            if (ignoreFriends.get() && entity instanceof PlayerEntity player) {
                return !Friends.get().isFriend(player);
            }
            return true;
        }, SortPriority.LowestDistance);
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getName().getString() : null;
    }
}
