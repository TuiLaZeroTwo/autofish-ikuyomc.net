"""
IkuyoMC.net Stardew-style fishing bot — minigame only
Manual cast/reel, bot only clicks when cursor is in green zone
"""
import time
import logging
from enum import Enum, auto
from dataclasses import dataclass
from collections import deque
from typing import Optional

import cv2
import numpy as np
import mss

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("fisher")


@dataclass
class Config:
    green_hue_center: float = 129
    green_hue_tolerance: float = 30
    green_sat_min: float = 0.5
    green_val_min: float = 0.3
    white_sat_max: float = 0.2
    white_val_min: float = 0.4
    cursor_template: str = "img/image.png"
    cursor_threshold: float = 0.6
    bar_width_pct: float = 0.40
    bar_height_pct: float = 0.055
    click_tolerance: int = 0
    minigame_timeout: float = 30.0
    scan_interval: float = 0.016
    hud: bool = False
    debug: bool = False


class State(Enum):
    IDLE = auto()
    MINIGAME = auto()
    STOP = auto()


class Fisher:
    def __init__(self, cfg: Optional[Config] = None):
        self.cfg = cfg or Config()
        self.sct = mss.MSS()
        self.state = State.IDLE
        self.monitor = self.sct.monitors[1]
        self.sw = self.monitor["width"]
        self.sh = self.monitor["height"]
        self.bar_rect: Optional[dict] = None
        self.green_start: Optional[int] = None
        self.green_end: Optional[int] = None
        self.cursor_x: Optional[int] = None
        self.timeout_until = 0.0
        self.log_buffer = deque(maxlen=10)
        self._hud_ready = False
        self._last_hud_time = 0.0
        self._last_bar_msg = ""
        self._cursor_misses = 0
        self._cursor_tpl = self._load_cursor_template()
        self._prev_above: Optional[np.ndarray] = None
        self._prev_below: Optional[np.ndarray] = None

    def _load_cursor_template(self):
        try:
            tpl = cv2.imread(self.cfg.cursor_template, cv2.IMREAD_GRAYSCALE)
            if tpl is not None:
                log.info(f"Loaded cursor template: {self.cfg.cursor_template} ({tpl.shape[1]}x{tpl.shape[0]})")
                return tpl
            log.warning(f"Could not load cursor template: {self.cfg.cursor_template}")
        except Exception as e:
            log.warning(f"Cursor template error: {e}")
        return None

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
                     (scan_l + scan_w, scan_t + scan_h), (80, 80, 80), 1)

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

        log_panel = np.zeros((80, w, 3), dtype=np.uint8)
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
        msg = f"Bar green=[{best_gstart},{best_gend}]"
        if msg != self._last_bar_msg:
            self._log(msg)
            self._last_bar_msg = msg
        return True

    def find_cursor(self) -> Optional[int]:
        if self.bar_rect is None:
            return None
        b = self.bar_rect
        arrow_h = 20
        top_y = max(0, b["top"] - arrow_h)
        bot_y = max(0, b["top"] + b["height"])
        scan_w = min(b["width"] + 40, self.sw - b["left"])
        scan_x = max(0, (self.sw - scan_w) // 2)

        if top_y + arrow_h > self.sh or bot_y + arrow_h > self.sh:
            return None

        # Try template matching first
        if self._cursor_tpl is not None:
            tpl_w = self._cursor_tpl.shape[1]
            tpl_h = self._cursor_tpl.shape[0]
            for label, y_pos in [("top", top_y), ("bot", bot_y)]:
                r = {"left": scan_x, "top": y_pos, "width": scan_w, "height": arrow_h}
                img = self.capture(r)
                if img.size == 0:
                    continue
                gray = cv2.cvtColor(img, cv2.COLOR_BGRA2GRAY)
                if gray.shape[0] < tpl_h or gray.shape[1] < tpl_w:
                    continue
                result = cv2.matchTemplate(gray, self._cursor_tpl, cv2.TM_CCOEFF_NORMED)
                _, max_val, _, max_loc = cv2.minMaxLoc(result)
                if max_val >= self.cfg.cursor_threshold:
                    cx = scan_x + max_loc[0] + tpl_w // 2
                    if self.cfg.debug:
                        self._log(f"Cursor {label} bar (tmpl) at x={cx} conf={max_val:.2f}")
                    return cx

        # Motion detection: cursor is the only moving thing in the bar area
        for label, y_pos, prev_key in [
            ("above", top_y, "_prev_above"),
            ("below", bot_y, "_prev_below"),
        ]:
            r = {"left": scan_x, "top": y_pos, "width": scan_w, "height": arrow_h}
            img = self.capture(r)
            if img.size == 0:
                continue
            gray = cv2.cvtColor(img, cv2.COLOR_BGRA2GRAY)
            prev = getattr(self, prev_key)
            if prev is not None and prev.shape == gray.shape:
                diff = cv2.absdiff(gray, prev)
                _, thresh = cv2.threshold(diff, 25, 255, cv2.THRESH_BINARY)
                col_sums = np.sum(thresh, axis=0)
                max_col = int(np.argmax(col_sums))
                if col_sums[max_col] >= 3:
                    start = max_col
                    while start > 0 and col_sums[start - 1] >= 1:
                        start -= 1
                    cx = scan_x + start
                    if self.cfg.debug:
                        self._log(f"Cursor {label} bar (motion) at x={cx} peak={int(col_sums[max_col])}")
                    setattr(self, prev_key, gray)
                    return cx
            setattr(self, prev_key, gray)
        return None

    def right_click(self):
        try:
            import win32gui, win32api, win32con
            def enum_cb(hwnd, windows):
                if win32gui.IsWindowVisible(hwnd) and 'Minecraft' in win32gui.GetWindowText(hwnd):
                    windows.append(hwnd)
            windows = []
            win32gui.EnumWindows(enum_cb, windows)
            if windows:
                lparam = win32api.MAKELONG(0, 0)
                win32api.SendMessage(windows[0], win32con.WM_RBUTTONDOWN, win32con.MK_RBUTTON, lparam)
                win32api.SendMessage(windows[0], win32con.WM_RBUTTONUP, 0, lparam)
                return
        except ImportError:
            pass
        import pyautogui
        pyautogui.FAILSAFE = False
        pyautogui.click(button="right")

    def _reset_bar(self):
        self.bar_rect = None
        self.green_start = None
        self.green_end = None
        self.cursor_x = None
        self._cursor_misses = 0
        self._prev_above = None
        self._prev_below = None

    def _log(self, msg):
        log.info(msg)
        if self.cfg.hud:
            self._hud_log(msg)

    def run(self):
        log.info("=== IkuyoMC Minigame Bot started ===")
        log.info(f"Screen: {self.sw}x{self.sh}")
        log.info("Cast your rod and fish manually. I'll click when cursor is in the green zone.")
        if self.cfg.hud:
            self._hud_setup()
            self._log("HUD ready — press Q to quit")
        else:
            log.info("Press Ctrl+C to stop.")

        try:
            while self.state != State.STOP:
                now = time.time()
                self._tick(now)
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

    def _tick(self, now):
        if self.state == State.IDLE:
            if self.find_bar():
                self._log("Minigame detected!")
                self.state = State.MINIGAME
                self.timeout_until = now + self.cfg.minigame_timeout
            return

        if self.state == State.MINIGAME:
            if now >= self.timeout_until:
                self._log("Minigame timeout — back to idle")
                self._reset_bar()
                self.state = State.IDLE
                return

            if self.bar_rect is None or self.green_start is None:
                self.find_bar()
                return

            cx = self.find_cursor()
            if cx is not None:
                self._cursor_misses = 0
                self.cursor_x = cx
                tol = self.cfg.click_tolerance
                if self.green_start + tol <= cx <= self.green_end - tol:
                    self._log("Cursor in green zone, clicking!")
                    self.right_click()
                    self._reset_bar()
                    self.state = State.IDLE
            else:
                self._cursor_misses += 1
                if self.cfg.debug and self._cursor_misses == 1:
                    self._log("Cursor not found, scanning...")
                if self._cursor_misses > 60:
                    self._reset_bar()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="IkuyoMC.net fishing minigame bot")
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
