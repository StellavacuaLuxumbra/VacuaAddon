package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * PhaseCheck - 卡方块检测
 *
 * 检测自己是否卡进实心方块, 卡住时在屏幕中央显示提示。
 */
public class PhaseCheck extends Module {
    public enum DisplayMode {
        Constant,
        Blink
    }

    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Double> xOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("x-offset").description("文字水平偏移.")
        .defaultValue(0.0).range(-1000.0, 1000.0).sliderRange(-500.0, 500.0).build());

    private final Setting<Double> yOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-offset").description("文字垂直偏移.")
        .defaultValue(10.0).range(-1000.0, 1000.0).sliderRange(-500.0, 500.0).build());

    private final Setting<Double> textSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("text-size").description("文字大小.")
        .defaultValue(1.0).range(0.5, 3.0).sliderRange(0.5, 3.0).build());

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color").description("文字颜色.")
        .defaultValue(new SettingColor(0, 255, 0, 255)).build());

    private final Setting<DisplayMode> displayMode = sgGeneral.add(new EnumSetting.Builder<DisplayMode>()
        .name("display-mode").description("显示方式.")
        .defaultValue(DisplayMode.Constant).build());

    private final Setting<Double> blinkSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("blink-speed").description("闪烁速度.")
        .defaultValue(1.0).range(0.1, 5.0).sliderRange(0.1, 5.0)
        .visible(() -> displayMode.get() == DisplayMode.Blink).build());

    private final Setting<Boolean> textShadow = sgGeneral.add(new BoolSetting.Builder()
        .name("text-shadow").description("文字阴影.")
        .defaultValue(true).build());

    private final String text = "Phaseable!";

    public PhaseCheck() {
        super(VacuaAddon.CATEGORY, "phase-check", "卡方块检测 - 卡进方块时在屏幕显示提示.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || !stuckInBlocks()) return;

        double size = textSize.get();
        Color color = textColor.get().copy();

        if (displayMode.get() == DisplayMode.Blink) {
            double phase = Math.sin(System.currentTimeMillis() / 1000.0 * blinkSpeed.get() * Math.PI * 2);
            color.a = (int) Math.round(color.a * (phase * 0.5 + 0.5));
        }

        double width = TextRenderer.get().getWidth(text) * size;
        double x = event.screenWidth / 2.0 - width / 2.0 + xOffset.get();
        double y = yOffset.get();

        TextRenderer.get().begin(size, false, textShadow.get());
        TextRenderer.get().render(text, x, y, color, textShadow.get());
        TextRenderer.get().end();
    }

    private boolean stuckInBlocks() {
        Box playerBox = mc.player.getBoundingBox();

        int minX = (int) Math.floor(playerBox.minX);
        int minY = (int) Math.floor(playerBox.minY);
        int minZ = (int) Math.floor(playerBox.minZ);
        int maxX = (int) Math.floor(playerBox.maxX);
        int maxY = (int) Math.floor(playerBox.maxY);
        int maxZ = (int) Math.floor(playerBox.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isAir() || !state.isSolidBlock(mc.world, pos)) continue;

                    Box blockBox = state.getCollisionShape(mc.world, pos).getBoundingBox().offset(pos);
                    if (playerBox.intersects(blockBox)) return true;
                }
            }
        }

        return false;
    }
}
