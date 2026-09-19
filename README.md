# CraftingTableAutoSell

A **client-side** Fabric mod for Minecraft **26.2** that safely auto-sells Crafting Tables
from your inventory, **one at a time**, using `/ah sell 999`.

## What it does

- Press **Backspace** to toggle the auto-seller on/off. A local (client-only) chat line
  confirms the new state.
- While ON, it repeatedly:
  1. Scans your inventory for Crafting Tables.
  2. If a stack has more than 1, splits off **exactly one** using real inventory
     slot-click packets (pickup / place-one / swap) - never by editing the stack directly.
  3. Verifies the main hand holds exactly 1 Crafting Table, and that the cursor
     (carried item) is empty.
  4. Sends `/ah sell 999`.
  5. Waits for the hand to actually empty out (sale confirmed) before starting the
     next cycle.
- If the server replies with a message containing "you have too many items listed"
  (any capitalization/formatting), it stops, waits **8 seconds**, and retries -
  it never spams the command.
- When you have **zero** Crafting Tables left, it turns itself off, rings a bell
  sound 4 times, and stays off until you press Backspace again.
- If anything about the inventory state looks unexpected (wrong item, more than 1
  Crafting Table in hand, a stray carried item, no confirmation the sale went through,
  no safe empty slot to split into, etc.), it **immediately stops** rather than risk
  selling the wrong quantity.

See `AutoSellManager.java` for the full state machine and safety checks.

## Project layout

```
CraftingTableAutoSell/
├── build.gradle
├── gradle.properties
├── settings.gradle
├── LICENSE
├── .github/workflows/build.yml
└── src/main/
    ├── java/com/example/craftingtableautosell/
    │   ├── CraftingTableAutoSellClient.java   (entrypoint: keybind + tick pump + chat hooks)
    │   └── AutoSellManager.java               (all sell logic and safety checks)
    └── resources/
        ├── fabric.mod.json
        └── assets/craftingtableautosell/lang/en_us.json
```

## Building

Requires **JDK 25**.

```bash
gradle build
```

(No `gradlew` wrapper jar is committed, since this environment has no network access to
fetch one. Run `gradle wrapper --gradle-version 9.5.1` once locally if you'd like a
wrapper, or just use a local Gradle 9.5+ install / the CI workflow below.)

The built jar appears under `build/libs/`.

## CI

`.github/workflows/build.yml` builds the mod on every push/PR using JDK 25 and Gradle
9.5.1 (via `gradle/actions/setup-gradle`), then uploads the compiled jar as a workflow
artifact named `CraftingTableAutoSell`.

## Important note on how current this is

Minecraft 26.2 and its unobfuscated, Mojang-mappings-only toolchain (Fabric Loader
0.19.3, Fabric API 0.152.x, Fabric Loom 1.17.x) are extremely recent as of when this
project was written. This code was written directly against Mojang's official mapping
names (e.g. `Minecraft`, `LocalPlayer`, `InventoryMenu`, `ClickType`,
`handleInventoryMouseClick`, `KeyMapping`) rather than the old Yarn names, per Fabric's
own porting guide. It has **not** been compiled in this environment (no network/JDK 25
toolchain available here), so if `gradle build` reports a missing symbol, the most
likely spots to check first are:

- The exact Fabric Loom version pin in `build.gradle` (`1.17.+`).
- The exact Fabric API version in `gradle.properties` (`0.152.1+26.2`).
- `SoundEvents.BELL_BLOCK_USE` in `AutoSellManager.playBellSound` (used only as a
  fallback - the primary path looks the sound up by its stable resource location
  `minecraft:block.bell.use`, which shouldn't need any change).
- The exact `ClientReceiveMessageEvents` method signatures in
  `CraftingTableAutoSellClient` (package/class name unchanged per the Fabric API 26.1
  rename list, but double-check against current docs).

Cross-check against <https://docs.fabricmc.net/develop/porting/> and
<https://docs.fabricmc.net/develop/porting/fabric-api> (the official rename list) if
anything doesn't line up.

## Safety design notes

- All item movement goes through `Minecraft#gameMode.handleInventoryMouseClick(...)`
  against the player's own `InventoryMenu` (slot id `containerId`) - the same packets a
  real player sends when clicking their own inventory - never direct `ItemStack`/list
  mutation.
- Every intermediate step re-reads the actual slot/cursor contents and bails out
  (leaving everything as-is) if reality doesn't match what was expected.
- A scratch slot used to hold a split-off remainder is always verified empty
  immediately before use, and is chosen from main storage first, then the hotbar -
  crafting-grid, armor, and offhand slots are never touched.
- The mod will not act at all while any other screen (your inventory, a chest, a shop
  GUI, etc.) is open, and will not act while the cursor is already carrying an item.
