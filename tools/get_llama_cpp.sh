#!/usr/bin/env sh
# Fetches the llama.cpp source the app is built against into llmspike/third_party/llama.cpp, at the exact commit pinned
# in llmspike/LLAMA_COMMIT (line 1: repository URL, line 2: commit hash and date). Fetches only that one commit.
# Run from anywhere: sh tools/get_llama_cpp.sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="$ROOT/llmspike/LLAMA_COMMIT"
URL="$(sed -n 1p "$PIN" | tr -d '\r')"
SHA="$(sed -n 2p "$PIN" | tr -d '\r' | cut -d' ' -f1)"
DEST="$ROOT/llmspike/third_party/llama.cpp"

if [ -d "$DEST/.git" ] && [ "$(git -C "$DEST" rev-parse HEAD)" = "$SHA" ]; then
    echo "llama.cpp already at $SHA"
    exit 0
fi
if [ -e "$DEST" ]; then
    echo "$DEST exists but is not llama.cpp at $SHA; move it away first" >&2
    exit 1
fi
mkdir -p "$DEST"
git -C "$DEST" init -q
# Windows: a few files in llama.cpp (its web UI, not built here) have paths longer than 260 characters
git -C "$DEST" config core.longpaths true
git -C "$DEST" remote add origin "$URL"
git -C "$DEST" fetch -q --depth 1 origin "$SHA"
git -C "$DEST" checkout -q FETCH_HEAD
echo "llama.cpp at $(git -C "$DEST" rev-parse HEAD)"
