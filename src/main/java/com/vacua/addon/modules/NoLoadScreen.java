package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;

/**
 * NoLoadScreen - 屏蔽加载屏
 *
 * 取消地形下载屏幕, 配合传送/穿墙模块避免黑屏卡住; 可选下界门无敌(取消传送确认)。
 */
public class NoLoadScreen extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> portalGodMode = sgGeneral.add(new BoolSetting.Builder()
        .name("portal-god-mode").description("传送门无敌(取消传送确认包, 避免门伤害).")
        .defaultValue(false).build());

    private boolean godMode;

    public NoLoadScreen() {
        super(VacuaAddon.CATEGORY, "no-load-screen", "屏蔽加载屏 - 取消地形下载屏幕.");
    }

    @Override
    public void onActivate() {
        godMode = false;
    }

    @Override
    public void onDeactivate() {
        godMode = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST + 999)
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof DownloadingTerrainScreen) {
            if (portalGodMode.get()) godMode = true;
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onPacketSend(PacketEvent.Send event) {
        if (!portalGodMode.get() || !godMode) return;
        if (event.packet instanceof TeleportConfirmC2SPacket) event.setCancelled(true);
    }
}
