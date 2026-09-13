#!/usr/bin/env python3
import pathlib
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: embed_spv.py <input.spv> <output.h> <symbol>")

src = pathlib.Path(sys.argv[1]).read_bytes()
if len(src) % 4:
    raise SystemExit("SPIR-V size must be a multiple of 4 bytes")

out = pathlib.Path(sys.argv[2])
symbol = sys.argv[3]
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", encoding="utf-8") as f:
    f.write("#pragma once\n#include <cstddef>\n")
    f.write(f"alignas(4) static const unsigned char {symbol}[] = {{\n")
    for i in range(0, len(src), 16):
        chunk = src[i:i+16]
        f.write("    " + ", ".join(f"0x{b:02x}" for b in chunk) + ",\n")
    f.write("};\n")
    f.write(f"static constexpr size_t {symbol}_size = sizeof({symbol});\n")
