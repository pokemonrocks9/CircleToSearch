# Circle To Search (lean fork)

Circle something on your screen, get search results in a slide-up sheet. No Google app, no Play Services, no chatbot, nothing uploaded to a middleman.

A stripped-down fork of [AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch), built to be a reliable replacement for Google's Circle to Search on a de-Googled phone (developed and tested on a Pixel 10a running GrapheneOS).

## What it does

- **Circle to search.** Draw around anything on the screen. The crop is sent straight from the app to the search engine you picked, and nowhere else. No image-hosting services in between.
- **One search bar.** Type a query alone, or circle something and add words to it.
- **Embedded results sheet.** Results open in a slide-up sheet inside the app. Google's header and sign-in are hidden, and it follows your light/dark theme. Links that leave the search engine open in your browser.
- **QR codes handled on device.** If your circle contains a QR code it is decoded locally and shown with Copy / Open, with nothing sent anywhere.
- **Separate image and text engines**, chosen in the app's settings:

  | | Image search | Text search |
  |---|---|---|
  | Google | Lens | Google Search |
  | Bing | Visual Search | Bing |
  | Yandex | Yandex Images | Yandex |
  | DuckDuckGo | n/a | DuckDuckGo |

  Bing and Yandex image upload are best-effort and may need fixing if the sites change. TinEye is in the code but disabled.
- **Remember cookies** switch. Off by default (fresh session each time). Turn it on to make Google captchas stick once solved.

## Ways to launch it

- **Tap the app icon.** Searches immediately once setup is done. Long-press the icon for **Settings**.
- **Edge gesture / shortcut apps.** Use the "Circle to search" app shortcut, or launch the action `com.akslabs.circletosearch.TRIGGER`.
- **Default assistant** (optional). Long-press home or your assistant gesture.

The tap and edge-gesture routes only need the accessibility service, so they keep working even if the default-assistant setting gets reset.

## Setup

Open the app after installing. The checklist shows what's left:

1. **Accessibility service**: used only to take the screenshot. It reads nothing from the screen.
2. **Network permission**: on GrapheneOS, turn on the per-app Network toggle (it is not granted automatically when an update adds internet access).
3. **Default assistant** (optional): for hold-home.

## Privacy

- Only the circled area leaves the device, and only to the engine you chose.
- No analytics, no accounts, no logs kept. Backups are disabled.
- The screenshot is kept in memory only and dropped after use.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Without a release keystore the build signs with the debug key, so it installs over a debug build. Debug builds also work (`assembleDebug`) but the UI is noticeably slower.

Useful logs: `adb logcat -s CTS.Entry CTS.Session CTS.Overlay CTS.Search CTS.A11y CTS.Web CTS.Perf`

## Credits

Based on [AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch). This fork removes the OCR, AI-chat and extra-feature code, and rebuilds the capture, overlay and search flow.
