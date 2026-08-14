Add-Type -AssemblyName System.Drawing
$size = 1024
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'

# 整幅品牌蓝底 + 白色 D（占位图标，正式版由设计师出）
$g.Clear([System.Drawing.Color]::FromArgb(79, 124, 255))
$font = New-Object System.Drawing.Font("Segoe UI", 330, [System.Drawing.FontStyle]::Bold)
$sf = New-Object System.Drawing.StringFormat
$sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
$g.DrawString("D", $font, [System.Drawing.Brushes]::White,
    (New-Object System.Drawing.RectangleF 0, 0, $size, $size), $sf)

$out = "d:\AI Projects\AI-Projects\DevPilot\PROJECT\desktop\src-tauri\icons\app-icon.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved: $out"
