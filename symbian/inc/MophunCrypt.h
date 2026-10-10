/* MophunCrypt.h - descriptografia de ROMs mophun (DRM Sony Ericsson).
 *
 * Portado de src/mophun/MophunCrypt.java (que por sua vez vem do MophunDecrypt do
 * JaGoTu). Igual ao original:
 *   - so a secao de CODIGO e cifrada, palavra a palavra, por um XTEA de 32 bits;
 *   - a chave simetrica (4x u32) esta cifrada com RSA (expoente 3) dentro do recurso
 *     "META" do proprio jogo: chave = (meta^3 mod N)[0x14 .. 0x14+16];
 *   - N e um dos dois modulos RSA das chaves SE (inc/MophunKeys.h).
 *
 * O Java usava java.math.BigInteger; aqui ha um bignum proprio de 1024 bits (nenhuma
 * biblioteca grande existe no Symbian sem OpenC). Sem STL, sem libc, sem alocacao:
 * tudo cabe na pilha, entao roda igual no ARM do aparelho e no g++ do PC.
 *
 * O arquivo original NUNCA e alterado: a decifragem acontece na copia em memoria que
 * o Vmgp ja faz.
 */
#ifndef MOPHUNCRYPT_H
#define MOPHUNCRYPT_H

#include "vmcommon.h"

class MophunCrypt
{
public:
    /* Heuristica de DRM, a mesma do emulador de PC: a secao de codigo decifrada tem
       muitos bytes repetidos (opcodes + imediatos), a cifrada parece ruido. Mais de
       190 valores de byte distintos numa janela de 4 KB => provavelmente cifrada. */
    static bool looksEncrypted(const u8* d, int len);

    /* Ha um modulo RSA que valida o META deste ROM? (ou seja, da pra decifrar) */
    static bool hasKey(const u8* d, int len);

    /* Decifra a secao de codigo NO PROPRIO BUFFER. Devolve false se nao achou o META
       ou nenhuma chave serve (ROM de outra plataforma, ou DRM desconhecido). */
    static bool decryptInPlace(u8* d, int len);
};

#endif /* MOPHUNCRYPT_H */
