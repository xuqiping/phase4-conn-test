/**
 * File Keeper SVG Icon Generator
 * Generates SVG icons that can be converted to PNG
 *
 * Usage: node generate-icons-svg.js
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function generateSVG(size) {
    const scale = size / 128;

    return `<?xml version="1.0" encoding="UTF-8"?>
<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bgGradient" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#3b82f6;stop-opacity:1" />
      <stop offset="100%" style="stop-color:#1d4ed8;stop-opacity:1" />
    </linearGradient>
  </defs>

  <!-- Background -->
  <rect width="${size}" height="${size}" fill="url(#bgGradient)"/>
  <!-- Folder body -->
  <rect x="${(size/2 - 40*scale)}" y="${(size/2 - 15*scale)}"
        width="${80*scale}" height="${50*scale}"
        rx="${4*scale}" fill="#fbbf24"/>

  <!-- Folder tab -->
  <path d="M ${size/2 - 40*scale} ${size/2 - 25*scale}
           h ${35*scale}
           q ${4*scale} 0 ${4*scale} ${4*scale}
        v ${8*scale}
           h ${-35*scale - 4*scale}
           q ${-4*scale} 0 ${-4*scale} ${-4*scale}
           v ${-4*scale}
           q 0 ${-4*scale} ${4*scale} ${-4*scale}
           z"
        fill="#f59e0b"/>

  <!-- Lightning bolt -->
  <path d="M ${size/2 + 5*scale} ${size/2 - 20*scale}
           L ${size/2 - 5*scale} ${size/2 + 5*scale}
           L ${size/2} ${size/2 + 5*scale}
           L ${size/2 - 10*scale} ${size/2 + 25*scale}
           L ${size/2 + 5*scale} ${size/2}
           L ${size/2} ${size/2}
           Z"
        fill="#ffffff"
        stroke="rgba(0,0,0,0.2)"
        stroke-width="${1*scale}"/>
</svg>`;
}

// Main execution
const iconsDir = path.join(__dirname, '..', 'src-tauri', 'icons');

// Ensure icons directory exists
if (!fs.existsSync(iconsDir)) {
    fs.mkdirSync(iconsDir, { recursive: true });
}

console.log('Generating File Keeper SVG icons...\n');

// Generate SVG icons
const sizes = [32, 128, 256];
sizes.forEach(size => {
    const svg = generateSVG(size);
    const outputPath = path.join(iconsDir, `${size}x${size}.svg`);
    fs.writeFileSync(outputPath, svg);
    console.log(`✓ Generated ${size}x${size}.svg`);
});

console.log('\n✓ All SVG icons generated successfully!');
console.log('\nNext steps:');
console.log('1. Convert SVG to PNG using one of these methods:');
console.log('   - Online: https://cloudconvert.com/svg-to-png');
console.log('   - Inkscape: inkscape --export-type=png --export-filename=output.png input.svg');
console.log('   - ImageMagick: magick convert input.svg output.png');
console.log('   - Or open generate-icons.html in a browser for direct PNG download');
console.log('\n2. Convert 256x256.png to icon.ico:');
console.log('   - Online: https://convertio.co/png-ico/');
console.log('   - CLI: png-to-ico 256x256.png > icon.ico');
