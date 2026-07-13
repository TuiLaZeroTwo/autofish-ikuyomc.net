package tlz.autofish.addon.modules;

import tlz.autofish.addon.TLZAutoFish;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.Entity;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.lwjgl.BufferUtils;
import java.awt.Color;
import static meteordevelopment.meteorclient.utils.Utils.rightClick;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

public class AutoFish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switch to a fishing rod.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Avoid using rods that would break if they were cast.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoCast = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-cast")
        .description("Automatically cast the fishing rod.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoCatch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-catch")
        .description("Automatically reel in when a fish bites.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoMinigame = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-minigame")
        .description("Automatically plays the fishing minigame.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> castDelay = sgGeneral.add(new IntSetting.Builder()
        .name("cast-delay")
        .description("How long to wait between recasts if the bobber fails to land in water.")
        .defaultValue(14)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> castDelayVariance = sgGeneral.add(new IntSetting.Builder()
        .name("cast-delay-variance")
        .description("Maximum amount of randomness added to cast delay.")
        .defaultValue(0)
        .min(0)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> catchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("catch-delay")
        .description("How long to wait after hooking a fish to reel it in.")
        .defaultValue(1)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> catchDelayVariance = sgGeneral.add(new IntSetting.Builder()
        .name("catch-delay-variance")
        .description("Maximum amount of randomness added to catch delay.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private static final int MINIGAME_TIMEOUT = 30;

    private final Setting<Integer> clickTolerance = sgGeneral.add(new IntSetting.Builder()
        .name("click-tolerance")
        .description("Pixels inside the green zone (positive) or outside (negative) to click.")
        .defaultValue(-10)
        .sliderRange(-50, 50)
        .build()
    );

    private final Setting<Integer> clickCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("click-cooldown")
        .description("Ticks to wait between minigame clicks.")
        .defaultValue(4)
        .range(1, 20)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> barScreenWidth = sgGeneral.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Width of the fishing bar overlay in screen pixels.")
        .defaultValue(781)
        .range(200, 2000)
        .sliderMax(1500)
        .build()
    );

    private final Setting<Integer> barScreenHeight = sgGeneral.add(new IntSetting.Builder()
        .name("bar-height")
        .description("Height of the fishing bar overlay in screen pixels.")
        .defaultValue(106)
        .range(30, 300)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> greenHueTolerance = sgGeneral.add(new IntSetting.Builder()
        .name("green-hue-tolerance")
        .description("Hue tolerance in degrees (± from 120°) to detect green.")
        .defaultValue(30)
        .range(5, 90)
        .sliderMax(90)
        .build()
    );

    private final Setting<Integer> whiteBrightnessMin = sgGeneral.add(new IntSetting.Builder()
        .name("white-brightness-min")
        .description("Minimum HSB brightness (0-255) for cursor detection.")
        .defaultValue(160)
        .range(50, 255)
        .sliderMax(255)
        .build()
    );

    private final Setting<Boolean> pauseOnPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-player")
        .description("Pauses autofish if another player is nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOnPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-on-player")
        .description("Turns off autofish module completely if a player is nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerDistance = sgGeneral.add(new IntSetting.Builder()
        .name("player-distance")
        .description("Distance to detect players.")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .visible(() -> pauseOnPlayer.get() || toggleOnPlayer.get())
        .build()
    );

    private final Setting<Boolean> autoVault = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-vault")
        .description("Automatically uses /kho to store items when inventory is full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> vaultClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("vault-click-delay")
        .description("Ticks to wait between storing each item (for anti-cheat bypass).")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> smartDebug = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-debug")
        .description("Prints detailed minigame history if catching fails.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRepairRods = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-repair-rods")
        .description("Automatically combines nearly broken fishing rods in your 2x2 crafting grid.")
        .defaultValue(true)
        .build()
    );

    private final float[] hsbBuf = new float[3];
    private boolean barFound;
    private int barFbX, barFbY, barFbW, barFbH;
    private int greenStartFb, greenEndFb;
    private int cursorFbX;
    private int recheckDelay;
    private double castDelayLeft;
    private double catchDelayLeft;
    private boolean wasHooked;
    private boolean inMinigame;
    private int minigameTimer;
    private int waitingTicks;
    private int clickCooldown;
    private boolean cursorFoundThisTick;
    private boolean hasSeenCursor;
    private int cursorLostTicks;
    private ByteBuffer barBuf;
    private ByteBuffer prevBarBuf;
    private boolean[] greenBarCols = new boolean[0];
    private int prevCursorX = -1;
    private int cursorVelocity = 0;
    
    private final List<String> debugHistory = new ArrayList<>();
    
    private int autoVaultState = 0;
    private int vaultWaitTicks = 0;
    private int vaultStoreTicks = 0;
    private int autoVaultCooldown = 0;
    private final java.util.Set<Integer> clickedSlots = new java.util.HashSet<>();

    private int autoRepairState = 0;
    private int repairWaitTicks = 0;
    private int repairSlot1 = -1;
    private int repairSlot2 = -1;

    public AutoFish() {
        super(TLZAutoFish.CATEGORY, "auto-fisch", "The ultimate addon for fishing on IkuyoMC.net.");
        try {
            Field f = Module.class.getField("title");
            f.setAccessible(true);
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(f, f.getModifiers() & ~Modifier.FINAL);
            f.set(this, "Auto-Fisch");
        } catch (Exception e) {
            // Fallback: title will be "Auto Fisch" from nameToTitle
        }
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        barFound = false;
        greenStartFb = greenEndFb = -1;
        cursorFbX = -1;
        recheckDelay = 0;
        castDelayLeft = 0.0;
        catchDelayLeft = 0.0;
        wasHooked = false;
        inMinigame = false;
        minigameTimer = 0;
        waitingTicks = 0;
        clickCooldown = 0;
        cursorFoundThisTick = false;
        hasSeenCursor = false;
        cursorLostTicks = 0;
        prevBarBuf = null;
        prevCursorX = -1;
        cursorVelocity = 0;
        debugHistory.clear();
        autoVaultState = 0;
        autoVaultCooldown = 0;
        clickedSlots.clear();
        autoRepairState = 0;
        repairWaitTicks = 0;
        repairSlot1 = -1;
        repairSlot2 = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (pauseOnPlayer.get() || toggleOnPlayer.get()) {
            boolean playerFound = false;
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof PlayerEntity && entity != mc.player) {
                    if (mc.player.distanceTo(entity) <= playerDistance.get()) {
                        playerFound = true;
                        break;
                    }
                }
            }
            if (playerFound) {
                if (toggleOnPlayer.get()) {
                    toggle();
                    return;
                }
                if (pauseOnPlayer.get()) {
                    return;
                }
            }
        }

        if (autoVaultCooldown > 0) {
            autoVaultCooldown--;
        }

        if (autoVaultState > 0) {
            tickAutoVault();
            return;
        }

        if (autoRepairState > 0) {
            tickAutoRepair();
            return;
        }

        if (autoRepairRods.get() && autoRepairState == 0 && autoVaultState == 0 && !inMinigame && mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
            int slot1 = -1, slot2 = -1;
            for (int i = 9; i <= 44; i++) {
                ItemStack stack = mc.player.playerScreenHandler.slots.get(i).getStack();
                if (stack.getItem() == Items.FISHING_ROD && stack.getDamage() >= stack.getMaxDamage() - 2) {
                    if (slot1 == -1) slot1 = i;
                    else if (slot2 == -1) {
                        slot2 = i;
                        break;
                    }
                }
            }
            if (slot1 != -1 && slot2 != -1) {
                boolean gridEmpty = true;
                for (int i = 1; i <= 4; i++) {
                    if (!mc.player.playerScreenHandler.slots.get(i).getStack().isEmpty()) {
                        gridEmpty = false;
                        break;
                    }
                }
                if (gridEmpty) {
                    repairSlot1 = slot1;
                    repairSlot2 = slot2;
                    autoRepairState = 1;
                    repairWaitTicks = vaultClickDelay.get();
                    return;
                }
            }
        }

        if (autoVault.get() && autoVaultCooldown <= 0 && mc.player.getInventory().getEmptySlot() == -1 && !inMinigame) {
            autoVaultState = 1;
            vaultWaitTicks = 40;
            clickedSlots.clear();
            ChatUtils.sendPlayerMsg("/kho");
            return;
        }

        if (autoSwitch.get()) {
            int bestRodSlot = findBestRod();
            if (bestRodSlot != -1) {
                swapRodToHotbar(bestRodSlot);
            }
        }

        if (!isHoldingRod()) {
            if (wasHooked || inMinigame || castDelayLeft > 0 || catchDelayLeft > 0) reset();
            return;
        }

        if (recheckDelay > 0) {
            recheckDelay--;
            return;
        }

        if (clickCooldown > 0) {
            clickCooldown--;
        }

        minigameTimer++;

        if (inMinigame) {
            tickMinigame();
            cursorFoundThisTick = false;
            return;
        }

        tryCast();
        tryCatch();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!inMinigame) return;
        if (!autoMinigame.get()) return;

        if (!barFound) {
            detectBar();
        } else {
            scanCursor();
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!autoMinigame.get() || !smartDebug.get()) return;
        String name = event.packet.getClass().getSimpleName();
        if (name.contains("Title") || name.contains("Subtitle") || name.contains("Overlay")) {
            try {
                for (Field f : event.packet.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(event.packet);
                    if (val != null) {
                        String s = val.toString().toLowerCase();
                        if (s.contains("thất bại") || s.contains("xui quá") || s.contains("got away") || s.contains("failed")) {
                            printVisualDebug();
                            debugHistory.clear();
                        } else if (s.contains("thành công") || s.contains("tốt lắm") || s.contains("caught")) {
                            debugHistory.clear();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void printVisualDebug() {
        if (debugHistory.isEmpty()) return;
        info("--- 🎣 AUTOFISH FAILED: SMART DEBUG LOG 🎣 ---");
        for (String line : debugHistory) {
            info(line);
        }
        info("---------------------------------------------");
        debugHistory.clear();
    }

    private void tickAutoVault() {
        vaultWaitTicks--;
        
        if (vaultWaitTicks <= 0 && autoVaultState != 3) {
            autoVaultState = 0;
            autoVaultCooldown = 600;
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            return;
        }

        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen) {
            String title = screen.getTitle().getString();
            
            if (autoVaultState == 1) {
                if (title.contains("Kho Đồ Của Tôi") || title.contains("Vault") || title.contains("Chest")) {
                    net.minecraft.screen.ScreenHandler handler = screen.getScreenHandler();
                    for (int i = 0; i < handler.slots.size(); i++) {
                        ItemStack stack = handler.slots.get(i).getStack();
                        if (stack.getItem() == Items.BARREL || stack.getItem() == Items.CHEST) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
                            autoVaultState = 2;
                            vaultWaitTicks = 40;
                            return;
                        }
                    }
                }
            } else if (autoVaultState == 2) {
                if (title.contains("Kho Đồ #") || title.contains("Kho ") || title.contains("Vault")) {
                    autoVaultState = 3;
                    vaultStoreTicks = vaultClickDelay.get();
                    clickedSlots.clear();
                }
            } else if (autoVaultState == 3) {
                if (vaultStoreTicks > 0) {
                    vaultStoreTicks--;
                    return;
                }
                
                net.minecraft.screen.ScreenHandler handler = screen.getScreenHandler();
                boolean foundItem = false;
                
                for (int i = 0; i < handler.slots.size(); i++) {
                    if (clickedSlots.contains(i)) continue;
                    net.minecraft.screen.slot.Slot slot = handler.slots.get(i);
                    if (slot.inventory == mc.player.getInventory()) {
                        ItemStack stack = slot.getStack();
                        if (!stack.isEmpty() && stack.getItem() != Items.FISHING_ROD) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                            vaultStoreTicks = vaultClickDelay.get();
                            clickedSlots.add(i);
                            foundItem = true;
                            break;
                        }
                    }
                }
                
                if (!foundItem) {
                    autoVaultState = 0;
                    autoVaultCooldown = 600;
                    mc.player.closeHandledScreen();
                }
            }
        }
    }

    private void tickAutoRepair() {
        if (repairWaitTicks > 0) {
            repairWaitTicks--;
            return;
        }

        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            autoRepairState = 0;
            return;
        }

        if (autoRepairState == 1) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, repairSlot1, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
            autoRepairState = 2;
            repairWaitTicks = vaultClickDelay.get();
        } else if (autoRepairState == 2) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 1, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
            autoRepairState = 3;
            repairWaitTicks = vaultClickDelay.get();
        } else if (autoRepairState == 3) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, repairSlot2, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
            autoRepairState = 4;
            repairWaitTicks = vaultClickDelay.get();
        } else if (autoRepairState == 4) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 2, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
            autoRepairState = 5;
            repairWaitTicks = vaultClickDelay.get();
        } else if (autoRepairState == 5) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 0, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
            autoRepairState = 6;
            repairWaitTicks = vaultClickDelay.get();
        } else if (autoRepairState == 6) {
            autoRepairState = 0;
        }
    }

    private void tickMinigame() {
        if (mc.player.fishHook == null) {
            inMinigame = false;
            return;
        }

        if (cursorFoundThisTick) {
            hasSeenCursor = true;
            cursorLostTicks = 0;
        } else if (hasSeenCursor) {
            cursorLostTicks++;
            if (cursorLostTicks > 20) {
                useRod();
                return;
            }
        }

        if (minigameTimer > MINIGAME_TIMEOUT * 20) {
            useRod();
        }
    }

    private boolean isHoldingRod() {
        if (mc.player == null) return false;
        ItemStack main = mc.player.getMainHandStack();
        ItemStack off = mc.player.getOffHandStack();
        return main.getItem() == Items.FISHING_ROD || off.getItem() == Items.FISHING_ROD;
    }

    private void tryCast() {
        if (mc.player.fishHook != null) return;
        if (!autoCast.get()) return;

        if (castDelayLeft > 0) {
            castDelayLeft -= TickRate.INSTANCE.getTickRate() / 20.0;
            return;
        }

        useRod();
    }

    private void tryCatch() {
        if (mc.player.fishHook == null) return;

        if (mc.player.fishHook.getHookedEntity() != null) {
            useRod();
            return;
        }

        waitingTicks++;

        if (!wasHooked) {
            if (!isStateBobbing()) return;

            if (hasCaughtFish()) {
                catchDelayLeft = randomizeDelay(catchDelay.get(), catchDelayVariance.get());
                wasHooked = true;
                waitingTicks = 0;
            } else if (waitingTicks > 600) {
                useRod();
            }
            return;
        }

        if (catchDelayLeft > 0) {
            catchDelayLeft -= TickRate.INSTANCE.getTickRate() / 20.0;
            return;
        }

        if (autoMinigame.get()) {
            rightClick();
            inMinigame = true;
            minigameTimer = 0;
            barFound = false;
            wasHooked = false;
            catchDelayLeft = 0.0;
            hasSeenCursor = false;
            cursorLostTicks = 0;
            debugHistory.clear();
        } else {
            useRod();
        }
    }

    private void useRod() {
        rightClick();
        wasHooked = false;
        inMinigame = false;
        catchDelayLeft = 0.0;
        castDelayLeft = randomizeDelay(castDelay.get(), castDelayVariance.get());
        barFound = false;
        recheckDelay = 5;
        waitingTicks = 0;
    }

    private void minigameClick() {
        rightClick();
        clickCooldown = clickCooldownTicks.get();
    }

    private boolean isStateBobbing() {
        try {
            Field f = FishingBobberEntity.class.getDeclaredField("field_7175");
            f.setAccessible(true);
            Object val = f.get(mc.player.fishHook);
            int ord = ((Enum<?>) val).ordinal();
            return ord != 0;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean hasCaughtFish() {
        if (mc.player.fishHook == null) return false;
        try {
            Field f = FishingBobberEntity.class.getDeclaredField("field_23232");
            f.setAccessible(true);
            boolean val = f.getBoolean(mc.player.fishHook);
            return val;
        } catch (Exception e) {
            return false;
        }
    }

    private int findBestRod() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() != Items.FISHING_ROD) continue;
            if (antiBreak.get() && stack.getDamage() >= stack.getMaxDamage() - 1) continue;
            return i;
        }
        return -1;
    }

    private void swapRodToHotbar(int bestRodSlot) {
        int currentSlot = 0;
        try {
            java.lang.reflect.Field f = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("field_7545"); // selectedSlot in Yarn
            f.setAccessible(true);
            currentSlot = f.getInt(mc.player.getInventory());
        } catch (Exception e) {
            try {
                java.lang.reflect.Field f = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
                f.setAccessible(true);
                currentSlot = f.getInt(mc.player.getInventory());
            } catch (Exception ex) {}
        }

        if (bestRodSlot < 9) {
            if (currentSlot != bestRodSlot) {
                meteordevelopment.meteorclient.utils.player.InvUtils.swap(bestRodSlot, false);
            }
        } else {
            int networkSlot = -1;
            for (int i = 0; i < mc.player.playerScreenHandler.slots.size(); i++) {
                net.minecraft.screen.slot.Slot slot = mc.player.playerScreenHandler.slots.get(i);
                if (slot.inventory == mc.player.getInventory() && slot.getIndex() == bestRodSlot) {
                    networkSlot = i;
                    break;
                }
            }
            if (networkSlot != -1) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, networkSlot, currentSlot, net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
            }
        }
    }

    private double randomizeDelay(int delay, int variance) {
        if (variance == 0) return delay;
        double scale = Math.sqrt(-2 * Math.log(Utils.random(0.0001, 1.0)));
        double angle = 2 * Math.PI * Utils.random(0.0, 1.0);
        double norm = scale * Math.cos(angle);
        final double MAX_SD = 3.0;
        norm = Math.clamp(norm, -MAX_SD, MAX_SD) / MAX_SD;
        delay += Math.round(norm * variance);
        return Math.max(1, delay);
    }

    private void detectBar() {
        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();
        int scW = mc.getWindow().getScaledWidth();
        int scH = mc.getWindow().getScaledHeight();

        if (scW == 0 || scH == 0) return;

        double sc = (double) fbW / scW;

        int bfbW = (int) (barScreenWidth.get() * sc);
        int bfbH = (int) (barScreenHeight.get() * sc);

        int margin = 10;
        int readW = Math.min(bfbW + margin * 2, fbW);
        int readH = Math.min(bfbH + margin * 2, fbH) / 2;
        int readX = Math.max(0, (fbW - readW) / 2);
        int readY = Math.max(0, (fbH - readH) / 2);
        readW = Math.min(readW, fbW - readX);
        readH = Math.min(readH, fbH - readY);

        if (readW <= 0 || readH <= 0) return;

        ByteBuffer buf = BufferUtils.createByteBuffer(readW * readH * 4);
        GlStateManager._readPixels(readX, readY, readW, readH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(buf));

        int hueTol = greenHueTolerance.get();
        int bestRow = -1;
        int bestGStart = -1, bestGEnd = -1;
        int bestWidth = 0;

        for (int row = 0; row < readH; row++) {
            int gStart = -1, gEnd = -1;
            boolean inGreen = false;

            for (int col = 0; col < readW; col++) {
                int idx = (row * readW + col) * 4;
                int rv = buf.get(idx) & 0xFF;
                int gv = buf.get(idx + 1) & 0xFF;
                int bv = buf.get(idx + 2) & 0xFF;
                Color.RGBtoHSB(rv, gv, bv, hsbBuf);
                float hueDeg = hsbBuf[0] * 360;
                boolean isGreen = hsbBuf[1] > 0.5f && hsbBuf[2] > 0.3f
                    && Math.abs(hueDeg - 129) < hueTol;

                if (isGreen && !inGreen) {
                    gStart = readX + col;
                    inGreen = true;
                } else if (!isGreen && inGreen) {
                    gEnd = readX + col;
                    inGreen = false;
                    break;
                }
            }
            if (inGreen) {
                gEnd = readX + readW;
            }

            if (gStart >= 0) {
                int w = gEnd - gStart;
                if (w > bestWidth) {
                    bestWidth = w;
                    bestRow = row;
                    bestGStart = gStart;
                    bestGEnd = gEnd;
                }
            }
        }

        if (bestRow < 0) {
            return;
        }

        int greenFbY = readY + bestRow;
        barFbX = readX;
        barFbY = Math.max(0, greenFbY - bfbH / 2);
        barFbW = bfbW;
        barFbH = bfbH;
        greenStartFb = bestGStart;
        greenEndFb = bestGEnd;
        barFound = true;
    }

    private void scanCursor() {
        if (barFbY < 0 || barFbW <= 0) return;

        int fbW = mc.getWindow().getFramebufferWidth();
        int fbH = mc.getWindow().getFramebufferHeight();

        int scanW = barFbW + 20;
        scanW = Math.min(scanW, fbW);
        int scanX = Math.max(0, (fbW - scanW) / 2);

        int brightnessMin = whiteBrightnessMin.get();
        int barMidY = barFbY + barFbH / 2;
        int scanY = Math.max(0, barMidY - 20);
        int scanH = 41;

        if (barBuf == null || barBuf.capacity() < scanW * scanH * 4) {
            barBuf = BufferUtils.createByteBuffer(scanW * scanH * 4);
        }
        barBuf.clear();
        GlStateManager._readPixels(scanX, scanY, scanW, scanH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(barBuf));

        int hueTol = greenHueTolerance.get();
        if (greenBarCols.length < scanW) {
            greenBarCols = new boolean[scanW];
        }

        // Motion detection
        boolean hasPrev = (prevBarBuf != null && prevBarBuf.capacity() >= barBuf.capacity());
        int maxDiffCol = -1;
        int maxDiffScore = 0;
        int midR = scanH / 2;

        for (int col = 0; col < scanW; col++) {
            boolean isGreenCol = false;
            int colDiffSum = 0;

            for (int r = 0; r < scanH; r++) {
                int idx = (r * scanW + col) * 4;
                int rv = barBuf.get(idx) & 0xFF;
                int gv = barBuf.get(idx + 1) & 0xFF;
                int bv = barBuf.get(idx + 2) & 0xFF;
                
                // Only check for green in the middle 3 rows to avoid background grass/water
                if (Math.abs(r - midR) <= 1) {
                    Color.RGBtoHSB(rv, gv, bv, hsbBuf);
                    float hueDeg = hsbBuf[0] * 360;
                    if (hsbBuf[1] > 0.5f && hsbBuf[2] > 0.3f && Math.abs(hueDeg - 129) < hueTol) {
                        isGreenCol = true;
                    }
                }

                if (hasPrev) {
                    int r2 = prevBarBuf.get(idx) & 0xFF;
                    int g2 = prevBarBuf.get(idx + 1) & 0xFF;
                    int b2 = prevBarBuf.get(idx + 2) & 0xFF;
                    int diff = Math.abs(rv - r2) + Math.abs(gv - g2) + Math.abs(bv - b2);
                    if (diff > 50) { // Threshold to ignore subtle background animation
                        colDiffSum++;
                    }
                }
            }

            greenBarCols[col] = isGreenCol;

            if (colDiffSum > maxDiffScore) {
                maxDiffScore = colDiffSum;
                maxDiffCol = col;
            }
        }

        if (prevBarBuf == null || prevBarBuf.capacity() < barBuf.capacity()) {
            prevBarBuf = BufferUtils.createByteBuffer(barBuf.capacity());
        }
        prevBarBuf.clear();
        barBuf.clear();
        prevBarBuf.put(barBuf);
        prevBarBuf.flip();
        barBuf.flip();

        int newCursorX = -1;

        if (maxDiffScore >= 3) {
            newCursorX = scanX + maxDiffCol;
        }

        if (newCursorX < 0) {
            return;
        }
        
        if (prevCursorX != -1 && newCursorX != prevCursorX) {
            cursorVelocity = newCursorX - prevCursorX;
        }
        prevCursorX = newCursorX;
        
        cursorFbX = newCursorX;
        cursorFoundThisTick = true;
        
        if (!hasSeenCursor || cursorVelocity == 0) return;

        int tol = clickTolerance.get();
        int sign = cursorVelocity > 0 ? 1 : (cursorVelocity < 0 ? -1 : 0);
        int targetX = cursorFbX + sign * tol;

        boolean targetIsGreen = false;
        int targetCol = targetX - scanX;
        if (targetCol >= 0 && targetCol < scanW) {
            targetIsGreen = greenBarCols[targetCol];
        }

        if (smartDebug.get()) {
            debugHistory.add(String.format("T%d: Bar[%d->%d] CursX=%d Vel=%d TgtX=%d Green=%b", 
                minigameTimer, greenStartFb, greenEndFb, cursorFbX, cursorVelocity, targetX, targetIsGreen));
            if (debugHistory.size() > 200) debugHistory.remove(0);
        }

        if (targetIsGreen && clickCooldown <= 0) {
            minigameClick();
        }
    }
}
