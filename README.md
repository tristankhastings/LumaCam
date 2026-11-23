<div align="center">
  <img src="docs/logo.svg" alt="LumaCam logo" width="84" height="84" />
  <h1>LumaCam</h1>
  <p>A minimal Android camera app that was 100% coded by Gemini in Android Studio while I watched and cheered it on.</p>

  <p>
    <img src="https://img.shields.io/badge/Human%20Code-0%25-ff4444?style=flat-square" alt="Human Code 0%" />
    <img src="https://img.shields.io/badge/Vibe%20Coded%20With-Gemini%20in%20Android%20Studio-4285F4?style=flat-square" alt="Gemini in Android Studio" />
    <img src="https://img.shields.io/badge/Android-14%2B%20(API%2034)-black?style=flat-square" alt="Android 14+" />
    <img src="https://img.shields.io/badge/License-MIT-black?style=flat-square" alt="MIT License" />
  </p>
</div>

> [!CAUTION]
> **BIG AI DISCLAIMER: READ BEFORE JUDGING THE CODE**
>
> Every single line of Kotlin, Camera2 boilerplate, AGSL shader math, and Jetpack Compose UI in this repository was written **entirely by Gemini in Android Studio**.
>
> - **Did a human write any of this?** No. My main contributions were typing prompts, clicking "Accept", squinting at Logcat, and renaming the app from `MonoCam` to **LumaCam** at the last minute (which is why the package name is still `com.tristan.monocam` — please don't look too closely).
> - **Is it production-ready?** It takes photos, looks cool on an OLED screen, and hasn't melted my phone yet. Use at your own risk, and if something breaks, blame the robot.
> - **Will I understand your pull request?** Probably not, I'll have to ask Gemini to explain it to me.

## What Is This?

**LumaCam** is a no-nonsense camera app for Android. No Material 3 bloat, no social feed, no 47-tab settings menu—just an edge-to-edge viewfinder, tactile gestures, unprocessed sensor output, and real-time GPU film looks (~1.6 MB release APK).

## What It Actually Does

- **Real-Time Film Looks (AGSL + `.cube` 3D LUTs):** Built-in **NATURAL**, **FUJI**, and **KODAK** shaders (warm vintage tone curve + subtle highlight halation), custom `.cube` 3D LUT import, and optional film grain.
- **Multi-Lens Camera2 Pipeline:** Automatically finds your physical lenses (`0.6×`, `1×`, `2×`, `3.5×`, `SELFIE`), turns on hardware OIS, turns *off* aggressive digital sharpening/noise reduction, and supports **RAW + JPG** (16-bit DNG) on supported lenses.
- **Pure `#000000` OLED UI:** Hidden status bar, frosted gesture sheets, and orientation-adaptive pills that rotate in place.
- **2-Axis Level Crosshair:** Helps you keep horizons straight and flat-lays flat, with a tiny haptic snap when you nail it.
- **Vintage 7-Segment LED Date Stamp:** Optional `YYYY MM DD` stamp burned into your JPEGs like it's 1998.
- **Aspect Ratios:** `FULL` (uses the whole screen while saving the full uncropped sensor image), plus `4:3`, `3:2`, `16:9`, and `1:1`.

## How to Use It

| Gesture / Button | What It Does |
| :--- | :--- |
| **Swipe Left / Right** | Switch film look |
| **Swipe Down** | Open Settings |
| **Swipe Up** | Open Manual Controls (`ISO`, `Shutter`, `Focus`) |
| **Tap Viewfinder** | Focus & meter (in Auto mode) |
| **Shutter / Volume Up or Down** | Take a photo |
| **Tap Pills** | Cycle lens, aspect ratio, or RAW |

## Building It Yourself

Requires **Android Studio** (AGP `9.5.2`, Kotlin `2.2.10`, Java `11`) and an **Android 14+ (`minSdk = 34`)** device.

```bash
git clone [https://github.com/tristankhastings/LumaCam.git](https://github.com/tristankhastings/LumaCam.git)
cd LumaCam

# Build debug APK
./gradlew assembleDebug

# Build R8-optimized release APK
./gradlew assembleRelease
