/* MophunKeys.h - GERADO por tools/genkeys.py a partir de MophunCrypt.java.
 * Modulos RSA (1024 bits) das chaves Sony Ericsson, em ordem LITTLE-ENDIAN de
 * bytes -> aqui ja convertidos para 32 limbs de 32 bits (limb 0 = menos
 * significativo), que e o formato que o bignum usa. NAO editar a mao. */
#ifndef MOPHUNKEYS_H
#define MOPHUNKEYS_H

#include "vmcommon.h"

const int kMophunModulusCount = 2;
const int kMophunLimbs = 32;   /* 1024 bits */

static const u32 kMophunModuli[2][32] = {
    {
        0x7cfc95fbu, 0xfe1954c4u, 0xa90a6e73u, 0x43d20026u,
        0xfa58be05u, 0xddbec6afu, 0xc09d01e6u, 0xbfde43f2u,
        0xf07ac57fu, 0xf0664605u, 0x1e0716a2u, 0xde6a050cu,
        0x2e435d36u, 0xba2bcdccu, 0x1ab1ab19u, 0x083992f7u,
        0xcdad723du, 0x967306efu, 0x0ce0368eu, 0x2a586272u,
        0x662e5b2cu, 0x0f0c1fe5u, 0x77fc8bb9u, 0x90e3d14du,
        0x2f3a4572u, 0xbc1a7fbau, 0x8d00c393u, 0xb3e58735u,
        0x77197b8du, 0xc535ccd2u, 0x53d145d0u, 0xde0882a5u,
    },
    {
        0x71eaf703u, 0x23685113u, 0x3466ec3au, 0xfcc40c8fu,
        0x87da31eau, 0x17ff0de4u, 0xe3a92413u, 0x8081fc27u,
        0xdb6f351fu, 0x66ff932cu, 0x82b4b1adu, 0xaba13ef3u,
        0x829d82a4u, 0xcd9ce21bu, 0x61b1bcc0u, 0x58c128ebu,
        0xb881f275u, 0xa5b6cf81u, 0xbeae482du, 0x368bd5d5u,
        0x28e9605au, 0xd39fd6c2u, 0xb26069a6u, 0x11d56d29u,
        0xdaaafdf6u, 0xb54d13f0u, 0x21fb9568u, 0x8bc99182u,
        0x6b13ed63u, 0x2189f41du, 0x87569b26u, 0xef24e0adu,
    },
};

#endif /* MOPHUNKEYS_H */
