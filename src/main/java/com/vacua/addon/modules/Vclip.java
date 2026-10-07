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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Vclip - 垂直穿墙
 *
 * 分多步发送上升移动包把角色顶进天花板/方块内, 用于向上 clip。
 */
public class Vclip extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance").description("向上 clip 的距离.")
        .defaultValue(3.0).range(0.1, 20.0).sliderRange(0.1, 20.0).build());

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets").description("发送的移动包数量.")
        .defaultValue(20).min(1).max(100).sliderRange(1, 50).build());

    private final Setting<Boolean> force = sgGeneral.add(new BoolSetting.Builder()
        .name("force").description("不要求装备鞘翅.")
        .defaultValue(true).build());

    private final Setting<Boolean> disableFlying = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-flying").description("clip 完成后关闭飞行.")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render").description("渲染 clip 轨迹.")
        .defaultValue(true).build());

    private final Setting<SettingColor> boxColor = sgGeneral.add(new ColorSetting.Builder()
        .name("box-color").description("轨迹边框颜色.")
        .defaultValue(new SettingColor(255, 0, 0, 255)).visible(render::get).build());

    private final Setting<SettingColor> fillColor = sgGeneral.add(new ColorSetting.Builder()
        .name("fill-color").description("轨迹填充颜色.")
        .defaultValue(new SettingColor(255, 0, 0, 50)).visible(render::get).build());

    private Vec3d currentPos;
    private double targetY;

    public Vclip() {
        super(VacuaAddon.CATEGORY, "vclip", "垂直穿墙 - 分步发包向上 clip.");
    }

    @Override
    public void onActivate() {
        currentPos = null;
        targetY = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (!force.get() && !mc.player.isFallFlying()) {
            error("需要装备鞘翅才能使用, 或打开 force.");
            toggle();
            return;
        }

        clipUpwards();
        toggle();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || currentPos == null) return;

        double width = 0.6;
        Box box = new Box(
            currentPos.x - width / 2, currentPos.y, currentPos.z - width / 2,
            currentPos.x + width / 2, targetY, currentPos.z + width / 2
        );
        event.renderer.box(box, fillColor.get(), boxColor.get(), ShapeMode.Both, 0);
    }

    private void clipUpwards() {
        currentPos = mc.player.getPos();
        targetY = currentPos.y + distance.get();
        double step = distance.get() / packets.get();

        for (int i = 1; i <= packets.get(); i++) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                currentPos.x, currentPos.y + step * i, currentPos.z, mc.player.isOnGround()));
        }

        mc.player.setPos(currentPos.x, targetY, currentPos.z);

        if (disableFlying.get()) {
            mc.player.getAbilities().flying = false;
        }
    }
}
