package tlz.autofish.addon.modules;

import tlz.autofish.addon.Autofish;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.FishingHookAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import static meteordevelopment.meteorclient.utils.Utils.rightClick;

public class AutoFish extends Module {
    private enum State { IDLE, CASTING, WAITING, BITE, MINIGAME, REELING }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoCast = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-cast")
        .description("Automatically casts the fishing rod when not in use.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoCatch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-catch")
        .description("Automatically reels in when a fish bites and no minigame triggers.")
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
        .description("Ticks to wait after casting before checking for bobber.")
        .defaultValue(6)
        .range(2, 40)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> biteWaitTicks = sgGeneral.add(new IntSetting.Builder()
        .name("bite-wait-ticks")
        .description("Ticks to wait after a bite before reeling or scanning for minigame bar.")
        .defaultValue(4)
        .range(1, 20)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> minigameTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("minigame-timeout")
        .description("Max seconds to wait before giving up on the minigame.")
        .defaultValue(8)
        .range(2, 20)
        .sliderMax(15)
        .build()
    );

    private final Setting<Integer> clickTolerance = sgGeneral.add(new IntSetting.Builder()
        .name("click-tolerance")
        .description("How many pixels inside the green zone the cursor must be before clicking.")
        .defaultValue(5)
        .range(0, 30)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> scanFrequency = sgGeneral.add(new IntSetting.Builder()
        .name("scan-frequency")
        .description("Screen scans happen every N frames (higher = less CPU).")
        .defaultValue(3)
        .range(1, 10)
        .sliderMax(6)
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

    private final Setting<Integer> greenThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("green-threshold")
        .description("Minimum G-R difference to classify a pixel as green.")
        .defaultValue(35)
        .range(10, 100)
        .sliderMax(80)
        .build()
    );

    private final Setting<Integer> whiteThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("white-threshold")
        .description("Minimum brightness to classify a pixel as the white cursor arrow.")
        .defaultValue(200)
        .range(150, 255)
        .sliderMax(255)
        .build()
    );

    private final Setting<Integer> barColorMinPixels = sgGeneral.add(new IntSetting.Builder()
        .name("bar-color-min")
        .description("Minimum colored pixels in a row to confirm bar presence.")
        .defaultValue(30)
        .range(5, 200)
        .sliderMax(100)
        .build()
    );

    private State state = State.IDLE;
    private int timer;
    private int scanCounter;
    private boolean barFound;
    private int barFbX, barFbY, barFbW, barFbH;
    private int greenStartFb, greenEndFb;
    private int cursorFbX;
    private int recheckDelay;

