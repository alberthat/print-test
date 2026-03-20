# Phomemo Canvas Print (Jetpack Compose)

This Android Studio project renders a label preview using Jetpack Compose `Canvas` and generates a print-ready PNG.

## Print target

- Physical label size: **50 mm x 30 mm**
- Target resolution: **200 dpi**
- Output bitmap size: **394 x 236 px** (rounded from 393.7 x 236.2)

## Phomemo T02 implementation

Phomemo T02 printers pair and print through the **Phomemo mobile app** (not Android's built-in PrintManager destination list for most devices).

This app now:

1. Renders the 50x30 label as PNG in app cache.
2. Shares that PNG via Android `ACTION_SEND` with URI permission using `FileProvider`.
3. Tries to open the Phomemo app package directly (`com.quyin.phomemo`) when installed.
4. Falls back to the Android share chooser if Phomemo app is not installed.

## Usage

- Install and pair T02 in the official Phomemo app.
- Tap **Send to Phomemo App** in this project.
- In the Phomemo app, import the shared image and print.
