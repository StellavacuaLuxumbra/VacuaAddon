package com.vacua.addon.modules;

import com.vacua.addon.VacuaAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AntiCrash - 防崩溃
 *
 * 安装全局未捕获异常处理器, 吞掉可能导致客户端崩溃的错误并统计/记录.
 */
public class AntiCrash extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgLogging = settings.createGroup("Logging");

    private final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled").description("启用防崩溃保护.")
        .defaultValue(true).build());

    private final Setting<Boolean> catchAll = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-all").description("捕获所有其他异常.")
        .defaultValue(true).visible(enabled::get).build());

    private final Setting<Boolean> catchRuntime = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-runtime").description("捕获运行时异常.")
        .defaultValue(true).visible(enabled::get).build());

    private final Setting<Boolean> catchErrors = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-errors").description("捕获Error(含严重错误).")
        .defaultValue(true).visible(enabled::get).build());

    private final Setting<Boolean> catchThreadDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-thread-death").description("捕获ThreadDeath/中断类错误.")
        .defaultValue(false).visible(enabled::get).build());

    private final Setting<Boolean> logErrors = sgLogging.add(new BoolSetting.Builder()
        .name("log-errors").description("把捕获的错误输出到聊天栏.")
        .defaultValue(false).visible(enabled::get).build());

    private final Setting<Boolean> verboseLogging = sgLogging.add(new BoolSetting.Builder()
        .name("verbose-logging").description("输出完整错误堆栈.")
        .defaultValue(false).visible(() -> enabled.get() && logErrors.get()).build());

    private final Setting<Integer> maxLogRate = sgLogging.add(new IntSetting.Builder()
        .name("max-log-rate").description("每秒最多记录多少条错误.")
        .defaultValue(5).min(1).max(100).sliderRange(1, 20)
        .visible(() -> enabled.get() && logErrors.get()).build());

    private final Setting<Boolean> showStats = sgLogging.add(new BoolSetting.Builder()
        .name("show-stats").description("定期输出统计信息.")
        .defaultValue(true).visible(enabled::get).build());

    private final Setting<Boolean> showDetailedStats = sgLogging.add(new BoolSetting.Builder()
        .name("show-detailed-stats").description("统计时按异常类型细分.")
        .defaultValue(false).visible(() -> enabled.get() && showStats.get()).build());

    private Thread.UncaughtExceptionHandler defaultHandler;
    private final AtomicInteger caughtCount = new AtomicInteger(0);
    private final AtomicInteger exceptionCount = new AtomicInteger(0);
    private final AtomicInteger runtimeExceptionCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> typeCount = new ConcurrentHashMap<>();
    private long lastLogTime;
    private int loggedThisSecond;
    private long lastStatsTime;

    public AntiCrash() {
        super(VacuaAddon.CATEGORY, "anti-crash", "防崩溃 - 全局捕获未处理的错误, 防止客户端崩溃.");
    }

    @Override
    public void onActivate() {
        if (enabled.get()) installHandler();
    }

    @Override
    public void onDeactivate() {
        uninstallHandler();
    }

    private void installHandler() {
        if (defaultHandler != null) return;
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isActive() && enabled.get() && shouldCatch(throwable)) {
                handle(thread, throwable);
            } else if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                throw new RuntimeException(throwable);
            }
        });
    }

    private void uninstallHandler() {
        if (defaultHandler == null) return;
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
        defaultHandler = null;
    }

    private boolean shouldCatch(Throwable throwable) {
        if (throwable instanceof ThreadDeath) return catchThreadDeath.get();
        if (throwable instanceof InterruptedException) return catchThreadDeath.get();
        if (throwable instanceof Error) return catchErrors.get();
        if (throwable instanceof RuntimeException) return catchRuntime.get();
        return catchAll.get();
    }

    private void handle(Thread thread, Throwable throwable) {
        caughtCount.incrementAndGet();
        typeCount.computeIfAbsent(throwable.getClass().getSimpleName(), k -> new AtomicInteger(0)).incrementAndGet();

        if (throwable instanceof Error) errorCount.incrementAndGet();
        else if (throwable instanceof RuntimeException) runtimeExceptionCount.incrementAndGet();
        else exceptionCount.incrementAndGet();

        if (logErrors.get()) rateLimitedLog(thread, throwable);
    }

    private void rateLimitedLog(Thread thread, Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime >= 1000) {
            lastLogTime = now;
            loggedThisSecond = 0;
        }
        if (loggedThisSecond >= maxLogRate.get()) return;
        loggedThisSecond++;

        StringBuilder sb = new StringBuilder()
            .append("捕获 ").append(throwable.getClass().getSimpleName())
            .append(" (线程 ").append(thread.getName()).append(')');
        if (verboseLogging.get()) {
            sb.append(": ").append(throwable.getMessage());
            info(sb.toString());
            StackTraceElement[] stack = throwable.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 5); i++) {
                info("  at " + stack[i]);
            }
        } else {
            info(sb.toString());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!showStats.get() || caughtCount.get() == 0) return;

        long now = System.currentTimeMillis();
        if (now - lastStatsTime < 1000) return;
        lastStatsTime = now;

        if (showDetailedStats.get()) {
            info("=== AntiCrash 统计 ===");
            info("总数: " + caughtCount.get() + " | 普通异常: " + exceptionCount.get()
                + " | 运行时: " + runtimeExceptionCount.get() + " | 错误: " + errorCount.get());
            typeCount.forEach((type, count) -> info("  " + type + ": " + count.get()));
        } else {
            info("AntiCrash 运行中 - 已捕获: " + caughtCount.get());
        }
    }

    private void printStats() {
        info("=== AntiCrash 统计 ===");
        info("总数: " + caughtCount.get() + " | 普通异常: " + exceptionCount.get()
            + " | 运行时: " + runtimeExceptionCount.get() + " | 错误: " + errorCount.get());
        typeCount.forEach((type, count) -> info("  " + type + ": " + count.get()));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WButton showStatsBtn = list.add(theme.button("显示统计")).expandX().widget();
        showStatsBtn.action = this::printStats;

        WButton clearBtn = list.add(theme.button("清除统计")).expandX().widget();
        clearBtn.action = () -> {
            caughtCount.set(0);
            exceptionCount.set(0);
            runtimeExceptionCount.set(0);
            errorCount.set(0);
            typeCount.clear();
            info("AntiCrash 统计已清除.");
        };

        WButton testBtn = list.add(theme.button("测试异常")).expandX().widget();
        testBtn.action = () -> {
            try {
                throw new RuntimeException("AntiCrash 测试异常");
            } catch (Throwable t) {
                if (shouldCatch(t)) handle(Thread.currentThread(), t);
                else throw t;
            }
        };

        return list;
    }
}
