# Word vectors of the bots

`build.py` makes `backend/src/main/resources/bot/fr-vectors.bin` from the frWac vectors of Jean-Philippe Fauconnier ([ADR 0009](../../docs/adr/0009-french-word-vectors-for-the-bots.md)).

```bash
uv run --no-project --with numpy==2.5.4 python -I tools/vectors/build.py
```

It downloads the source (126 MB) into `tools/vectors/.cache`, checks its MD5, keeps the first 50 000 words made of lowercase French letters (three or more) and the words of `words.txt`, normalises the vectors, quantises them to bytes and writes the file. Run twice, it writes the same bytes.

With numpy 2.5.4 and Python 3.14 on macOS the file has 50 001 words, 10 456 108 bytes and this SHA-256:

```
4041d279146261a19f8f10fd83ddc14ea4207e6a407331859300185412066a79
```

Another platform can round a value differently: if the hash differs, compare the words and the dimensions before the bytes. The file is regenerated only when the rule in the script or the words of the game change, and each regeneration adds about 10 MB to the history.
