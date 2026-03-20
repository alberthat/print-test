# Phomemo Canvas Print (Jetpack Compose)

This Android Studio project renders a label preview using Jetpack Compose `Canvas` and sends it directly to a paired Phomemo printer over Bluetooth (no external app handoff).

## Print target

- Physical label size: **50 mm x 30 mm**
- Target resolution: **200 dpi**
- Output bitmap size: **394 x 236 px** (rounded from 393.7 x 236.2)

## Native T02 printing approach

The app performs native Bluetooth printing by:

1. Finding a paired Bluetooth device with a likely Phomemo/T02 name.
2. Opening an RFCOMM socket using the standard SPP UUID.
3. Converting the label bitmap to 1-bit monochrome raster bytes.
4. Sending ESC/POS raster command bytes (`GS v 0`) directly to the printer.

## Usage

1. Pair your Phomemo T02 in Android Bluetooth settings.
2. Open this app and tap **Print to Phomemo T02**.
3. Grant Bluetooth permission (Android 12+) if prompted.

## Notes

- This implementation intentionally avoids launching the Phomemo mobile app.
- If your specific T02 firmware expects a different command framing than ESC/POS raster mode, further protocol tuning may be needed.
