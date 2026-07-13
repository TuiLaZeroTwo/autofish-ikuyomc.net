package tlz.autofish.addon.modules;

import tlz.autofish.addon.TLZAutoFish;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.projectile.FishingBobberEntity;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.lwjgl.BufferUtils;
import java.awt.Color;
import static meteordevelopment.meteorclient.utils.Utils.rightClick;

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
        .description("How many pixels inside the green zone the cursor must be before clicking.")
        .defaultValue(0)
        .range(0, 30)
        .sliderMax(20)
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

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints state changes and detection info to chat.")
        .defaultValue(false)
        .build()
    );

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
    private final float[] hsbBuf = new float[3];

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
    }

    private void log(String msg) {
        if (debug.get()) info(msg);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (autoSwitch.get()) {
            int bestRodSlot = findBestRod();
            if (bestRodSlot != -1 && mc.player.getInventory().getSelectedSlot() != bestRodSlot) {
                InvUtils.swap(bestRodSlot, false);
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

        if (inMinigame) {
            tickMinigame();
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

    private void tickMinigame() {
        minigameTimer++;

        if (mc.player.fishHook == null) {
            log("Bobber lost during minigame");
            inMinigame = false;
            return;
        }

        if (minigameTimer > MINIGAME_TIMEOUT * 20) {
            log("Minigame timeout");
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

        log("Casting rod");
        useRod();
    }

    private void tryCatch() {
        if (mc.player.fishHook == null) return;

        if (mc.player.fishHook.getHookedEntity() != null) {
            log("Entity hooked, reeling");
            useRod();
            return;
        }

        waitingTicks++;

        if (!wasHooked) {
            if (!isStateBobbing()) return;

            if (hasCaughtFish()) {
                log("Fish bite detected!");
                catchDelayLeft = randomizeDelay(catchDelay.get(), catchDelayVariance.get());
                wasHooked = true;
                waitingTicks = 0;
            } else if (waitingTicks > 600) {
                log("Waiting timeout (30s), reeling anyway");
                useRod();
            }
            return;
        }

        if (catchDelayLeft > 0) {
            catchDelayLeft -= TickRate.INSTANCE.getTickRate() / 20.0;
            return;
        }

        if (autoMinigame.get()) {
            log("Catch delay done, reeling to trigger minigame");
            rightClick();
            inMinigame = true;
            minigameTimer = 0;
            barFound = false;
            wasHooked = false;
            catchDelayLeft = 0.0;
        } else {
            log("Catch delay done, reeling");
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

    private boolean isStateBobbing() {
        try {
            Field f = FishingBobberEntity.class.getDeclaredField("field_7175");
            f.setAccessible(true);
            Object val = f.get(mc.player.fishHook);
            int ord = ((Enum<?>) val).ordinal();
            if (debug.get()) info("Bobber state ordinal=" + ord);
            return ord != 0;
        } catch (Exception e) {
            if (debug.get()) info("isStateBobbing exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return true;
        }
    }

    private boolean hasCaughtFish() {
        if (mc.player.fishHook == null) return false;
        try {
            Field f = FishingBobberEntity.class.getDeclaredField("field_23232");
            f.setAccessible(true);
            boolean val = f.getBoolean(mc.player.fishHook);
            if (debug.get()) info("hasCaughtFish = " + val);
            return val;
        } catch (Exception e) {
            if (debug.get()) info("hasCaughtFish exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private int findBestRod() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() != Items.FISHING_ROD) continue;
            if (antiBreak.get() && stack.getDamage() == stack.getMaxDamage() - 1) continue;
            return i;
        }
        return -1;
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
            log("Green zone scan: none found");
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
        log("Bar found via green zone at fbY=" + greenFbY + " zone=[" + bestGStart + "," + bestGEnd + "]");
    }

    private void scanCursor() {
        if (!barFound) return;
        if (barFbW <= 0) return;

        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();

        int arrowScanH = 10;
        int topScanY = Math.max(0, barFbY - arrowScanH);
        int botScanY = Math.max(0, barFbY + barFbH);

        int scanW = barFbW + 20;
        scanW = Math.min(scanW, fbW);
        int scanX = Math.max(0, (fbW - scanW) / 2);

        if (topScanY + arrowScanH > fbH) return;
        if (botScanY + arrowScanH > fbH) return;

        ByteBuffer topBuf = BufferUtils.createByteBuffer(scanW * arrowScanH * 4);
        ByteBuffer botBuf = BufferUtils.createByteBuffer(scanW * arrowScanH * 4);

        GlStateManager._readPixels(scanX, topScanY, scanW, arrowScanH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(topBuf));
        GlStateManager._readPixels(scanX, botScanY, scanW, arrowScanH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(botBuf));

        int brightnessMin = whiteBrightnessMin.get();
        int cursorTop = findCursorX(topBuf, scanW, arrowScanH, brightnessMin);
        int cursorBot = findCursorX(botBuf, scanW, arrowScanH, brightnessMin);

        int newCursorX = -1;
        String cursorLoc = "";
        if (cursorTop >= 0) {
            newCursorX = scanX + cursorTop;
            cursorLoc = "above";
        } else if (cursorBot >= 0) {
            newCursorX = scanX + cursorBot;
            cursorLoc = "below";
        }

        if (newCursorX < 0) {
            log("Cursor scan: not found (top=" + cursorTop + " bot=" + cursorBot + ")");
            return;
        }
        cursorFbX = newCursorX;
        int tol = clickTolerance.get();
        log("Cursor " + cursorLoc + " bar at fbX=" + cursorFbX + " greenZone=[" + greenStartFb + "," + greenEndFb + "] inZone=" + (cursorFbX >= greenStartFb + tol && cursorFbX <= greenEndFb - tol));
        if (cursorFbX >= greenStartFb + tol && cursorFbX <= greenEndFb - tol) {
            log("Cursor in green zone, clicking!");
            useRod();
        }
    }

    private int findCursorX(ByteBuffer buf, int w, int h, int brightnessMin) {
        float bNorm = brightnessMin / 255.0f;
        for (int col = 0; col < w; col++) {
            int whiteCount = 0;
            for (int row = 0; row < h; row++) {
                int idx = (row * w + col) * 4;
                int r = buf.get(idx) & 0xFF;
                int g = buf.get(idx + 1) & 0xFF;
                int b = buf.get(idx + 2) & 0xFF;
                Color.RGBtoHSB(r, g, b, hsbBuf);
                if (hsbBuf[1] < 0.2f && hsbBuf[2] > bNorm) whiteCount++;
            }
            if (whiteCount >= 2) {
                int startCol = col;
                while (startCol > 0) {
                    int cnt = 0;
                    for (int r2 = 0; r2 < h; r2++) {
                        int i = (r2 * w + (startCol - 1)) * 4;
                        int rv = buf.get(i) & 0xFF;
                        int gv = buf.get(i + 1) & 0xFF;
                        int bv = buf.get(i + 2) & 0xFF;
                        Color.RGBtoHSB(rv, gv, bv, hsbBuf);
                        if (hsbBuf[1] < 0.2f && hsbBuf[2] > bNorm) cnt++;
                    }
                    if (cnt >= 1) startCol--;
                    else break;
                }
                return startCol;
            }
        }
        return -1;
    }

}
