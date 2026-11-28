#!/usr/bin/env python3
"""Merge a dev game's saved piece-set overrides into the mod's built-in alignments.

usage: tools/merge_builtins.py <game>/mccitybuilder/piecesets <pack file name>...

For each named overrides file (already sanitised, e.g. Sword_and_Kingdom.txt) the keys are merged into
src/main/resources/mccitybuilder/alignments/<same name>: a key already there is replaced in place, a new one is appended.
The '#v2' header is dropped (built-ins are read without it). Prints one line per pack: keys added / replaced.
"""
import pathlib
import sys

DEST = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/mccitybuilder/alignments"


def read(path):
    """Ordered {key: value} of a 'key TAB value' file (header and damaged lines skipped)."""
    out = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) == 2:
                out[parts[0]] = parts[1].strip()
    return out


def main(argv):
    src = pathlib.Path(argv[1])
    for name in argv[2:]:
        mine = read(src / name)
        merged = read(DEST / name)
        added = sum(k not in merged for k in mine)
        replaced = sum(k in merged and merged[k] != v for k, v in mine.items())
        merged.update(mine)
        (DEST / name).write_text("".join(f"{k}\t{v}\n" for k, v in merged.items()), encoding="utf-8")
        print(f"{name}: {added} added, {replaced} replaced, {len(merged)} keys")


if __name__ == "__main__":
    main(sys.argv)
