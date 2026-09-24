param([string]$Mode='full')
Add-Type -AssemblyName System.Drawing
$projectRoot=Split-Path $PSScriptRoot -Parent
$folder=Join-Path $projectRoot 'artifacts/avatar-audit/device'
$catalog=Get-Content (Join-Path $projectRoot 'app/src/main/assets/live2d/catalog.json') -Raw | ConvertFrom-Json
$font=New-Object System.Drawing.Font('Microsoft YaHei',9)
for($page=0;$page -lt [Math]::Ceiling($catalog.Count/25);$page++) {
  $bitmap=New-Object System.Drawing.Bitmap(1250,1125)
  $g=[System.Drawing.Graphics]::FromImage($bitmap)
  $g.Clear([System.Drawing.Color]::FromArgb(15,22,34))
  $found=0
  for($slot=0;$slot -lt 25;$slot++) {
    $index=$page*25+$slot
    if($index -ge $catalog.Count) { break }
    $file=Join-Path $folder ('{0:000}-{1}.jpg' -f $index,$Mode)
    if(!(Test-Path $file)) {continue}
    $x=($slot%5)*250; $y=[Math]::Floor($slot/5)*225
    $img=[System.Drawing.Image]::FromFile($file)
    $g.DrawImage($img,$x,$y,250,190)
    $label=('{0:000} {1}' -f $index,$catalog[$index].name)
    $g.DrawString($label,$font,[System.Drawing.Brushes]::White,[System.Drawing.RectangleF]::new($x+3,$y+191,245,34))
    $img.Dispose(); $found++
  }
  $g.Dispose()
  if($found -gt 0) { $bitmap.Save((Join-Path $projectRoot ('artifacts/avatar-audit/sheet-{0}-{1:00}.png' -f $Mode,$page)),[System.Drawing.Imaging.ImageFormat]::Png) }
  $bitmap.Dispose()
}
$font.Dispose()
