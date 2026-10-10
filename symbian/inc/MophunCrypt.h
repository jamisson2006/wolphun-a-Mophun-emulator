/* MophunCrypt.h - decryption of Mophun ROMs (Sony Ericsson DRM).
 *
 * Ported from src/mophun/MophunCrypt.java, which in turn comes from JaGoTu's
 * MophunDecrypt. Same scheme as the original:
 *   - only the CODE section is encrypted, word by word, with a 32-bit XTEA;
 *   - the symmetric key (4x u32) is RSA-encrypted (exponent 3) inside the game's own
 *     "META" resource: key = (meta^3 mod N)[0x14 .. 0x14+16];
 *   - N is one of the two RSA moduli of the SE keys (inc/MophunKeys.h).
 *
 * The Java version used java.math.BigInteger; here there is a purpose-built 1024-bit
 * bignum, because no large-integer library exists on Symbian without OpenC. No STL,
 * no libc, no allocation: everything fits on the stack, so it behaves identically on
 * the phone's ARM and on a desktop g++.
 *
 * The original file is NEVER modified: decryption happens in the in-memory copy that
 * Vmgp already makes.
 */
#ifndef MOPHUNCRYPT_H
#define MOPHUNCRYPT_H

#include "vmcommon.h"

class MophunCrypt
{
public:
    /* DRM heuristic, the same one the PC emulator uses: a decrypted code section
       repeats a lot of byte values (opcodes plus immediates), while an encrypted one
       looks like noise. More than 190 distinct byte values in a 4 KB window means it
       is probably encrypted. */
    static bool looksEncrypted(const u8* d, int len);

    /* Is there an RSA modulus that validates this ROM's META? (i.e. can we decrypt) */
    static bool hasKey(const u8* d, int len);

    /* Decrypts the code section IN PLACE. Returns false if META was not found or no
       key fits (a ROM from another platform, or an unknown DRM). */
    static bool decryptInPlace(u8* d, int len);
};

#endif /* MOPHUNCRYPT_H */
