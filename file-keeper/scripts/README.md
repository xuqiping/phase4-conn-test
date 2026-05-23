# File Keeper Icon Generation

This directory contains scripts to generate application icons for File Keeper.

## Icon Design

The File Keeper icon features:
- **Blue gradient background** - representing the application theme
- **Yellow folder** - symbolizing file management
- **White lightning bolt** - representing quick access and speed

## Generated Sizes

- `32x32.png` - Small icon for taskbar and system tray
- `128x128.png` - Medium icon for app lists and shortcuts
- `256x256.png` - Large icon for high-DPI displays
- `icon.ico` - Windows icon file (multi-resolution)

## Generation Methods

### Method 1: Node.js Script (Recommended)

**Requirements:**
```bash
npm install canvas
```

**Generate icons:**
```bash
node scripts/generate-icons.js
```

**Convert to ICO:**
```bash
npm install -g png-to-ico
png-to-ico src-tauri/icons/256x256.png > src-tauri/icons/icon.ico
```

### Method 2: Browser-based Generator

1. Open `scripts/generate-icons.html` in a web browser
2. Click the download buttons for each size
3. Save the files to `src-tauri/icons/`
4. Convert 256x256.png to icon.ico using an online tool:
   - https://convertio.co/png-ico/
   - https://www.icoconverter.com/
   - https://cloudconvert.com/png-to-ico

### Method 3: Manual Design

If you prefer to design custom icons:

1. Use a design tool (Figma, Photoshop, GIMP, etc.)
2. Create icons at 256x256 resolution
3. Export as PNG at required sizes (32x32, 128x128, 256x256)
4. Convert the largest PNG to ICO format

## Icon Placement

All generated icons should be placed in:
```
src-tauri/icons/
├── 32x32.png
├── 128x128.png
├── 256x256.png
└── icon.ico
```

## Tauri Configuration

The icon is referenced in `src-tauri/tauri.conf.json`:

```json
{
  "bundle": {
    "icon": [
      "icons/icon.ico"
    ]
  }
}
```

## Testing Icons

After generating icons:

1. **Development mode:**
   ```bash
   npm run tauri dev
   ```
   Check the window icon and taskbar icon.

2. **Build installer:**
   ```bash
   npm run tauri build
   ```
   Check the installer icon and installed application icon.

## Troubleshooting

### Canvas package installation fails

If `npm install canvas` fails on Windows:

1. Install Windows Build Tools:
   ```bash
   npm install --global windows-build-tools
   ```

2. Or use the browser-based generator instead (Method 2)

### ICO conversion issues

- Ensure the PNG is exactly 256x256 pixels
- Use multiple sizes in the ICO (16, 32, 48, 256)
- Online converters usually handle this automatically

### Icon not showing in built app

1. Clear Tauri cache: `npm run tauri build -- --clean`
2. Verify icon path in `tauri.conf.json`
3. Ensure `icon.ico` is in `src-tauri/icons/`

## Design Guidelines

When customizing the icon:

- **Keep it simple** - Icons should be recognizable at small sizes
- **Use high contrast** - Ensure visibility on different backgrounds
- **Avoid text** - Text becomes unreadable at small sizes
- **Test at all sizes** - Verify the icon looks good at 16x16, 32x32, and larger
- **Consider the brand** - The icon should reflect File Keeper's purpose

## Resources

- [Tauri Icon Documentation](https://tauri.app/v1/guides/features/icons)
- [Windows Icon Guidelines](https://docs.microsoft.com/en-us/windows/apps/design/style/iconography/app-icon-design)
- [Icon Design Best Practices](https://developer.apple.com/design/human-interface-guidelines/app-icons)
