# Remouse Multilingual

This repository is a multilingual adaptation of the open-source **Remouse** project by **zy0816**. **Mohamed LALAH** contributed the multilingual adaptation, not the original application.

- Original project: [Remouse by zy0816](https://github.com/zy0816/remouse)
- Multilingual adaptation: **Mohamed LALAH**
- Languages: **English** (default/fallback), **French**, **Arabic**, and **Simplified Chinese**, selected automatically from the device locale.

Arabic supports RTL interface text/layout while preserving physical D-pad and cursor directions. All three SeekBars retain LEFT-to-decrease and RIGHT-to-increase behavior. There is no in-app language selector.

The original MIT license and copyright notice are preserved in [LICENSE](LICENSE). The upstream usage and implementation documentation follows; its clone command refers to the original repository.

**Turn your TV remote's D-pad into a free-moving cursor.**

[中文说明](README.zh-CN.md)

An Android TV accessibility service that replaces focus-based navigation with a real cursor you steer using the remote's arrow keys. Built for the apps that were never designed for a TV — web views, phone apps sideloaded onto a TV box, settings screens buried behind controls the D-pad simply can't reach.

## Why this exists

TV interfaces move focus between elements. That works until an app has no focus order — a web page, a phone-first app, a video player with a scrub bar. Then you're stuck.

A cursor solves that, but the obvious implementation doesn't work: **injecting touch gestures does nothing on most TV apps.** They're built around focus navigation and ignore touch events entirely. On a TCL launcher, a synthetic tap just resets focus to the first tile — the app never opens.

Remouse clicks through the accessibility node tree instead. It finds the node under the cursor and calls `ACTION_CLICK` on it, falling back to gesture injection only when no clickable node is there. That one difference is what makes it actually work.

## Features

- **Free cursor** — arrow keys move it in 8 directions, starting slow and accelerating smoothly the longer you hold. A quick tap nudges it ~10px, so small buttons stay reachable.
- **Node-based clicking** — `ACTION_CLICK` on the accessibility node, not injected touch. Works where gesture injection silently fails.
- **Snap to nearby targets** — the cursor doesn't have to sit exactly on a button. Anything within 5% of the screen width counts, with the smallest enclosing node winning when several overlap.
- **Hover highlight** — a neutral outline marks the element that will be clicked, so you confirm before pressing OK rather than guessing.
- **Scroll mode** — long-press OK to switch; arrow keys then page through the list under the cursor.
- **Edge paging** — hold the cursor against a screen edge for 300 ms and the container underneath pages in that direction.
- **Pause without disabling** — long-press BACK to hand every key back to the system for apps where the cursor gets in the way. Long-press again to return.
- **Idle fade** — the cursor disappears 3 s after the last key and returns instantly on the next one.

The pointer itself is deliberately quiet: a dark translucent fill with a white stroke, no glow and no animation. The two contrasting layers keep it visible over both bright and dark content.

## Key mapping

| Key | Cursor mode | Scroll mode |
| --- | --- | --- |
| Arrow keys | Move cursor (tap = nudge, hold = accelerate) | Page the list under the cursor |
| OK | Click the highlighted element | Click |
| Long-press OK | Switch to scroll mode | Switch back to cursor mode |
| Long-press BACK | Pause / resume Remouse | Pause / resume |
| BACK (short) | Normal back | Normal back |
| Volume +/− | Cursor speed | Scroll speed |
| Cursor against a screen edge | Page in that direction | — |

## Install

Build the APK (see below) or grab one from [Releases](../../releases), install it, then enable the accessibility service:

```
Settings → Accessibility → Remouse → On
```

Chinese-brand TVs block most of that from the UI. If installing fails or the accessibility toggle refuses to stick, see **[docs/install-on-tcl.md](docs/install-on-tcl.md)** — it covers getting an ADB connection on TCL / FFALCON sets and the three vendor restrictions you have to clear first.

## Build

```bash
git clone https://github.com/zy0816/remouse.git
cd remouse
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

Signing: if a `platform.keystore` exists in the project root it is used for release builds. Without it, release builds are unsigned; there is no automatic debug-signing fallback for release. For development, use `./gradlew clean :app:assembleDebug :app:lintDebug` with JDK 11 and Android SDK Platform 33. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses the standard debug signing configuration. No key material is in this repository.

Requirements: Android 7.0 (API 24) or newer, compiled against API 33.

## How it works

The whole thing is one `AccessibilityService`:

- `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents` let `onKeyEvent` intercept the remote's keys before the foreground app sees them. Both are required — the runtime flag alone silently does nothing.
- The cursor and the highlight box are two `TYPE_ACCESSIBILITY_OVERLAY` windows, so no overlay permission is needed.
- Hit testing walks the window list from the top layer down, prunes any subtree whose bounds miss a small search box around the cursor, and picks the smallest node containing the point — or the nearest one within the snap radius.
- All node work (`getWindows()`, tree traversal, `performAction`) runs on a dedicated `HandlerThread`. These are cross-process calls; on main they stall the frame the cursor is being drawn in.

## License

MIT — see [LICENSE](LICENSE).
