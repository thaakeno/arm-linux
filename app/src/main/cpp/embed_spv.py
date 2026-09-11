#!/usr/bin/env python3
import pathlib
import struct
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: embed_spv.py <input.spv> <output.h> <symbol>")

src = pathlib.Path(sys.argv[1]).read_bytes()
if len(src) % 4:
    raise SystemExit("SPIR-V size must be a multiple of 4 bytes")
words = struct.unpack("<%dI" % (len(src) // 4), src)
out = pathlib.Path(sys.argv[2])
symbol = sys.argv[3]
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", encoding="utf-8") as f:
    f.write("#pragma once\n#include <cstddef>\n#include <cstdint>\n")
    f.write(f"static const uint32_t {symbol}[] = {{\n")
    for i in range(0, len(words), 8):
        chunk = words[i:i+8]
        f.write("    " + ", ".join(f"0x{w:08x}u" for w in chunk) + ",\n")
    f.write("};\n")
    f.write(f"static constexpr size_t {symbol}_size = sizeof({symbol});\n")
