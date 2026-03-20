# Phomemo Label Creator (T02)

This app is now a **label editor** for Phomemo T02-style native Bluetooth printing.

## Core features

- Full-width label canvas at native T02-style width (`48mm`, `203dpi`).
- User-controlled label length (`20mm` to `120mm`).
- Add and edit objects:
  - Text (editable content + switchable font family)
  - Clipart symbols
  - QR codes
  - Images (picked from gallery; editor placeholder + export outline)
- Object transforms after insertion:
  - Move (drag)
  - Resize (width/height sliders)
  - Rotate (rotation slider)
- Pinch-zoom in editor mode.
- Save/export:
  - Native `.phlabel` JSON file
  - PNG export
  - SVG export
- Native print over Bluetooth RFCOMM/ESC-POS raster, banded/chunked for reliability.

## Notes

- Pair the T02 printer in Android Bluetooth settings first.
- Android 12+ requires Bluetooth runtime permissions.
