/* midtest.cpp - a desktop test for the SMF sequencer (MidiSeq), with no Symbian.
 *
 *   g++ -fwrapv -O2 -Iinc -o test/midtest test/midtest.cpp src/vm/MidiSeq.cpp
 *   test/midtest file1.mid file2.mid ...
 *
 * For each file it prints how many messages came out, the duration, the channels used
 * and the list of events timestamped in milliseconds -- enough to check by eye that the
 * tempo is right and that every note on has its note off.
 */
#include <stdio.h>
#include <stdlib.h>
#include "MidiSeq.h"

static const char* nomeStatus(u8 s)
{
    switch (s & 0xF0)
    {
        case 0x80: return "note off";
        case 0x90: return "note on ";
        case 0xA0: return "aftertch";
        case 0xB0: return "controle";
        case 0xC0: return "programa";
        case 0xD0: return "pressao ";
        case 0xE0: return "pitchbnd";
        default:   return "???     ";
    }
}

int main(int argc, char** argv)
{
    if (argc < 2){ printf("uso: midtest <arquivo.mid> [...]\n"); return 2; }

    int falhas = 0;
    for (int a = 1; a < argc; a++)
    {
        FILE* f = fopen(argv[a], "rb");
        if (!f){ printf("%s: nao abriu\n", argv[a]); falhas++; continue; }
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        unsigned char* buf = (unsigned char*)malloc(n);
        if (fread(buf, 1, n, f) != (size_t)n){ printf("%s: leitura curta\n", argv[a]); fclose(f); free(buf); falhas++; continue; }
        fclose(f);

        MidiSeq seq;
        const bool ok = seq.Load(buf, (int)n);
        printf("\n=== %s  (%ld bytes)\n", argv[a], n);
        if (!ok){ printf("    RECUSADO (nao e SMF valido)\n"); falhas++; free(buf); continue; }

        printf("    mensagens=%d  duracao=%u us (%.2f s)  canais=0x%04X\n",
               seq.Count(), seq.DurationUs(), seq.DurationUs()/1e6, seq.ChannelMask());

        /* Check the note balance: every note turned on has to be turned off, otherwise
           it stays stuck playing forever -- that was the symptom behind the "shot stuck
           in a loop". */
        int ligadas[16][128];
        for (int c = 0; c < 16; c++) for (int k = 0; k < 128; k++) ligadas[c][k] = 0;
        int nOn = 0, nOff = 0;

        u32 agora = 0;
        const MidiSeq::Msg* m;
        int linhas = 0;
        while (true)
        {
            m = seq.Next(agora);
            if (!m)
            {
                if (seq.Finished()) break;
                agora += 1000;                  /* advance 1 ms and try again */
                if (agora > 120u*1000u*1000u) break;   /* safety stop: 2 minutes */
                continue;
            }
            const int ch = m->status & 0x0F;
            if ((m->status & 0xF0) == 0x90 && m->d2 > 0){ ligadas[ch][m->d1]++; nOn++; }
            else if ((m->status & 0xF0) == 0x80 || ((m->status & 0xF0) == 0x90 && m->d2 == 0))
                   { ligadas[ch][m->d1]--; nOff++; }

            if (linhas < 14)
            {
                printf("    %7u ms  ch%-2d %s  %3d %3d\n",
                       m->us/1000, ch, nomeStatus(m->status), m->d1, m->d2);
                linhas++;
            }
            else if (linhas == 14){ printf("    ... (+%d)\n", seq.Count()-14); linhas++; }
        }

        int presas = 0;
        for (int c = 0; c < 16; c++) for (int k = 0; k < 128; k++) if (ligadas[c][k] > 0) presas++;
        printf("    note-on=%d note-off=%d  -> notas PRESAS: %d %s\n",
               nOn, nOff, presas, presas ? "<<< RUIM" : "(ok)");
        if (presas) falhas++;

        free(buf);
    }

    printf("\n%s\n", falhas ? "HOUVE FALHA" : "TODOS OK");
    return falhas ? 1 : 0;
}
