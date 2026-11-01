"""Builds backend/src/main/resources/bot/fr-vectors.bin from the frWac vectors (ADR 0009).

    uv run --no-project --with numpy==2.5.4 python -I tools/vectors/build.py

The source (126 MB) is downloaded once into tools/vectors/.cache and checked against its MD5.
The output is a big-endian file: the magic `FRV1`, the number of words and the dimensions (two
ints), the words (one length byte, then UTF-8), then words x dimensions signed bytes.
"""

import hashlib
import re
import struct
import urllib.request
from pathlib import Path

import numpy as np

SOURCE_URL = "https://embeddings.net/embeddings/frWac_non_lem_no_postag_no_phrase_200_skip_cut100.bin"
SOURCE_MD5 = "6b8605bb1fc726f44a895bff26057bae"
KEPT = 50_000
TOKEN = re.compile(r"[a-zàâäçéèêëîïôöùûüÿœæ]{3,}")

here = Path(__file__).resolve().parent
resources = here.parents[1] / "backend/src/main/resources"
source = here / ".cache" / SOURCE_URL.rsplit("/", 1)[1]

if not source.exists():
    source.parent.mkdir(exist_ok=True)
    partial = source.with_suffix(".part")
    urllib.request.urlretrieve(SOURCE_URL, partial)
    partial.rename(source)
data = source.read_bytes()
assert hashlib.md5(data).hexdigest() == SOURCE_MD5, f"{source} is not the pinned file"

count, dimensions = map(int, data[: data.index(b"\n")].split())
position = data.index(b"\n") + 1
words = []
vectors = np.empty((count, dimensions), dtype=np.float32)
for i in range(count):
    if data[position] == 10:
        position += 1
    space = data.index(b" ", position)
    words.append(data[position:space].decode("utf-8", errors="replace"))
    vectors[i] = np.frombuffer(data, dtype="<f4", count=dimensions, offset=space + 1)
    position = space + 1 + 4 * dimensions

# The source is in frequency order: a word's rank is its index, and the order is kept.
game = {line.strip() for line in (resources / "words.txt").read_text(encoding="utf-8").splitlines() if line.strip()}
clean = [i for i, word in enumerate(words) if TOKEN.fullmatch(word)]
kept = sorted(set(clean[:KEPT]) | {i for i, word in enumerate(words) if word in game})
assert {words[i] for i in kept} >= game, "a word of the game is not in the source"

vectors = vectors[kept]
vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
# One scale for all: the 99.99th percentile of the absolute values maps to 127.
scale = 127 / np.percentile(np.abs(vectors), 99.99)
quantised = np.clip(np.rint(vectors * scale), -127, 127).astype(np.int8)

encoded = [words[i].encode("utf-8") for i in kept]
out = resources / "bot/fr-vectors.bin"
out.parent.mkdir(exist_ok=True)
out.write_bytes(
    b"FRV1"
    + struct.pack(">II", len(kept), dimensions)
    + b"".join(bytes([len(word)]) + word for word in encoded)
    + quantised.tobytes()
)
print(f"{len(kept)} words, {dimensions} dimensions, {out.stat().st_size} bytes, "
      f"sha256 {hashlib.sha256(out.read_bytes()).hexdigest()}")
