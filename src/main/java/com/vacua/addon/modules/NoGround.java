package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * NoGround - 防摔伤
 *
 * 把所有移动包的 onGround 改为 false, 服务器认为你一直在下落从而不结算摔落伤害。
 */
public class NoGround extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> yVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-velocity").description("关闭时给自己的向上速度.")
        .defaultValue(0.1).range(0.1, 1.0).sliderRange(0.1, 1.0).build());

    public NoGround() {
        super(VacuaAddon.CATEGORY, "no-ground", "防摔伤 - 移动包始终上报 onGround=false.");
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket packet) {
            ((PlayerMoveC2SPacketAccessor) packet).setOnGround(false);
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            mc.player.setVelocity(0, yVelocity.get(), 0);
        }
    }
}
