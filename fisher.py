"""
IkuyoMC.net Stardew-style fishing bot
Screen capture + OpenCV based external tool
"""
import time
import random
import logging
from enum import Enum, auto
from dataclasses import dataclass
from collections import deque
from typing import Optional

import cv2
import numpy as np
import mss
import pyautogui
import win32gui
import win32con

pyautogui.PAUSE = 0
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("fisher")


@dataclass
class Config:
    green_hue_center: float = 129
    green_hue_tolerance: float = 30
    green_sat_min: float = 0.5
    green_val_min: float = 0.3
    white_sat_max: float = 0.2
    white_val_min: float = 0.6
    bar_width_pct: float = 0.40
    bar_height_pct: float = 0.055
    click_tolerance: int = 0
    minigame_timeout: float = 30.0
    cast_delay_min: float = 0.5
    cast_delay_max: float = 1.5
    scan_interval: float = 0.016
    wait_min: float = 8.0
    wait_max: float = 25.0
    hud: bool = False
    debug: bool = False


class State(Enum):
    INIT = auto()
    CASTING = auto()
    WAITING_BOBBER = auto()
    FISHING = auto()
    BITE_CHECK = auto()
    MINIGAME = auto()
    REELING = auto()
    STOP = auto()


class Fisher:
    def __init__(self, cfg: Optional[Config] = None):
        self.cfg = cfg or Config()
        self.sct = mss.MSS()
        self.state = State.INIT
        self.monitor = self.sct.monitors[1]
        self.sw = self.monitor["width"]
        self.sh = self.monitor["height"]
        self.state_start = 0.0
        self.bar_rect: Optional[dict] = None
        self.green_start: Optional[int] = None
        self.green_end: Optional[int] = None
        self.cursor_x: Optional[int] = None
        self.wait_until = 0.0
        self.log_buffer = deque(maxlen=10)
        self._hud_ready = False
        self._last_hud_time = 0.0

    def _hud_log(self, msg):
        self.log_buffer.append(str(msg)[:80])

    def _hud_setup(self):
        cv2.namedWindow("Fisher HUD")
        cv2.resizeWindow("Fisher HUD", 800, 600)
        cv2.createTrackbar("Bar Width%", "Fisher HUD",
            int(self.cfg.bar_width_pct * 100), 80,
            lambda v: setattr(self.cfg, 'bar_width_pct', max(0.1, v / 100.0)))
        cv2.createTrackbar("Bar Height%", "Fisher HUD",
            int(self.cfg.bar_height_pct * 100), 20,
            lambda v: setattr(self.cfg, 'bar_height_pct', max(0.01, v / 100.0)))
        cv2.createTrackbar("Hue Tolerance", "Fisher HUD",
            self.cfg.green_hue_tolerance, 90,
            lambda v: setattr(self.cfg, 'green_hue_tolerance', max(5, v)))
        self._hud_ready = True

    def _hud_draw(self, now):
        rect = self._center_rect(0.6, 0.4)
        img = self.capture(rect)
        img_bgr = cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)
        h, w = img_bgr.shape[:2]

        scan_w = int(w * (self.cfg.bar_width_pct / 0.6))
        scan_h = int(h * (self.cfg.bar_height_pct / 0.4))
        scan_l = (w - scan_w) // 2
        scan_t = (h - scan_h) // 2
        cv2.rectangle(img_bgr, (scan_l, scan_t),
                     (scan_l + scan_w, scan_t + scan_h),
                     (80, 80, 80), 1)

        if self.bar_rect is not None:
            bl = self.bar_rect["left"] - rect["left"]
            bt = self.bar_rect["top"] - rect["top"]
            bw = self.bar_rect["width"]
            bh = self.bar_rect["height"]
            cv2.rectangle(img_bgr, (bl, bt), (bl + bw, bt + bh), (255, 0, 0), 2)
            if self.green_start is not None and self.green_end is not None:
                gl = self.green_start - rect["left"]
                gr = self.green_end - rect["left"]
                mid_y = bt + bh // 2
                cv2.line(img_bgr, (gl, mid_y), (gr, mid_y), (0, 255, 0), 3)
            if self.cursor_x is not None:
                cx = self.cursor_x - rect["left"]
                cv2.circle(img_bgr, (cx, bt - 5), 4, (255, 255, 255), -1)
                cv2.circle(img_bgr, (cx, bt + bh + 5), 4, (255, 255, 255), -1)

        fps = 1.0 / (now - self._last_hud_time + 0.001)
        self._last_hud_time = now
        cv2.putText(img_bgr, f"State:{self.state.name} FPS:{fps:.0f}", (5, 12),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.35, (200, 200, 200), 1)

        log_h = 80
        log_panel = np.zeros((log_h, w, 3), dtype=np.uint8)
        for i, msg in enumerate(self.log_buffer):
            cv2.putText(log_panel, msg, (5, 13 + i * 13),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.3, (180, 180, 180), 1)

        display = np.vstack([img_bgr, log_panel])
        if display.shape[0] > 700:
            sc = 700.0 / display.shape[0]
            display = cv2.resize(display, (int(display.shape[1] * sc), 700))

        cv2.imshow("Fisher HUD", display)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            self.state = State.STOP
            return False
        return True

    def _center_rect(self, w_pct, h_pct):
        w = int(self.sw * w_pct)
        h = int(self.sh * h_pct)
        l = (self.sw - w) // 2
        t = (self.sh - h) // 2
        return {"left": l, "top": t, "width": w, "height": h}

    def capture(self, rect: dict) -> np.ndarray:
        return np.array(self.sct.grab(rect))

    def find_bar(self) -> bool:
        rect = self._center_rect(self.cfg.bar_width_pct, self.cfg.bar_height_pct)
        if rect["width"] <= 0 or rect["height"] <= 0:
            return False
        margin = 5
        read_l = max(0, rect["left"] - margin)
        read_t = max(0, rect["top"] - margin)
        read_r = min(self.sw, rect["left"] + rect["width"] + margin)
        read_b = min(self.sh, rect["top"] + rect["height"] + margin)
        cap_rect = {"left": read_l, "top": read_t, "width": read_r - read_l, "height": read_b - read_t}
        if cap_rect["width"] <= 0 or cap_rect["height"] <= 0:
            return False
        img = self.capture(cap_rect)
        img_bgr = cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)
        img_hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)

        center_hue = self.cfg.green_hue_center
        hue_range = self.cfg.green_hue_tolerance
        h_low = max(0, (center_hue - hue_range) / 2)
        h_high = min(179, (center_hue + hue_range) / 2)
        s_min = int(self.cfg.green_sat_min * 255)
        v_min = int(self.cfg.green_val_min * 255)

        lower1 = np.array([h_low, s_min, v_min], dtype=np.uint8)
        upper1 = np.array([h_high, 255, 255], dtype=np.uint8)
        green_mask = cv2.inRange(img_hsv, lower1, upper1)

        if center_hue + hue_range > 180:
            h_low2 = 0
            h_high2 = int((center_hue + hue_range - 360) / 2)
            lower2 = np.array([h_low2, s_min, v_min], dtype=np.uint8)
            upper2 = np.array([h_high2, 255, 255], dtype=np.uint8)
            green_mask2 = cv2.inRange(img_hsv, lower2, upper2)
            green_mask = cv2.bitwise_or(green_mask, green_mask2)

        best_row = -1
        best_gstart = -1
        best_gend = -1
        best_width = 0

        for row in range(green_mask.shape[0]):
            in_green = False
            gs = -1
            ge = -1
            for col in range(green_mask.shape[1]):
                if green_mask[row, col]:
                    if not in_green:
                        gs = read_l + col
                        in_green = True
                elif in_green:
                    ge = read_l + col
                    in_green = False
                    break
            if in_green:
                ge = read_l + green_mask.shape[1]
            if gs >= 0:
                w = ge - gs
                if w > best_width:
                    best_width = w
                    best_row = row
                    best_gstart = gs
                    best_gend = ge

        if best_row < 0:
            return False

        green_fb_y = read_t + best_row
        bar_h = rect["height"]
        self.bar_rect = {
            "left": read_l,
            "top": max(0, green_fb_y - bar_h // 2),
            "width": rect["width"],
            "height": bar_h,
        }
        self.green_start = best_gstart
        self.green_end = best_gend
        if self.cfg.debug:
            self._log(f"Bar found @ y={green_fb_y} green=[{best_gstart},{best_gend}]")
        return True

    def find_cursor(self) -> Optional[int]:
        if self.bar_rect is None:
            return None
        b = self.bar_rect
        arrow_h = 10
        top_y = max(0, b["top"] - arrow_h)
        bot_y = max(0, b["top"] + b["height"])
        scan_w = min(b["width"] + 20, self.sw - b["left"])
        scan_x = max(0, (self.sw - scan_w) // 2)

        if top_y + arrow_h > self.sh or bot_y + arrow_h > self.sh:
            return None

        regions = [
            ("top", {"left": scan_x, "top": top_y, "width": scan_w, "height": arrow_h}),
            ("bot", {"left": scan_x, "top": bot_y, "width": scan_w, "height": arrow_h}),
        ]

        for label, r in regions:
            img = self.capture(r)
            if img.size == 0:
                continue
            img_bgr = cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)
            img_hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
            s = img_hsv[:, :, 1].astype(np.float32) / 255.0
            v = img_hsv[:, :, 2].astype(np.float32) / 255.0
            white_mask = (s < self.cfg.white_sat_max) & (v >= self.cfg.white_val_min)

            for col in range(white_mask.shape[1]):
                white_count = np.sum(white_mask[:, col])
                if white_count >= 2:
                    start_col = col
                    while start_col > 0:
                        cnt = np.sum(white_mask[:, start_col - 1])
                        if cnt >= 1:
                            start_col -= 1
                        else:
                            break
                    cx = scan_x + start_col
                    if self.cfg.debug:
                        loc = "above" if label == "top" else "below"
                        in_zone = self.green_start is not None and self.green_end is not None and \
                            self.green_start + self.cfg.click_tolerance <= cx <= self.green_end - self.cfg.click_tolerance
                        self._log(f"Cursor {loc} bar at x={cx} green=[{self.green_start},{self.green_end}] inZone={in_zone}")
                    return cx
        return None

    def right_click(self):
        pyautogui.click(button="right")

    def cast(self):
        if self.cfg.debug:
            self._log("Casting rod")
        self.right_click()

    def reel(self):
        if self.cfg.debug:
            self._log("Reeling")
        self.right_click()

    def _log(self, msg):
        log.info(msg)
        if self.cfg.hud:
            self._hud_log(msg)

    def set_state(self, state: State):
        self.state = state
        self.state_start = time.time()

    def _focus_minecraft(self):
        def enum_callback(hwnd, windows):
            if win32gui.IsWindowVisible(hwnd):
                title = win32gui.GetWindowText(hwnd)
                if 'Minecraft' in title:
                    windows.append(hwnd)

        windows = []
        win32gui.EnumWindows(enum_callback, windows)
        if windows:
            win32gui.ShowWindow(windows[0], win32con.SW_RESTORE)
            win32gui.SetForegroundWindow(windows[0])
            time.sleep(0.5)
            log.info("Focused Minecraft window")
            return True
        log.warning("Minecraft window not found")
        return False

    def run(self):
        self._focus_minecraft()
        log.info("=== IkuyoMC Fisher started ===")
        log.info(f"Screen: {self.sw}x{self.sh}")
        if self.cfg.hud:
            self._hud_setup()
            self._log("HUD ready — press Q to quit")
        else:
            log.info("Make sure Minecraft is focused. Press Ctrl+C to stop.")
        self.set_state(State.CASTING)
        self.wait_until = time.time() + random.uniform(self.cfg.cast_delay_min, self.cfg.cast_delay_max)

        try:
            while self.state != State.STOP:
                now = time.time()
                self._tick()
                if self.cfg.hud and self._hud_ready:
                    if not self._hud_draw(now):
                        break
                else:
                    time.sleep(self.cfg.scan_interval)
        except KeyboardInterrupt:
            self._log("Stopped by user")
        finally:
            if self.cfg.hud:
                cv2.destroyAllWindows()

    def _tick(self):
        now = time.time()

        if self.state == State.CASTING:
            if now >= self.wait_until:
                self.cast()
                self.set_state(State.WAITING_BOBBER)
                self.wait_until = now + 3.0

        elif self.state == State.WAITING_BOBBER:
            if now >= self.wait_until:
                wait_time = random.uniform(self.cfg.wait_min, self.cfg.wait_max)
                self.set_state(State.FISHING)
                self.wait_until = now + wait_time
                if self.cfg.debug:
                    self._log(f"Fishing for {wait_time:.1f}s")

        elif self.state == State.FISHING:
            if now >= self.wait_until:
                self.set_state(State.BITE_CHECK)
                self.reel()
                self.wait_until = now + 0.5

        elif self.state == State.BITE_CHECK:
            if now >= self.wait_until:
                if self.find_bar():
                    self.set_state(State.MINIGAME)
                    self.wait_until = now + self.cfg.minigame_timeout
                    self._log("Minigame detected!")
                else:
                    if self.cfg.debug:
                        self._log("No bar found, re-casting")
                    self.set_state(State.CASTING)
                    self.wait_until = now + random.uniform(
                        self.cfg.cast_delay_min, self.cfg.cast_delay_max
                    )

        elif self.state == State.MINIGAME:
            if now >= self.wait_until:
                self._log("Minigame timeout")
                self.right_click()
                self.set_state(State.REELING)
                self.wait_until = now + 1.0
                return

            if self.bar_rect is None or self.green_start is None:
                if not self.find_bar():
                    return
            else:
                cx = self.find_cursor()
                if cx is not None:
                    tol = self.cfg.click_tolerance
                    if self.green_start + tol <= cx <= self.green_end - tol:
                        self._log("Cursor in green zone, clicking!")
                        self.right_click()
                        self.bar_rect = None
                        self.green_start = None
                        self.green_end = None
                        self.set_state(State.REELING)
                        self.wait_until = now + random.uniform(1.0, 2.0)
                else:
                    self.bar_rect = None
                    self.green_start = None
                    self.green_end = None

        elif self.state == State.REELING:
            if now >= self.wait_until:
                self.set_state(State.CASTING)
                self.wait_until = now + random.uniform(
                    self.cfg.cast_delay_min, self.cfg.cast_delay_max
                )


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="IkuyoMC.net fishing bot")
    parser.add_argument("--debug", action="store_true", help="Enable debug logging")
    parser.add_argument("--hud", action="store_true", help="Show HUD overlay window")
    args = parser.parse_args()

    cfg = Config()
    if args.debug:
        cfg.debug = True
        log.setLevel(logging.DEBUG)
    if args.hud:
        cfg.hud = True

    fisher = Fisher(cfg)
    fisher.run()
