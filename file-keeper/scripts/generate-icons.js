/**
 * File Keeper Icon Generator
 * Generates application icons in multiple sizes
 *
 * Usage: node generate-icons.js
 *
 * Note: This script requires the 'canvas' package.
 * Install it with: npm install canvas
 *
 * For ICO conversion, use an online tool or install 'png-to-ico':
 * npm install -g png-to-ico
 * png-to-ico icons/256x256.png > icons/icon.ico
 */

const fs = require('fs');
const path = require('path');

// Check if canvas is available
let Canvas;
try {
    Canvas = require('canvas');
} catch (e) {
    console.error('Error: canvas package not found.');
    console.error('Please install it with: npm install canvas');
    console.error('\nAlternatively, open scripts/generate-icons.html in a browser to generate icons manually.');
    process.exit(1);
}

const { createCanvas } = Canvas;

function drawIcon(canvas) {
    const ctx = canvas.getContext('2d');
    const size = canvas.width;
    const scale = size / 128; // Base design on 128x128

    // Clear canvas
    ctx.clearRect(0, 0, size, size);

    // Background gradient (blue theme)
    const gradient = ctx.createLinearGradient(0, 0, size, size);
    gradient.addColorStop(0, '#3b82f6');
    gradient.addColorStop(1, '#1d4ed8');
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, size, size);

  // Draw folder icon
    ctx.save();
    ctx.translate(size * 0.5, size * 0.5);

    // Folder body
    ctx.fillStyle = '#fbbf24';
    ctx.beginPath();
    ctx.roundRect(-40 * scale, -15 * scale, 80 * scale, 50 * scale, 4 * scale);
    ctx.fill();

    // Folder tab
    ctx.fillStyle = '#f59e0b';
    ctx.beginPath();
    ctx.roundRect(-40 * scale, -25 * scale, 35 * scale, 12 * scale, 4 * scale);
    ctx.fill();

    // Lightning bolt (quick access symbol)
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.moveTo(5 * scale, -20 * scale);
    ctx.lineTo(-5 * scale, 5 * scale);
    ctx.lineTo(0 * scale, 5 * scale);
    ctx.lineTo(-10 * scale, 25 * scale);
    ctx.lineTo(5 * scale, 0 * scale);
    ctx.lineTo(0 * scale, 0 * scale);
    ctx.closePath();
    ctx.fill();

    // Add shadow to lightning
    ctx.strokeStyle = 'rgba(0, 0, 0, 0.2)';
    ctx.lineWidth = 1 * scale;
    ctx.stroke();

    ctx.restore();
}

function generateIcon(size, outputPath) {
    const canvas = createCanvas(size, size);
    drawIcon(canvas);

    const buffer = canvas.toBuffer('image/png');
    fs.writeFileSync(outputPath, buffer);
    console.log(`✓ Generated ${path.basename(outputPath)} (${size}x${size})`);
}

// Main execution
const iconsDir = path.join(__dirname, '..', 'src-tauri', 'icons');

// Ensure icons directory exists
if (!fs.existsSync(iconsDir)) {
    fs.mkdirSync(iconsDir, { recursive: true });
}

console.log('Generating File Keeper icons...\n');

// Generate PNG icons
generateIcon(32, path.join(iconsDir, '32x32.png'));
generateIcon(128, path.join(iconsDir, '128x128.png'));
generateIcon(256, path.join(iconsDir, '256x256.png'));

console.log('\n✓ All PNG icons generated successfully!');
console.log('\nNext steps:');
console.log('1. Convert 256x256.png to icon.ico using one of these methods:');
console.log('   - Online: https://convertio.co/png-ico/');
console.log('   - CLI: npm install -g png-to-ico && png-to-ico src-tauri/icons/256x256.png > src-tauri/icons/icon.ico');
console.log('2. Replace the existing icon.ico in src-tauri/icons/');
