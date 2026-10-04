package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

public class FastFly extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("The fly speed.")
        .defaultValue(2.5)
        .min(0.1)
        .max(20.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("The vertical fly speed.")
        .defaultValue(1.0)
        .min(0.1)
        .max(10.0)
        .build()
    );

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Prevents you from being kicked for flying.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-sneak")
        .description("Allows you to sneak while flying.")
        .defaultValue(false)
        .build()
    );

    private int tickCounter;

    public FastFly() {
        super(VacuaAddon.CATEGORY, "fast-fly", "Allows you to fly at high speed.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().allowFlying = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().allowFlying = false;
        mc.player.setVelocity(Vec3d.ZERO);

        Vec3d forward = Vec3d.fromPolar(0, mc.player.getYaw()).normalize();
        Vec3d strafe = Vec3d.fromPolar(0, mc.player.getYaw() - 90).normalize();

        double motionX = 0;
        double motionZ = 0;
        double motionY = 0;

        if (mc.options.forwardKey.isPressed()) {
            motionX += forward.x * speed.get();
            motionZ += forward.z * speed.get();
        }
        if (mc.options.backKey.isPressed()) {
            motionX -= forward.x * speed.get();
            motionZ -= forward.z * speed.get();
        }
        if (mc.options.leftKey.isPressed()) {
            motionX += strafe.x * speed.get();
            motionZ += strafe.z * speed.get();
        }
        if (mc.options.rightKey.isPressed()) {
            motionX -= strafe.x * speed.get();
            motionZ -= strafe.z * speed.get();
        }

        if (mc.options.jumpKey.isPressed()) {
            motionY = verticalSpeed.get();
        }
        if (mc.options.sneakKey.isPressed() && allowSneak.get()) {
            motionY = -verticalSpeed.get();
        }

        mc.player.setVelocity(motionX, motionY, motionZ);

        // Anti-kick: periodically nudge the player down and up
        if (antiKick.get() && !mc.player.isOnGround()) {
            tickCounter++;
            if (tickCounter % 40 == 0) {
                mc.player.setVelocity(mc.player.getVelocity().add(0, -0.04, 0));
            } else if (tickCounter % 41 == 0) {
                mc.player.setVelocity(mc.player.getVelocity().add(0, 0.04, 0));
            }
        }

        if (!allowSneak.get()) {
            mc.player.input.sneaking = false;
        }
    }
}
