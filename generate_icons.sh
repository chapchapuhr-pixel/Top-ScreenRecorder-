#!/usr/bin/env bash
set -e

# Generate master square icon (512x512)
convert -size 512x512 xc:none \
  \( -size 512x512 gradient:'#FF3B30-#E01A1A' \
     \( -size 512x512 xc:none -fill white -draw "roundrectangle 24,24 488,488 110,110" \) \
     -alpha off -compose CopyOpacity -composite \) \
  -compose Over \
  \( -size 512x512 xc:none \
     -fill white \
     -draw "roundrectangle 100,165 340,345 32,32" \
     -draw "polygon 355,200 425,160 425,350 355,310" \
     -fill '#E01A1A' \
     -draw "circle 220,255 220,195" \
     -fill '#FF5252' \
     -draw "circle 220,255 220,215" \
     -fill white \
     -draw "circle 220,255 220,232" \
     -fill '#D50000' \
     -draw "circle 220,255 220,242" \
     -fill white \
     -draw "circle 135,195 135,185" \) \
  -composite /tmp/master_icon.png

# Generate master round icon (512x512)
convert -size 512x512 xc:none \
  \( -size 512x512 gradient:'#FF3B30-#E01A1A' \
     \( -size 512x512 xc:none -fill white -draw "circle 256,256 256,16" \) \
     -alpha off -compose CopyOpacity -composite \) \
  -compose Over \
  \( -size 512x512 xc:none \
     -fill white \
     -draw "roundrectangle 100,165 340,345 32,32" \
     -draw "polygon 355,200 425,160 425,350 355,310" \
     -fill '#E01A1A' \
     -draw "circle 220,255 220,195" \
     -fill '#FF5252' \
     -draw "circle 220,255 220,215" \
     -fill white \
     -draw "circle 220,255 220,232" \
     -fill '#D50000' \
     -draw "circle 220,255 220,242" \
     -fill white \
     -draw "circle 135,195 135,185" \) \
  -composite /tmp/master_icon_round.png

# Resize for all standard densities
for spec in "mipmap-mdpi:48" "mipmap-hdpi:72" "mipmap-xhdpi:96" "mipmap-xxhdpi:144" "mipmap-xxxhdpi:192"; do
  DIR="app/src/main/res/${spec%%:*}"
  SIZE="${spec##*:}"
  mkdir -p "$DIR"
  convert /tmp/master_icon.png -resize "${SIZE}x${SIZE}" "$DIR/ic_launcher.png"
  convert /tmp/master_icon_round.png -resize "${SIZE}x${SIZE}" "$DIR/ic_launcher_round.png"
  echo "Generated $DIR/ic_launcher.png (${SIZE}x${SIZE})"
done

# Save high-res app_icon.png
cp /tmp/master_icon.png app/src/main/res/drawable/app_icon.png

echo "All icon PNGs successfully generated!"
