/* MidiSeq.cpp - see MidiSeq.h. An SMF sequencer: it turns the file into a list of MIDI
   messages timestamped in microseconds. No synthesis happens here. */
#include "MidiSeq.h"

/* ---------------- big-endian reads and variable-length quantities ---------------- */

static inline u32 rdBE32(const u8* d, int o)
{
    return ((u32)d[o] << 24) | ((u32)d[o+1] << 16) | ((u32)d[o+2] << 8) | (u32)d[o+3];
}
static inline int rdBE16(const u8* d, int o)
{
    return ((int)d[o] << 8) | (int)d[o+1];
}
/* VLQ: 7 useful bits per byte, bit 7 means "continues". At most 4 bytes per the SMF standard. */
static u32 readVarLen(const u8* d, int aEnd, int& p)
{
    u32 v = 0;
    for (int i = 0; i < 4 && p < aEnd; i++)
    {
        const u8 b = d[p++];
        v = (v << 7) | (u32)(b & 0x7f);
        if ((b & 0x80) == 0) break;
    }
    return v;
}

/* ---------------- life cycle ---------------- */

MidiSeq::MidiSeq()
: iMsg(0), iCount(0), iNext(0), iDurationUs(0), iChannelMask(0)
{
}

MidiSeq::~MidiSeq() { Clear(); }

void MidiSeq::Clear()
{
    delete[] iMsg; iMsg = 0;
    iCount = 0; iNext = 0; iDurationUs = 0; iChannelMask = 0;
}

/* A STABLE sort by tick. Stability matters because events from different tracks can
   land on the same tick and must keep the order they were read in -- a program change
   before the note on of the same track, for one. Merge sort: n log n, with no deep recursion. */
void MidiSeq::MergeSort(Raw* a, Raw* tmp, int n)
{
    for (int w = 1; w < n; w *= 2)
    {
        for (int i = 0; i < n; i += 2*w)
        {
            int l = i, lEnd = i + w, r = i + w, rEnd = i + 2*w, k = i;
            if (lEnd > n) lEnd = n;
            if (r    > n) r    = n;
            if (rEnd > n) rEnd = n;
            while (l < lEnd && r < rEnd)
            {
                /* <= preserves stability: on a tie the left-hand one wins */
                if (a[l].tick <= a[r].tick) tmp[k++] = a[l++];
                else                        tmp[k++] = a[r++];
            }
            while (l < lEnd) tmp[k++] = a[l++];
            while (r < rEnd) tmp[k++] = a[r++];
        }
        for (int i = 0; i < n; i++) a[i] = tmp[i];
    }
}

/* ---------------- parsing the SMF ---------------- */

