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
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityPose;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class InkBurrow extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPhase = settings.createGroup("Phase");
    private final SettingGroup sgFly = settings.createGroup("Fly");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<PhaseMode> phaseMode = sgGeneral.add(new EnumSetting.Builder<PhaseMode>()
        .name("phase-mode").description("卡入模式.")
        .defaultValue(PhaseMode.CrouchGlitch).build());

    private final Setting<Boolean> autoEnableFly = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-fly").description("卡入后自动开启飞行.")
        .defaultValue(true).build());

    private final Setting<Boolean> clipUp = sgGeneral.add(new BoolSetting.Builder()
        .name("clip-up").description("卡入后向上穿墙.")
        .defaultValue(true).build());

    private final Setting<Integer> clipDepth = sgGeneral.add(new IntSetting.Builder()
        .name("clip-depth").description("向上穿墙深度(方块数).")
        .defaultValue(3).range(1, 10)
        .visible(clipUp::get).build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("穿出方块后自动关闭.")
        .defaultValue(true).build());

    // Phase
    private final Setting<Integer> crouchTicks = sgPhase.add(new IntSetting.Builder()
        .name("crouch-ticks").description("蹲下持续tick数.")
        .defaultValue(3).range(1, 10)
        .visible(() -> phaseMode.get() == PhaseMode.CrouchGlitch).build());

    private final Setting<Boolean> sendJumpPackets = sgPhase.add(new BoolSetting.Builder()
        .name("send-jump-packets").description("发送假跳跃包desync服务器.")
        .defaultValue(true).build());

    private final Setting<Boolean> sendPositionPackets = sgPhase.add(new BoolSetting.Builder()
        .name("send-position-packets").description("发送位置包desync碰撞检测.")
        .defaultValue(true).build());

    private final Setting<Boolean> onlyUnderBlock = sgPhase.add(new BoolSetting.Builder()
        .name("only-under-solid").description("仅在头顶有实心方块时激活.")
        .defaultValue(true).build());

    private final Setting<Boolean> retryOnFail = sgPhase.add(new BoolSetting.Builder()
        .name("retry-on-fail").description("失败后自动重试.")
        .defaultValue(true).build());

    private final Setting<Integer> maxRetries = sgPhase.add(new IntSetting.Builder()
        .name("max-retries").description("最大重试次数.")
        .defaultValue(3).range(1, 10)
        .visible(retryOnFail::get).build());

    // Fly
    private final Setting<Double> flySpeed = sgFly.add(new DoubleSetting.Builder()
        .name("fly-speed").description("穿墙飞行速度.")
        .defaultValue(1.0).range(0.1, 5.0).build());

    private final Setting<Double> flyVerticalSpeed = sgFly.add(new DoubleSetting.Builder()
        .name("fly-vertical-speed").description("垂直飞行速度.")
        .defaultValue(0.5).range(0.1, 3.0).build());

    private final Setting<Boolean> flyAntiKick = sgFly.add(new BoolSetting.Builder()
        .name("fly-anti-kick").description("飞行防踢.")
        .defaultValue(true).build());

    private final Setting<Boolean> flyDesync = sgFly.add(new BoolSetting.Builder()
        .name("fly-desync").description("飞行时发送desync包.")
        .defaultValue(true).build());

    private final Setting<Integer> flyDesyncInterval = sgFly.add(new IntSetting.Builder()
        .name("fly-desync-interval").description("desync包间隔(tick).")
        .defaultValue(20).range(5, 100)
        .visible(flyDesync::get).build());

    // Render
    private final Setting<Boolean> renderClip = sgRender.add(new BoolSetting.Builder()
        .name("render-clip").description("渲染卡入方块.")
        .defaultValue(true).build());

    private final Setting<SettingColor> clipColor = sgRender.add(new ColorSetting.Builder()
        .name("clip-color").description("卡入方块颜色.")
        .defaultValue(new SettingColor(0, 255, 255, 80)).build());

    // State
    private enum PhaseState { Idle, Crouching, Uncrouching, Clipping, Phasing, Flying }
    private PhaseState state = PhaseState.Idle;
    private int stateTimer;
    private boolean inBlock;
    private int flyTickCounter;
    private int retryCount;
    private BlockPos clipTarget;
    private Vec3d clipStartPos;
    private int antiKickPattern;

    public enum PhaseMode { CrouchGlitch, PacketClip, Auto }

    public InkBurrow() {
        super(VacuaAddon.CATEGORY, "ink-burrow", "碰撞箱glitch卡入方块 + 穿墙飞行 (绕过ink服务器禁burrow)");
    }

    @Override
    public void onActivate() {
        state = PhaseState.Idle;
        stateTimer = 0;
        inBlock = false;
        flyTickCounter = 0;
        retryCount = 0;
        clipTarget = null;
        clipStartPos = null;
        antiKickPattern = 0;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().allowFlying = false;
            mc.player.setSneaking(false);
            mc.options.sneakKey.setPressed(false);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case Idle -> handleIdle();
            case Crouching -> handleCrouching();
            case Uncrouching -> handleUncrouching();
            case Clipping -> handleClipping();
            case Phasing -> handlePhasing();
            case Flying -> handleFlying();
        }
    }

    private void handleIdle() {
        if (isInsideBlock()) {
            // Already inside a block, go straight to flying
            inBlock = true;
            if (autoEnableFly.get()) {
                state = PhaseState.Flying;
            }
            return;
        }

        if (!mc.player.isOnGround()) return;
        if (onlyUnderBlock.get() && !hasSolidAbove()) return;

        switch (phaseMode.get()) {
            case CrouchGlitch -> startCrouchGlitch();
            case PacketClip -> startPacketClip();
            case Auto -> {
                if (mc.player.isSneaking()) startCrouchGlitch();
                else startPacketClip();
            }
        }
    }

    private void handleCrouching() {
        mc.player.setSneaking(true);
        mc.options.sneakKey.setPressed(true);

        if (--stateTimer <= 0) {
            // Release crouch → collision box expands → clip happens
            state = PhaseState.Uncrouching;
            stateTimer = 2; // Wait for collision box to expand
        }
    }

    private void handleUncrouching() {
        mc.player.setSneaking(false);
        mc.options.sneakKey.setPressed(false);

        if (--stateTimer <= 0) {
            // Now clip up
            if (sendJumpPackets.get()) sendFakeJumpPackets();
            if (sendPositionPackets.get()) sendDesyncPackets();

            clipPlayerUp();
            state = PhaseState.Clipping;
            stateTimer = 3;
        }
    }

    private void handleClipping() {
        if (--stateTimer <= 0) {
            state = PhaseState.Phasing;
            stateTimer = 5;
        }
    }

    private void handlePhasing() {
        if (--stateTimer <= 0) {
            if (isInsideBlock()) {
                inBlock = true;
                state = autoEnableFly.get() ? PhaseState.Flying : PhaseState.Idle;
            } else if (retryOnFail.get() && retryCount < maxRetries.get()) {
                retryCount++;
                state = PhaseState.Idle;
            } else {
                state = PhaseState.Idle;
            }
        }
    }

    private void handleFlying() {
        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().allowFlying = false;
        mc.player.setVelocity(Vec3d.ZERO);

        // Exit detection
        if (autoDisable.get() && !isInsideBlock() && flyTickCounter > 15) {
            state = PhaseState.Idle;
            inBlock = false;
            flyTickCounter = 0;
            retryCount = 0;
            return;
        }

        flyTickCounter++;

        // Movement
        Vec3d forward = Vec3d.fromPolar(0, mc.player.getYaw()).normalize();
        Vec3d strafe = Vec3d.fromPolar(0, mc.player.getYaw() - 90).normalize();
        double speed = flySpeed.get();

        double motionX = 0, motionZ = 0, motionY = 0;

        if (mc.options.forwardKey.isPressed()) { motionX += forward.x * speed; motionZ += forward.z * speed; }
        if (mc.options.backKey.isPressed()) { motionX -= forward.x * speed; motionZ -= forward.z * speed; }
        if (mc.options.leftKey.isPressed()) { motionX += strafe.x * speed; motionZ += strafe.z * speed; }
        if (mc.options.rightKey.isPressed()) { motionX -= strafe.x * speed; motionZ -= strafe.z * speed; }
        if (mc.options.jumpKey.isPressed()) motionY = flyVerticalSpeed.get();
        if (mc.options.sneakKey.isPressed()) motionY = -flyVerticalSpeed.get();

        mc.player.setVelocity(motionX, motionY, motionZ);

        // Anti-kick: varied pattern to avoid detection
        if (flyAntiKick.get()) {
            antiKickPattern++;
            double jitter = Math.sin(antiKickPattern * 0.3) * 0.03 + Math.cos(antiKickPattern * 0.7) * 0.02;
            mc.player.setVelocity(mc.player.getVelocity().add(0, jitter, 0));
        }

        // Desync
        if (flyDesync.get() && flyTickCounter % flyDesyncInterval.get() == 0) {
            mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround()));
        }
    }

    private void startCrouchGlitch() {
        clipStartPos = mc.player.getPos();
        state = PhaseState.Crouching;
        stateTimer = crouchTicks.get();
    }

    private void startPacketClip() {
        if (!mc.player.isOnGround()) return;
        clipStartPos = mc.player.getPos();

        if (sendJumpPackets.get()) sendFakeJumpPackets();
        if (sendPositionPackets.get()) sendDesyncPackets();

        clipPlayerUp();
        state = PhaseState.Clipping;
        stateTimer = 3;
    }

    private void clipPlayerUp() {
        if (mc.player == null) return;
        int blocks = clipUp.get() ? clipDepth.get() : 1;
        double totalClip = blocks * 0.42;

        mc.player.setPosition(
            mc.player.getX(),
            mc.player.getY() + totalClip,
            mc.player.getZ()
        );

        clipTarget = mc.player.getBlockPos();
    }

    private void sendFakeJumpPackets() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        // Standard MC jump motion steps
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.4199999868869781, z, true));
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.7531999805212017, z, false));
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.9999957640154541, z, false));
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 1.1661092609382138, z, false));
    }

    private void sendDesyncPackets() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.5, z, false));
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 1.0, z, false));
    }

    private boolean hasSolidAbove() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos above = mc.player.getBlockPos().up();
        return mc.world.getBlockState(above).isFullCube(mc.world, above);
    }

    private boolean isInsideBlock() {
        if (mc.player == null || mc.world == null) return false;
        Box box = mc.player.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.floor(box.maxX);
        int maxY = (int) Math.floor(box.maxY);
        int maxZ = (int) Math.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isFullCube(mc.world, pos) && mc.player.getBoundingBox().intersects(new Box(pos))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderClip.get() || clipTarget == null) return;

        Color fill = new Color(clipColor.get());
        Color outline = new Color(clipColor.get().r, clipColor.get().g, clipColor.get().b, 200);
        event.renderer.box(new Box(clipTarget), fill, outline, ShapeMode.Both, 0);
    }
}
