package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Random;

/**
 * AutoEz - 死亡自动发言
 *
 * 自己或附近玩家死亡时自动发送自定义文本(可加随机后缀)。
 */
public class AutoEz extends Module {
    private static final String RANDOM_CHARS = "123456789abcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new Random();

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("检测其他玩家死亡的范围(格, 0 = 不限).")
        .defaultValue(20).min(0).max(50).sliderRange(0, 50).build());

    private final Setting<String> selfDeathText = sgGeneral.add(new StringSetting.Builder()
        .name("self-death-text").description("自己死亡时发送的文本.")
        .defaultValue("gg").build());

    private final Setting<String> otherDeathText = sgGeneral.add(new StringSetting.Builder()
        .name("other-death-text").description("别人死亡时发送的文本.")
        .defaultValue("ez").build());

    private final Setting<Integer> randomLength = sgGeneral.add(new IntSetting.Builder()
        .name("random-length").description("随机后缀长度(0 = 关闭).")
        .defaultValue(0).min(0).max(20).sliderRange(0, 20).build());

    private boolean deathSent;

    public AutoEz() {
        super(VacuaAddon.CATEGORY, "auto-ez", "死亡自动发言 - 自己/附近玩家死亡时发送自定义文本.");
    }

    @Override
    public void onActivate() {
        deathSent = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (mc.player.getHealth() > 0) {
            deathSent = false;
            return;
        }
        if (deathSent) return;
        deathSent = true;
        send(selfDeathText.get());
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (!(event.entity instanceof PlayerEntity player) || player == mc.player) return;
        if (!player.isDead()) return;

        double maxRange = range.get();
        if (maxRange > 0 && mc.player.squaredDistanceTo(player) > maxRange * maxRange) return;

        String suffix = randomLength.get() > 0 ? " " + random(randomLength.get()) : "";
        send(player.getName().getString() + " " + otherDeathText.get() + suffix);
    }

    private void send(String message) {
        if (message == null || message.isBlank()) return;
        mc.getNetworkHandler().sendChatMessage(message);
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_CHARS.charAt(RANDOM.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }
}
