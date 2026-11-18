param([Parameter(Mandatory = $true)][string] $OutputPath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# The page and folded corner use the artwork from the Android launcher icon.
function Get-PageImage([int] $Size) {
    $bitmap = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $canvas = [Drawing.Graphics]::FromImage($bitmap)
    $page = [Drawing.Drawing2D.GraphicsPath]::new([Drawing.Drawing2D.FillMode]::Alternate)
    $plate = [Drawing.Drawing2D.GraphicsPath]::new()
    $blue = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(0, 47, 167))
    $bytes = [IO.MemoryStream]::new()
    try {
        $canvas.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $canvas.ScaleTransform($Size / 48.0, $Size / 48.0)
        # Rounded plate, matching the adaptive icon Android draws behind the glyph.
        $radius = 10.0
        $plate.AddArc(2, 2, $radius * 2, $radius * 2, 180, 90)
        $plate.AddArc(46 - $radius * 2, 2, $radius * 2, $radius * 2, 270, 90)
        $plate.AddArc(46 - $radius * 2, 46 - $radius * 2, $radius * 2, $radius * 2, 0, 90)
        $plate.AddArc(2, 46 - $radius * 2, $radius * 2, $radius * 2, 90, 90)
        $plate.CloseFigure()
        $canvas.FillPath($blue, $plate)
        $page.AddPolygon([Drawing.PointF[]]@(
            [Drawing.PointF]::new(13, 9), [Drawing.PointF]::new(28, 9),
            [Drawing.PointF]::new(35, 16), [Drawing.PointF]::new(35, 39),
            [Drawing.PointF]::new(13, 39)))
        $page.AddPolygon([Drawing.PointF[]]@(
            [Drawing.PointF]::new(16, 12), [Drawing.PointF]::new(26, 12),
            [Drawing.PointF]::new(26, 19), [Drawing.PointF]::new(32, 19),
            [Drawing.PointF]::new(32, 36), [Drawing.PointF]::new(16, 36)))
        $canvas.FillPath([Drawing.Brushes]::White, $page)
        $canvas.FillRectangle([Drawing.Brushes]::White, 19, 23, 10, 2.5)
        $canvas.FillRectangle([Drawing.Brushes]::White, 19, 29, 8, 2.5)
        $bitmap.Save($bytes, [Drawing.Imaging.ImageFormat]::Png)
        return ,$bytes.ToArray()
    } finally {
        $bytes.Dispose()
        $blue.Dispose()
        $plate.Dispose()
        $page.Dispose()
        $canvas.Dispose()
        $bitmap.Dispose()
    }
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = @(foreach ($size in $sizes) { Get-PageImage $size })
$stream = [IO.File]::Create($OutputPath)
$writer = [IO.BinaryWriter]::new($stream)
try {
    $writer.Write([uint16] 0)
    $writer.Write([uint16] 1)
    $writer.Write([uint16] $sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($index = 0; $index -lt $sizes.Count; $index++) {
        $dimension = [byte] ($sizes[$index] % 256)
        $writer.Write($dimension)
        $writer.Write($dimension)
        $writer.Write([uint16] 0)
        $writer.Write([uint16] 1)
        $writer.Write([uint16] 32)
        $writer.Write([uint32] $images[$index].Length)
        $writer.Write([uint32] $offset)
        $offset += $images[$index].Length
    }
    foreach ($image in $images) { $writer.Write([byte[]] $image) }
} finally {
    $writer.Dispose()
}
