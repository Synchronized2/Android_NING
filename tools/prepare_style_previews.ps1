param([switch]$Download)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path $PSScriptRoot -Parent
$assetDir = Join-Path $projectRoot 'design-assets/style-previews'
New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
$revision = '6bf4d733ebf2b484a37c17d742eb47e5139e6a14'
$sources = @(
 'spam/a_city_with_neon_lights.png',
 'spam/a_group_of_buildings_with_neon_lights.png',
 'anime/a_city_skyline_with_a_tall_tower_lit_up_at_night.jpeg',
 'anime/a_cartoon_of_a_girl_with_white_hair_and_black_eyes.png',
 'anime/a_cartoon_of_a_woman_with_long_white_hair.jpg',
 'mountain/a_castle_on_a_hill_with_fog_with_Eltz_Castle_in_the_background.jpg',
 'anime/a_cartoon_of_a_castle_01.png',
 'outrun/a_city_at_night_with_a_fence_and_buildings.jpg',
 'anime/a_cartoon_of_a_girl_holding_a_bouquet_of_flowers.png',
 'anime/a_cartoon_of_a_girl_in_a_dress.png',
 'anime/a_cartoon_of_a_girl_with_long_pink_hair_sitting_on_a_balcony.png',
 'outrun/a_street_with_buildings_and_signs.png'
)
$sheet = New-Object System.Drawing.Bitmap 1024,768
$graphics = [Drawing.Graphics]::FromImage($sheet)
$graphics.Clear([Drawing.Color]::FromArgb(3,15,30))
$graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
try {
 for ($i=0; $i -lt $sources.Count; $i++) {
  $target = Join-Path $assetDir ('candidate-' + $i + [IO.Path]::GetExtension($sources[$i]))
  if ($Download -and !(Test-Path -LiteralPath $target)) {
   Invoke-WebRequest -Uri ('https://raw.githubusercontent.com/dharmx/walls/' + $revision + '/' + $sources[$i]) -OutFile $target
  }
  $source = [Drawing.Image]::FromFile($target)
  try {
   $side = [Math]::Min($source.Width,$source.Height)
   $sourceRect = New-Object Drawing.Rectangle (($source.Width-$side)/2),(($source.Height-$side)/2),$side,$side
   $destRect = New-Object Drawing.Rectangle (($i%4)*256),([Math]::Floor($i/4)*256),256,256
   $graphics.DrawImage($source,$destRect,$sourceRect,[Drawing.GraphicsUnit]::Pixel)
   $graphics.FillRectangle([Drawing.Brushes]::Black,$destRect.X,$destRect.Y,35,27)
   $graphics.DrawString([string]$i,[Drawing.SystemFonts]::DefaultFont,[Drawing.Brushes]::White,$destRect.X+8,$destRect.Y+6)
  } finally { $source.Dispose() }
 }
 $sheet.Save((Join-Path $assetDir ('candidates-' + [DateTime]::Now.ToString('HHmmss') + '.jpg')),[Drawing.Imaging.ImageFormat]::Jpeg)
} finally { $graphics.Dispose(); $sheet.Dispose() }

# Focus each crop on its subject; package only small local thumbnails.
$exports = @(
 @{ Index=7; Name='style_default'; X=0.5; Y=0.5; Zoom=1.0 },
 @{ Index=10; Name='style_anime'; X=0.72; Y=0.48; Zoom=0.72 },
 @{ Index=5; Name='style_realistic'; X=0.5; Y=0.52; Zoom=0.85 },
 @{ Index=0; Name='style_scifi'; X=0.5; Y=0.5; Zoom=1.0 }
)
foreach ($export in $exports) {
 $source = [Drawing.Image]::FromFile((Join-Path $assetDir ('candidate-' + $export.Index + [IO.Path]::GetExtension($sources[$export.Index]))))
 $thumb = New-Object Drawing.Bitmap 256,256
 $canvas = [Drawing.Graphics]::FromImage($thumb)
 try {
  $side = [int]([Math]::Min($source.Width,$source.Height) * $export.Zoom)
  $left = [int][Math]::Max(0,[Math]::Min($source.Width-$side,$source.Width*$export.X-$side/2))
  $top = [int][Math]::Max(0,[Math]::Min($source.Height-$side,$source.Height*$export.Y-$side/2))
  $canvas.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $canvas.DrawImage($source,(New-Object Drawing.Rectangle 0,0,256,256),(New-Object Drawing.Rectangle $left,$top,$side,$side),[Drawing.GraphicsUnit]::Pixel)
  $thumb.Save((Join-Path $projectRoot ('app/src/main/res/drawable-nodpi/' + $export.Name + '.png')),[Drawing.Imaging.ImageFormat]::Png)
 } finally { $canvas.Dispose(); $thumb.Dispose(); $source.Dispose() }
}
