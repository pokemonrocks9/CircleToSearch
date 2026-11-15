# Circle To Search (lean fork)

Circle something on your screen, get search results in a slide-up sheet. No Google app, no Play Services, no chatbot, and no image-hosting service in the middle.

A stripped-down fork of [AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch), built to be a reliable replacement for Google's Circle to Search on a de-Googled phone (developed and tested on a Pixel 10a running GrapheneOS).

## What it does

- **Circle to search.** Draw around anything on the screen. The crop is sent straight from the app to the search engine you picked, and nowhere else.
- **One search bar.** Type a query alone, or circle something and add words to it. Tap the dimmed screenshot to close.
- **Embedded results sheet.** Results open in a slide-up sheet inside the app. Google's header and sign-in are hidden, and it follows your light/dark theme. Links that leave the search engine open in your browser.
- **Save images.** Long-press an image in the results and choose "Save image". It is saved to `Pictures/CircleToSearch`, with no storage permission needed.
- **QR codes handled on device.** If your circle contains a QR code it is decoded locally and shown with Copy / Open, with nothing sent anywhere.
- **Separate image and text engines**, chosen in the app's settings:

  | | Image search | Text search |
  |---|---|---|
  | Google | Lens | Google Search |
  | Bing | Visual Search | Bing |
  | Yandex | Yandex Images | Yandex |
  | SauceNAO | Anime / art source finder | n/a |
  | DuckDuckGo | n/a | DuckDuckGo |

  Bing, Yandex and SauceNAO image upload are best-effort and may need fixing if those sites change. TinEye is in the code but disabled.
- **Translate.** The translate button in the search bar reads text on the screen and opens it in Google Translate:
  - **Tap:** translate the whole screen, or the area you circled.
  - **Long-press:** draw a line or scribble over just the text you want.
  - **Text recognition** runs on the device, with a choice of ML Kit (accurate, models bundled in the app) or Tesseract (open source, language data downloaded on first use). Pick the language of the text in settings.
  - **Method setting:** send only the recognized text, fall back to sending the image if no text is found (default), or always let Google Translate read the image.
  - The button can be switched off entirely in settings.
- **Remember cookies** switch. Off by default (fresh session each time). Turn it on to make Google captchas stick once solved.

## Ways to launch it

- **Tap the app icon.** Searches immediately once setup is done. Long-press the icon for **Settings**.
- **Edge gesture / shortcut apps.** Launch the app, or use the action `com.akslabs.circletosearch.TRIGGER`.
- **Default assistant** (optional). Long-press home or your assistant gesture.

The tap and edge-gesture routes only need the accessibility service, so they keep working even if the default-assistant setting gets reset.

## Setup

Open the app after installing. The checklist shows what's left:

1. **Accessibility service**: used only to take the screenshot. It reads nothing from the screen.
2. **Network permission**: on GrapheneOS, turn on the per-app Network toggle (it is not granted automatically when an update adds internet access).
3. **Default assistant** (optional): for hold-home.

## Privacy

- Only the circled area leaves the device, and only to the engine you chose.
- Translate: by default only the recognized text is sent. In the "image" modes, the picture goes to Google Translate (Google).
- No accounts and no logs kept. The app itself has no analytics. Backups are disabled.
- The ML Kit text recognizer is a Google library that runs on the device. If you want no Google code in that path, choose Tesseract in settings.
- The screenshot is kept in memory only and dropped after use.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

To sign with your own key, set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` before building. Without a release keystore the build signs with the debug key, so it installs over a debug build. Debug builds (`assembleDebug`) also work, but the UI is noticeably slower.

Useful logs: `adb logcat -s CTS.Entry CTS.Session CTS.Overlay CTS.Search CTS.A11y CTS.Web CTS.OCR CTS.Save CTS.Perf`

## Credits

Based on [AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch). This fork removes the AI-chat and extra-feature code and rebuilds the capture, overlay and search flow. Text recognition for translate uses ML Kit and Tesseract.
