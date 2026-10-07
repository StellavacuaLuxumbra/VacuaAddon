package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * SetVelocity - 固定速度
 *
 * 每 tick 把自己的速度设为固定向量(静态), 或开启时设置一次。
 */
public class SetVelocity extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> isStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("static").description("每 tick 持续锁定速度, 否则只设置一次.")
        .defaultValue(false).build());

    private final Setting<Double> velX = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-x").description("X 轴速度.")
        .defaultValue(0.0).range(-100.0, 100.0).sliderRange(-5.0, 5.0).build());

    private final Setting<Double> velY = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-y").description("Y 轴速度.")
        .defaultValue(0.0).range(-100.0, 100.0).sliderRange(-5.0, 5.0).build());

    private final Setting<Double> velZ = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-z").description("Z 轴速度.")
        .defaultValue(0.0).range(-100.0, 100.0).sliderRange(-5.0, 5.0).build());

    private boolean oneShot;

    public SetVelocity() {
        super(VacuaAddon.CATEGORY, "set-velocity", "固定速度 - 把自身速度设为固定向量.");
    }

    @Override
    public void onActivate() {
        oneShot = !isStatic.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (isStatic.get()) {
            apply();
        } else if (oneShot) {
            oneShot = false;
            apply();
            toggle();
        }
    }

    private void apply() {
        mc.player.setVelocity(new Vec3d(velX.get(), velY.get(), velZ.get()));
    }
}
