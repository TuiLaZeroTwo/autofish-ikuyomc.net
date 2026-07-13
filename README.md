# TLZ AutoFish

A Meteor Client addon for automated fishing with custom Stardew-style fishing minigame support, designed for IkuyoMC.

## Features

- **Auto Cast** - Automatically casts your fishing rod when not in use
- **Auto Catch** - Automatically reels in when a fish bites (for fish without minigame)
- **Auto Minigame** - Plays the Stardew-style fishing minigame using screen pixel scanning
  - Detects the red/yellow/green progress bar overlay
  - Finds the green "perfect catch" zone
  - Tracks the white cursor arrows as they bounce left-right
  - Right-clicks when the cursor enters the green zone

## Usage

1. Enable the module: `.module auto-fish enable`
2. Hold a fishing rod in your main or off-hand
3. The module handles everything: cast → wait → bite → minigame → reel → repeat

### Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `auto-cast` | true | Auto cast when rod is ready |
| `auto-catch` | true | Auto reel on bite (no minigame) |
| `auto-minigame` | true | Auto play the fishing minigame |
| `cast-delay` | 6 ticks | Delay before checking bobber after cast |
| `bite-wait-ticks` | 4 | Ticks to wait after bite before scanning |
| `minigame-timeout` | 8s | Max time before giving up on minigame |
| `click-tolerance` | 5px | Pixels inside green zone to trigger click |
| `scan-frequency` | 3 | Screen scan every N frames |
| `bar-width` | 781 | Bar overlay width (screen px) |
| `bar-height` | 106 | Bar overlay height (screen px) |
| `green-threshold` | 35 | G-R difference to detect green |
| `white-threshold` | 200 | Brightness to detect white cursor |
| `bar-color-min` | 30 | Min colored pixels to confirm bar |

## How it works

The module uses a state machine:

```
IDLE → CAST → WAIT → BITE → MINIGAME → REEL → IDLE
                         ↓ (no minigame)
                       REEL
```

- **Bite detection**: Uses Meteor's `FishingHookAccessor` to detect when the bobber catches a fish
- **Bar detection**: Reads screen pixels via OpenGL after the minigame overlay renders, searching for red/green/yellow bar colors at the center of the screen
- **Green zone**: Scans the bar's midline for pixels where green channel exceeds red by the configured threshold
- **Cursor tracking**: Scans above and below the bar for white arrow pixels

## Building

```bash
./gradlew build
```

The JAR will be in `build/libs/`.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.19.2+
- Meteor Client (1.21.11 build)