    public AutoFish() {
        super(Autofish.CATEGORY, "auto-fish", "Auto fish with Stardew-style minigame support for IkuyoMC.");
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
        state = State.IDLE;
        timer = 0;
        scanCounter = 0;
        barFound = false;
        greenStartFb = greenEndFb = -1;
        cursorFbX = -1;
        recheckDelay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (!isHoldingRod()) {
            if (state != State.IDLE) reset();
            return;
        }

        if (recheckDelay > 0) {
            recheckDelay--;
            return;
        }

        switch (state) {
            case IDLE -> tickIdle();
            case CASTING -> tickCasting();
            case WAITING -> tickWaiting();
            case BITE -> tickBite();
            case MINIGAME -> tickMinigame();
            case REELING -> tickReeling();
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (state != State.MINIGAME && state != State.BITE) return;
        if (!autoMinigame.get()) return;

        scanCounter++;
        if (scanCounter % scanFrequency.get() != 0) return;

        if (state == State.BITE) {
            detectBar();
        } else if (state == State.MINIGAME) {
            scanCursor();
        }
    }

    private boolean isHoldingRod() {
        if (mc.player == null) return false;
        ItemStack main = mc.player.getMainHandStack();
        ItemStack off = mc.player.getOffhandStack();
        return main.getItem() == Items.FISHING_ROD || off.getItem() == Items.FISHING_ROD;
    }

    private void tickIdle() {
        if (!autoCast.get()) return;
        if (mc.player.fishHook != null) return;
        rightClick();
        state = State.CASTING;
        timer = 0;
    }

    private void tickCasting() {
        timer++;
        if (timer > castDelay.get()) {
            if (mc.player.fishHook != null) {
                state = State.WAITING;
            } else {
                state = State.IDLE;
            }
            timer = 0;
        }
    }

    private void tickWaiting() {
        if (mc.player.fishHook == null) {
            state = State.IDLE;
            return;
        }
        boolean biting = false;
        try {
            biting = ((FishingHookAccessor) mc.player.fishHook).meteor$hasCaughtFish();
        } catch (Exception ignored) {
            biting = isBobberUnderwater();
        }
        if (biting) {
            state = State.BITE;
            timer = 0;
            barFound = false;
        }
    }

    private boolean isBobberUnderwater() {
        if (mc.player.fishHook == null) return false;
        return mc.player.fishHook.isUnderWater();
    }

    private void tickBite() {
        timer++;
        if (timer < biteWaitTicks.get()) return;

        if (autoMinigame.get() && barFound) {
            state = State.MINIGAME;
            timer = 0;
            return;
        }

        if (autoMinigame.get() && timer > biteWaitTicks.get() + 6) {
            doReel();
            return;
        }

        if (!autoMinigame.get() && autoCatch.get()) {
            doReel();
        }
    }

    private void tickMinigame() {
        timer++;
        if (timer > minigameTimeout.get() * 20) {
            doReel();
        }
        if (mc.player.fishHook == null) {
            state = State.IDLE;
        }
    }

    private void tickReeling() {
        if (mc.player.fishHook == null) {
            state = State.IDLE;
            timer = 0;
        }
    }

    private void doReel() {
        rightClick();
        state = State.REELING;
        timer = 0;
        barFound = false;
        recheckDelay = 5;
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
        int bfbX = (fbW - bfbW) / 2;
        int bfbY = (fbH - bfbH) / 2;

        int margin = 10;
        int readW = Math.min(bfbW + margin * 2, fbW);
        int readH = Math.min(bfbH + margin * 2, fbH);
        int readX = Math.max(0, bfbX - margin);
        int readY = Math.max(0, bfbY - margin);
        readW = Math.min(readW, fbW - readX);
        readH = Math.min(readH, fbH - readY);

        if (readW <= 0 || readH <= 0) return;

        ByteBuffer buf = BufferUtils.createByteBuffer(readW * readH * 4);
        GlStateManager.readPixels(readX, readY, readW, readH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        int barTopRowRel = -1;
        int bestScore = 0;

        for (int row = 0; row < readH; row++) {
            int count = 0;
            for (int col = 0; col < readW; col += 2) {
                int idx = (row * readW + col) * 4;
                int r = buf.get(idx) & 0xFF;
                int g = buf.get(idx + 1) & 0xFF;
                int b = buf.get(idx + 2) & 0xFF;
                if (isBarPixel(r, g, b)) count++;
            }
            if (count > bestScore) {
                bestScore = count;
                barTopRowRel = row;
            }
        }

        if (bestScore < barColorMinPixels.get()) return;

        int barRelY = barTopRowRel;
        int barHeightFound = bfbH;

        barFbX = readX;
        barFbY = readY + barRelY;
        barFbW = bfbW;
        barFbH = bfbH;

        int midRow = Math.min(barRelY + barHeightFound / 2, readH - 1);
        if (midRow < 0) midRow = 0;

        int gStart = -1, gEnd = -1;
        boolean inGreen = false;
        int threshold = greenThreshold.get();

        for (int col = 0; col < readW; col++) {
            int idx = (midRow * readW + col) * 4;
            int r = buf.get(idx) & 0xFF;
            int g = buf.get(idx + 1) & 0xFF;
            int b = buf.get(idx + 2) & 0xFF;

            boolean isGreen = (g > r + threshold && g > b + threshold);

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

        if (gStart >= 0 && gEnd > gStart + 3) {
            greenStartFb = gStart;
            greenEndFb = gEnd;
            barFound = true;
        }
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

        GlStateManager.readPixels(scanX, topScanY, scanW, arrowScanH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, topBuf);
        GlStateManager.readPixels(scanX, botScanY, scanW, arrowScanH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, botBuf);

        int threshold = whiteThreshold.get();
        int cursorTop = findCursorX(topBuf, scanW, arrowScanH, threshold);
        int cursorBot = findCursorX(botBuf, scanW, arrowScanH, threshold);

        int newCursorX = -1;
        if (cursorTop >= 0) newCursorX = scanX + cursorTop;
        else if (cursorBot >= 0) newCursorX = scanX + cursorBot;

        if (newCursorX < 0) return;
        cursorFbX = newCursorX;

        int tol = clickTolerance.get();
        if (cursorFbX >= greenStartFb + tol && cursorFbX <= greenEndFb - tol) {
            rightClick();
            state = State.REELING;
            timer = 0;
            barFound = false;
            recheckDelay = 5;
        }
    }

    private int findCursorX(ByteBuffer buf, int w, int h, int threshold) {
        for (int col = 0; col < w; col++) {
            for (int row = 0; row < h; row++) {
                int idx = (row * w + col) * 4;
                int r = buf.get(idx) & 0xFF;
                int g = buf.get(idx + 1) & 0xFF;
                int b = buf.get(idx + 2) & 0xFF;
                if (r > threshold && g > threshold && b > threshold) {
                    return col;
                }
            }
        }
        return -1;
    }

    private boolean isBarPixel(int r, int g, int b) {
        int thr = 35;
        if (r > g + thr && r > b + thr) return true;
        if (g > r + thr && g > b + thr) return true;
        if (r > 170 && g > 130 && b < 110) return true;
        return false;
    }
}
