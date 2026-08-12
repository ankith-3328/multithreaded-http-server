#!/usr/bin/env bash
#
# prepare-media.sh — generate streaming fixtures from media/source/sample.mp4
#
# Produces:
#   media/hls-clear/      playlist.m3u8 + seg-NNN.ts          (clear HLS)
#   media/hls-encrypted/  playlist.m3u8 + seg-NNN.ts + key    (AES-128 HLS)
#   media/dash/           manifest.mpd + init/media .m4s      (MPEG-DASH)
#
# Requires a 1080p H.264 + AAC MP4 at media/source/sample.mp4
# (source acquisition is one-time and not part of this script).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$ROOT/source/sample.mp4"

if [[ ! -f "$SOURCE" ]]; then
  echo "error: $SOURCE not found" >&2
  echo "place a 1080p H.264+AAC mp4 at media/source/sample.mp4 first" >&2
  exit 1
fi

rm -rf "$ROOT/hls-clear" "$ROOT/hls-encrypted" "$ROOT/dash"
mkdir -p "$ROOT/hls-clear" "$ROOT/hls-encrypted" "$ROOT/dash"

# ffmpeg resolves the key path in enc.keyinfo relative to the CWD,
# so run everything from $ROOT and keep enc.keyinfo device-independent.
cd "$ROOT"

# ---- Clear HLS ----
ffmpeg -y -loglevel error \
  -i "$SOURCE" \
  -codec copy \
  -start_number 0 \
  -hls_time 4 \
  -hls_list_size 0 \
  -hls_segment_filename "$ROOT/hls-clear/seg-%03d.ts" \
  -f hls \
  "$ROOT/hls-clear/playlist.m3u8"

# ---- AES-128 encrypted HLS ----
openssl rand 16 > "$ROOT/hls-encrypted/stream.key"

cat > "$ROOT/enc.keyinfo" <<EOF
/hls/encrypted/key
hls-encrypted/stream.key
EOF

ffmpeg -y -loglevel error \
  -i "$SOURCE" \
  -codec copy \
  -start_number 0 \
  -hls_time 4 \
  -hls_list_size 0 \
  -hls_key_info_file "$ROOT/enc.keyinfo" \
  -hls_segment_filename "$ROOT/hls-encrypted/seg-%03d.ts" \
  -f hls \
  "$ROOT/hls-encrypted/playlist.m3u8"

# ---- MPEG-DASH ----
ffmpeg -y -loglevel error \
  -i "$SOURCE" \
  -codec copy \
  -seg_duration 4 \
  -init_seg_name 'init-$RepresentationID$.m4s' \
  -media_seg_name 'seg-$RepresentationID$-$Number%03d$.m4s' \
  -f dash \
  "$ROOT/dash/manifest.mpd"

echo "Media preparation complete."
echo "  clear HLS:     $ROOT/hls-clear/playlist.m3u8"
echo "  encrypted HLS: $ROOT/hls-encrypted/playlist.m3u8"
echo "  DASH:          $ROOT/dash/manifest.mpd"
