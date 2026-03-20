# Phomemo Canvas Print (Jetpack Compose)

This Android Studio project renders a label preview using Jetpack Compose `Canvas` and prints a generated bitmap.

## Print target

- Physical label size: **50 mm x 30 mm**
- Target resolution: **200 dpi**
- Output bitmap size: **394 x 236 px** (rounded from 393.7 x 236.2)

## Phomemo T02 notes

Phomemo T02 support depends on an installed Android print service/plugin from Phomemo. The app opens Android's print dialog with:

- custom media size set to 50x30 mm
- no margins
- monochrome mode
- 200 dpi print resolution request

Then select the **Phomemo T02** destination inside the print dialog.
