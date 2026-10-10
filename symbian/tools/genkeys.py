import re, sys
src = open(r"C:\Users\tavar\Downloads\HoneyCave2-JavaSE\src\mophun\MophunCrypt.java", encoding="utf-8", errors="replace").read()
blk = re.search(r"MODULI_LE\s*=\s*\{(.*?)\};", src, re.S).group(1)
# each modulus = the string literals between commas at top level
parts = [p for p in blk.split(",")]
moduli = []
for p in parts:
    chunks = re.findall(r'"([0-9a-fA-F]+)"', p)
    if not chunks: continue
    moduli.append("".join(chunks))
out = []
out.append("/* MophunKeys.h - GERADO por tools/genkeys.py a partir de MophunCrypt.java.")
out.append(" * Modulos RSA (1024 bits) das chaves Sony Ericsson, em ordem LITTLE-ENDIAN de")
out.append(" * bytes -> aqui ja convertidos para 32 limbs de 32 bits (limb 0 = menos")
out.append(" * significativo), que e o formato que o bignum usa. NAO editar a mao. */")
out.append("#ifndef MOPHUNKEYS_H")
out.append("#define MOPHUNKEYS_H")
out.append("")
out.append("#include \"vmcommon.h\"")
out.append("")
out.append("const int kMophunModulusCount = %d;" % len(moduli))
out.append("const int kMophunLimbs = 32;   /* 1024 bits */")
out.append("")
out.append("static const u32 kMophunModuli[%d][32] = {" % len(moduli))
for m in moduli:
    assert len(m) == 256, "modulo com %d digitos hex (esperado 256)" % len(m)
    b = bytes.fromhex(m)          # ja em ordem little-endian de bytes
    limbs = [int.from_bytes(b[i*4:i*4+4], "little") for i in range(32)]
    out.append("    {")
    for r in range(0, 32, 4):
        out.append("        " + " ".join("0x%08xu," % limbs[r+c] for c in range(4)))
    out.append("    },")
out.append("};")
out.append("")
out.append("#endif /* MOPHUNKEYS_H */")
open("inc/MophunKeys.h","w").write("\n".join(out) + "\n")
print("moduli:", len(moduli), "cada um com", [len(m) for m in moduli], "digitos hex -> inc/MophunKeys.h")
