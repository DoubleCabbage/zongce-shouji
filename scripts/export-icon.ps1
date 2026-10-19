param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = Split-Path $PSScriptRoot -Parent
if (!$OutputDirectory) { $OutputDirectory = Join-Path $project 'docs/branding' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
[xml]$vector = Get-Content (Join-Path $project 'app/src/main/res/drawable/ic_launcher_foreground.xml') -Raw
$android = 'http://schemas.android.com/apk/res/android'
$culture = [Globalization.CultureInfo]::InvariantCulture

# Render the Android vector itself, so the exported PNG and launcher share geometry.
function Convert-VectorPath([string]$data) {
    $tokens = [regex]::Matches($data, '[MLHVQZ]|-?(?:\d*\.)?\d+') | ForEach-Object Value
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $i = 0; [single]$x = 0; [single]$y = 0
    while ($i -lt $tokens.Count) {
        $command = $tokens[$i++]
        switch ($command) {
            'M' { $x = [single]::Parse($tokens[$i++], $culture); $y = [single]::Parse($tokens[$i++], $culture); $path.StartFigure() }
            'L' { $nx = [single]::Parse($tokens[$i++], $culture); $ny = [single]::Parse($tokens[$i++], $culture); $path.AddLine($x,$y,$nx,$ny); $x=$nx; $y=$ny }
            'H' { $nx = [single]::Parse($tokens[$i++], $culture); $path.AddLine($x,$y,$nx,$y); $x=$nx }
            'V' { $ny = [single]::Parse($tokens[$i++], $culture); $path.AddLine($x,$y,$x,$ny); $y=$ny }
            'Q' {
                $cx=[single]::Parse($tokens[$i++],$culture); $cy=[single]::Parse($tokens[$i++],$culture)
                $nx=[single]::Parse($tokens[$i++],$culture); $ny=[single]::Parse($tokens[$i++],$culture)
                $path.AddBezier($x,$y,($x+2*($cx-$x)/3),($y+2*($cy-$y)/3),($nx+2*($cx-$nx)/3),($ny+2*($cy-$ny)/3),$nx,$ny)
                $x=$nx; $y=$ny
            }
            'Z' { $path.CloseFigure() }
            default { throw "Unsupported vector command: $command" }
        }
    }
    return ,$path
}

$svg = [Collections.Generic.List[string]]::new()
$svg.Add('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="#5956B4"/>')
$bitmap = [Drawing.Bitmap]::new(512,512)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#5956B4'))
    $graphics.ScaleTransform((512/108), (512/108))
    foreach ($node in $vector.vector.path) {
        $data = $node.GetAttribute('pathData',$android)
        $fill = $node.GetAttribute('fillColor',$android)
        $stroke = $node.GetAttribute('strokeColor',$android)
        $width = $node.GetAttribute('strokeWidth',$android)
        $path = Convert-VectorPath $data
        try {
            $svgFill = if ($fill -eq '@android:color/transparent') { 'none' } else { $fill }
            $attributes = 'd="' + $data + '" fill="' + $svgFill + '"'
            if ($svgFill -ne 'none') {
                $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($fill))
                try { $graphics.FillPath($brush,$path) } finally { $brush.Dispose() }
            }
            if ($stroke) {
                $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($stroke),[single]::Parse($width,$culture))
                $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
                $pen.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
                try { $graphics.DrawPath($pen,$path) } finally { $pen.Dispose() }
                $attributes += ' stroke="' + $stroke + '" stroke-width="' + $width + '" stroke-linecap="round" stroke-linejoin="round"'
            }
            $svg.Add('<path ' + $attributes + '/>')
        } finally { $path.Dispose() }
    }
    $bitmap.Save((Join-Path $OutputDirectory 'icon-512.png'),[Drawing.Imaging.ImageFormat]::Png)
    $svg.Add('</svg>')
    [IO.File]::WriteAllLines((Join-Path $OutputDirectory 'icon.svg'), $svg, [Text.UTF8Encoding]::new($false))

    # Mask and small-size checks; this is an asset preview, not a device screenshot.
    $preview = [Drawing.Bitmap]::new(560,200)
    $pg = [Drawing.Graphics]::FromImage($preview)
    try {
        $pg.Clear([Drawing.ColorTranslator]::FromHtml('#F8F8F5'))
        $pg.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $pg.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $pg.DrawImage($bitmap,28,28,144,144)
        $clip = [Drawing.Drawing2D.GraphicsPath]::new()
        try {
            $clip.AddEllipse(208,28,144,144); $pg.SetClip($clip)
            $pg.DrawImage($bitmap,208,28,144,144); $pg.ResetClip()
        } finally { $clip.Dispose() }
        $pg.DrawImage($bitmap,400,48,64,64)
        $pg.DrawImage($bitmap,488,60,40,40)
        $preview.Save((Join-Path $OutputDirectory 'icon-preview.png'),[Drawing.Imaging.ImageFormat]::Png)
    } finally { $pg.Dispose(); $preview.Dispose() }
} finally { $graphics.Dispose(); $bitmap.Dispose() }
Write-Output "Exported icon assets to $OutputDirectory"
