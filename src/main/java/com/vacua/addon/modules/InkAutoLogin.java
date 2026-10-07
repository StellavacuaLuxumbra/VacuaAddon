package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

/**
 * InkAutoLogin - ink自动登录
 *
 * 进服后: 1) 发送 /login 密码  2) 右键快捷栏里的下界之星  3) 用发包方式丢掉打开GUI中的所有物品。
 */
public class InkAutoLogin extends Module {
    public enum ThrowMode {
        Stack,
        Single
    }

    private enum State {
        Idle,
        WaitLogin,
        WaitUse,
        WaitGui,
        WaitThrow,
        Throwing,
        Done
    }

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgThrow = settings.createGroup("Throw");

    // General
    private final Setting<String> password = sgGeneral.add(new StringSetting.Builder()
        .name("password").description("/login 使用的密码.")
        .defaultValue("").build());

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot").description("下界之星所在的快捷栏槽位(0-8, 第5格 = 4).")
        .defaultValue(4).min(0).max(8).sliderRange(0, 8).build());

    private final Setting<Boolean> checkItem = sgGeneral.add(new BoolSetting.Builder()
        .name("check-item").description("右键前检查该槽位是否为下界之星.")
        .defaultValue(true).build());

    private final Setting<String> serverFilter = sgGeneral.add(new StringSetting.Builder()
        .name("server-filter").description("只在服务器地址包含该文本时触发(留空 = 任意).")
        .defaultValue("ink").build());

    private final Setting<Boolean> autoTrigger = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-trigger").description("进入世界后自动执行流程.")
        .defaultValue(true).build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify").description("输出执行进度到聊天栏.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("流程完成后自动关闭模块.")
        .defaultValue(false).build());

    // Timing
    private final Setting<Integer> loginDelay = sgTiming.add(new IntSetting.Builder()
        .name("login-delay").description("进服后等待多少tick再发 /login.")
        .defaultValue(40).min(0).max(200).sliderRange(0, 200).build());

    private final Setting<Integer> useDelay = sgTiming.add(new IntSetting.Builder()
        .name("use-delay").description("发完 /login 后等待多少tick再右键.")
        .defaultValue(20).min(0).max(100).sliderRange(0, 100).build());

    private final Setting<Integer> guiTimeout = sgTiming.add(new IntSetting.Builder()
        .name("gui-timeout").description("等待GUI打开的超时(tick).")
        .defaultValue(100).min(10).max(400).sliderRange(10, 400).build());

    private final Setting<Integer> throwDelay = sgTiming.add(new IntSetting.Builder()
        .name("throw-delay").description("GUI打开后等待多少tick开始丢物品.")
        .defaultValue(10).min(0).max(100).sliderRange(0, 100).build());

    // Throw
    private final Setting<ThrowMode> throwMode = sgThrow.add(new EnumSetting.Builder<ThrowMode>()
        .name("throw-mode").description("Stack = 整组丢出, Single = 每次丢1个.")
        .defaultValue(ThrowMode.Stack).build());

    private final Setting<Integer> slotsPerTick = sgThrow.add(new IntSetting.Builder()
        .name("slots-per-tick").description("每tick丢几个槽位(发包限速).")
        .defaultValue(1).min(1).max(9).sliderRange(1, 9).build());

    private final Setting<Boolean> skipPlayerInv = sgThrow.add(new BoolSetting.Builder()
        .name("skip-player-inventory").description("跳过GUI下方的玩家背包槽位.")
        .defaultValue(true).build());

    private final Setting<Boolean> closeScreen = sgThrow.add(new BoolSetting.Builder()
        .name("close-screen").description("丢完后关闭GUI.")
        .defaultValue(false).build());

    // State
    private State state = State.Idle;
    private boolean armed;
    private int ticks;
    private int throwTicks;

    public InkAutoLogin() {
        super(VacuaAddon.CATEGORY, "ink-auto-login", "ink自动登录 - 进服自动 /login、右键下界之星并丢弃GUI物品.");
    }

    @Override
    public void onActivate() {
        reset();
        if (mc.player != null && mc.world != null && matchesServer()) start();
        else armed = true;
    }

    @Override
    public void onDeactivate() {
        reset();
        armed = false;
    }

    private void reset() {
        state = State.Idle;
        ticks = 0;
        throwTicks = 0;
    }

    private void start() {
        armed = false;
        state = State.WaitLogin;
        ticks = 0;
        if (notify.get()) info("ink自动登录: 开始流程.");
    }

    private boolean matchesServer() {
        String filter = serverFilter.get().trim();
        if (filter.isEmpty()) return true;
        var entry = mc.getCurrentServerEntry();
        if (entry == null) return false;
        return entry.address.toLowerCase().contains(filter.toLowerCase());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            reset();
            armed = true;
            return;
        }

        switch (state) {
            case Idle -> {
                if (armed && autoTrigger.get() && matchesServer()) start();
            }
            case WaitLogin -> {
                if (++ticks < loginDelay.get()) return;
                sendLogin();
                state = State.WaitUse;
                ticks = 0;
            }
            case WaitUse -> {
                if (++ticks < useDelay.get()) return;
                state = useStar() ? State.WaitGui : State.Done;
                ticks = 0;
            }
            case WaitGui -> {
                if (isGuiOpen()) {
                    state = State.WaitThrow;
                    ticks = 0;
                } else if (++ticks >= guiTimeout.get()) {
                    warning("ink自动登录: 未检测到GUI打开, 流程结束.");
                    state = State.Done;
                }
            }
            case WaitThrow -> {
                if (++ticks < throwDelay.get()) return;
                state = State.Throwing;
                throwTicks = 0;
            }
            case Throwing -> throwItems();
            case Done -> {
                if (autoDisable.get()) toggle();
                else state = State.Idle;
            }
        }
    }

    private void sendLogin() {
        String pw = password.get();
        if (pw == null || pw.isEmpty()) {
            warning("ink自动登录: 未设置 password, 跳过 /login.");
            return;
        }
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendChatCommand("login " + pw);
        if (notify.get()) info("ink自动登录: 已发送 /login.");
    }

    private boolean useStar() {
        if (mc.interactionManager == null || mc.getNetworkHandler() == null) return false;

        PlayerInventory inv = mc.player.getInventory();
        int slot = hotbarSlot.get();
        ItemStack stack = inv.getStack(slot);

        if (checkItem.get() && !stack.isOf(Items.NETHER_STAR)) {
            warning("ink自动登录: 槽位 " + slot + " 不是下界之星(当前: "
                + (stack.isEmpty() ? "空" : stack.getName().getString()) + "), 流程中止.");
            return false;
        }

        int previous = inv.selectedSlot;
        inv.selectedSlot = slot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        inv.selectedSlot = previous;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(previous));

        if (notify.get()) info("ink自动登录: 已右键下界之星.");
        return true;
    }

    private boolean isGuiOpen() {
        return mc.currentScreen instanceof HandledScreen<?>
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0;
    }

    private void throwItems() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (mc.interactionManager == null || handler == null || handler.syncId == 0 || !isGuiOpen()) {
            state = State.Done;
            return;
        }
        if (++throwTicks > 200) {
            warning("ink自动登录: 丢物品超时, 流程结束.");
            state = State.Done;
            return;
        }

        int budget = slotsPerTick.get();
        int action = throwMode.get() == ThrowMode.Stack ? 1 : 0;

        for (int i = 0; i < handler.slots.size() && budget > 0; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack()) continue;
            if (skipPlayerInv.get() && slot.inventory == mc.player.getInventory()) continue;

            mc.interactionManager.clickSlot(handler.syncId, i, action, SlotActionType.THROW, mc.player);
            budget--;
        }

        if (budget > 0) {
            if (closeScreen.get()) mc.player.closeHandledScreen();
            if (notify.get()) info("ink自动登录: 物品已丢完, 登录流程完成.");
            state = State.Done;
        }
    }
}