bool MidiSeq::Load(const u8* smf, int len)
{
    Clear();

    if (!smf || len < 14) return false;
    if (smf[0]!='M' || smf[1]!='T' || smf[2]!='h' || smf[3]!='d') return false;

    const u32 hdrLen = rdBE32(smf, 4);
    if (hdrLen < 6 || (int)(8 + hdrLen) > len) return false;

    const int ntracks = rdBE16(smf, 10);
    int division      = rdBE16(smf, 12);
    /* SMPTE division (bit 15) does not appear in Mophun's resources; falling back to a
       default beats refusing the sound. */
    if (division & 0x8000) division = 96;
    if (division <= 0)     division = 96;

    Raw* raw = new Raw[KMaxEvents];
    if (!raw) return false;
    int nRaw = 0;

    /* the tempo map: (tick, microseconds per quarter note). The MIDI default is 120 BPM. */
    u32 tempoTick[KMaxTempos];
    u32 tempoUs[KMaxTempos];
    int nTempo = 0;

    u32 order = 0;        /* global read order, used by the stable sort */
    u32 maxTick = 0;

    int p = 8 + (int)hdrLen;
    for (int t = 0; t < ntracks && p + 8 <= len; t++)
    {
        if (smf[p]!='M' || smf[p+1]!='T' || smf[p+2]!='r' || smf[p+3]!='k') break;
        const u32 tlen = rdBE32(smf, p + 4);
        int q   = p + 8;
        int end = q + (int)tlen;
        if (end > len) end = len;       /* a truncated track: read what there is */
        p = end;

        u32 tick = 0;
        u8  running = 0;

        while (q < end)
        {
            tick += readVarLen(smf, end, q);
            if (q >= end) break;

            u8 status = smf[q];
            if (status & 0x80) { q++; }
            else               { status = running; }    /* running status */
            if (status == 0) break;                     /* corrupt stream */

            if (status == 0xFF)                         /* meta event */
            {
                if (q >= end) break;
                const u8 type = smf[q++];
                const u32 mlen = readVarLen(smf, end, q);
                if (type == 0x51 && mlen == 3 && q + 3 <= end && nTempo < KMaxTempos)
                {
                    tempoTick[nTempo] = tick;
                    tempoUs[nTempo]   = ((u32)smf[q] << 16) | ((u32)smf[q+1] << 8) | (u32)smf[q+2];
                    if (tempoUs[nTempo] == 0) tempoUs[nTempo] = 500000;
                    nTempo++;
                }
                q += (int)mlen;
                if (type == 0x2F) break;                /* end of track */
                continue;
            }
            if (status == 0xF0 || status == 0xF7)       /* sysex: the phone does not need it */
            {
                const u32 slen = readVarLen(smf, end, q);
                q += (int)slen;
                continue;
            }

            running = status;
            const u8 hi = (u8)(status & 0xF0);
            const int need = (hi == 0xC0 || hi == 0xD0) ? 1 : 2;
            if (q + need > end) break;

            const u8 d1 = smf[q];
            const u8 d2 = (need == 2) ? smf[q+1] : 0;
            q += need;

            if (nRaw < KMaxEvents)
            {
                raw[nRaw].tick   = tick;
                raw[nRaw].order  = order++;
                raw[nRaw].status = status;
                raw[nRaw].d1     = d1;
                raw[nRaw].d2     = d2;
                raw[nRaw].size   = (u8)(need + 1);
                nRaw++;
                iChannelMask = (u16)(iChannelMask | (1u << (status & 0x0F)));
            }
            if (tick > maxTick) maxTick = tick;
        }
    }

    if (nRaw == 0){ delete[] raw; return false; }

    /* The tracks arrive concatenated, each sorted within itself; the stable sort by
       tick interleaves them correctly (SMF format 1 means parallel tracks). */
    if (nRaw > 1)
    {
        Raw* tmp = new Raw[nRaw];
        if (tmp){ MergeSort(raw, tmp, nRaw); delete[] tmp; }
        /* No memory for the scratch buffer: carry on in read order. Format 0, a single
           track, is already correct, and that is the common case for Mophun's effects. */
    }

    /* the tempo map usually sits entirely in track 0 and is already ordered; make sure anyway */
    for (int i = 1; i < nTempo; i++)
    {
        const u32 kt = tempoTick[i], ku = tempoUs[i];
        int j = i - 1;
        while (j >= 0 && tempoTick[j] > kt){ tempoTick[j+1]=tempoTick[j]; tempoUs[j+1]=tempoUs[j]; j--; }
        tempoTick[j+1] = kt; tempoUs[j+1] = ku;
    }

    iMsg = new Msg[nRaw];
    if (!iMsg){ delete[] raw; return false; }

    /* ticks -> microseconds, applying the tempo changes along the way.
       us += deltaTicks * usPerQuarter / ticksPerQuarter, in 64 bits: the product
       overflows 32 bits easily -- 1000 ticks x 500000 us already exceeds 2^31. */
    u64 usAcc   = 0;
    u32 lastTick = 0;
    u32 tempo    = 500000;      /* 120 BPM, the SMF default when there is no FF 51 */
    int ti       = 0;

    for (int i = 0; i < nRaw; i++)
    {
        const u32 tk = raw[i].tick;
        while (ti < nTempo && tempoTick[ti] <= tk)
        {
            usAcc   += ((u64)(tempoTick[ti] - lastTick) * (u64)tempo) / (u64)division;
            lastTick = tempoTick[ti];
            tempo    = tempoUs[ti];
            ti++;
        }
        const u64 us = usAcc + ((u64)(tk - lastTick) * (u64)tempo) / (u64)division;

        iMsg[i].us     = (us > 0xFFFFFFFFULL) ? 0xFFFFFFFFu : (u32)us;
        iMsg[i].status = raw[i].status;
        iMsg[i].d1     = raw[i].d1;
        iMsg[i].d2     = raw[i].d2;
        iMsg[i].size   = raw[i].size;
    }

    /* duracao = ate o fim da faixa (maxTick), nao so ate o ultimo evento: um SMF pode ter
       silencio no fim, e repetir cedo demais atropelaria a cauda. */
    while (ti < nTempo && tempoTick[ti] <= maxTick)
    {
        usAcc   += ((u64)(tempoTick[ti] - lastTick) * (u64)tempo) / (u64)division;
        lastTick = tempoTick[ti];
        tempo    = tempoUs[ti];
        ti++;
    }
    {
        const u64 dur = usAcc + ((u64)(maxTick - lastTick) * (u64)tempo) / (u64)division;
        iDurationUs = (dur > 0xFFFFFFFFULL) ? 0xFFFFFFFFu : (u32)dur;
        if (iDurationUs == 0) iDurationUs = 1;
    }

    iCount = nRaw;
    iNext  = 0;
    delete[] raw;
    return true;
}

const MidiSeq::Msg* MidiSeq::Next(u32 aNowUs)
{
    if (!iMsg || iNext >= iCount) return 0;
    if (iMsg[iNext].us > aNowUs) return 0;
    return &iMsg[iNext++];
}
