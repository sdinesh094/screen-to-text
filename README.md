# Screen to Text

Android app that reads text off a laptop screen or whiteboard.

1. A USB webcam plugged into the phone shows a live feed.
2. **Capture** freezes one frame as a photo.
3. The photo is turned into text you can edit and **Copy**.

Two ways to read the photo, with a switch on the main screen:

| Mode | Good for | Needs |
|---|---|---|
| On the phone (default) | Printed text and code. Free, offline, private. | Nothing |
| Cloud AI | Glare, small text, tables, diagrams | Internet + a Claude API key |

## Get the APK from GitHub

1. Create an empty GitHub repository and push this folder to its `main` branch.
2. The **Build APK** workflow starts by itself (see the **Actions** tab). It takes about 3 to 5 minutes.
3. Open **Releases → Latest build** and download `ScreenToText.apk` on the phone.
4. Install it (Android will ask you to allow installs from your browser).

Every build is signed with the same key (`app/debug.keystore`), so a newer APK
installs over the older one. That key is public in the repo, which is fine for
a personal tool but not for a Play Store release.

No Gradle wrapper is included. GitHub installs Gradle 8.7 itself. Android
Studio will offer to create the wrapper if you open the project there.

## Using it

1. Plug the webcam into the phone (USB-C, or an OTG adapter).
2. Open the app. Allow **Camera**, then allow **USB access** to the webcam.
3. Point the webcam so the screen or whiteboard fills the picture, then tap **Capture**.
4. Text appears in the bottom half. Edit if needed, then **Copy**.
5. **Live view** goes back to the feed. **Read again** re-reads the same photo
   (handy after flipping the Cloud AI switch).

Other buttons:

- **Resolution**: the app starts at up to 1920×1080. If the webcam offers more, pick it here. More pixels means better text.
- **Pick image**: read a photo from the gallery instead. Useful for testing without the webcam.
- **Settings**: Claude API key and model name (default `claude-sonnet-5-5`). The key is stored only on the phone, in the app's private storage.

## Tips for a readable photo

- Fill the frame with the screen. Text the webcam cannot resolve cannot be read.
- Tilt the laptop screen slightly to remove reflections, and raise screen brightness.
- Zoom the content on the laptop (Ctrl and +). Bigger letters beat a better camera.
- If the webcam has manual focus, focus it once on the screen.

## How it is built

| File | Job |
|---|---|
| `MainActivity.kt` | Screen, webcam feed, capture, buttons |
| `LocalOcr.kt` | On-phone reading with ML Kit; rebuilds indentation and columns from text positions |
| `CloudOcr.kt` | Sends the photo to the Claude API and returns the text |
| `Prefs.kt` | Saved settings |

Libraries: [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) for the USB
webcam, [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) for on-phone reading.

## Limits

- The phone must support USB host mode (almost all recent phones do).
- On-phone mode reads Latin-script text only and will not understand diagrams; it only returns the words in them.
- Handwriting on a whiteboard works much better in Cloud AI mode.
