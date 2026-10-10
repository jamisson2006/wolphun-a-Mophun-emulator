/* VSound.cpp - ver VSound.h */
#include "VSound.h"
#include "Pip2.h"

/* ---------------- tabelas do IMA ADPCM (SNDFORMAT_ADPCM = 2) ---------------- */
static const int IMA_STEP[89] = {
    7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,
    130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,
    1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,
    5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,
    27086,29794,32767 };
static const int IMA_IDX[16] = { -1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8 };

static inline int clampi(int v, int lo, int hi){ return v < lo ? lo : (v > hi ? hi : v); }

VSound::VSound(Pip2* aVm)
: enabled(true), sndLoaded(0), playCount(0), beepCount(0),
  vm(aVm), nextHandle(1), masterVol(127), started(false),
  beepFreq(0), beepDur(0)
{
    for (int i = 0; i < KMaxSounds; i++){ sounds[i].pcm = 0; sounds[i].handle = 0; }
    for (int i = 0; i < KMaxVoices; i++){ voices[i].snd = 0; voices[i].handle = 0; voices[i].playing = false; }
    beepSnd.pcm = 0; beepSnd.len = 0; beepSnd.handle = 0;
}

VSound::~VSound()
{
    for (int i = 0; i < KMaxSounds; i++) freeSound(&sounds[i]);
    delete[] beepSnd.pcm; beepSnd.pcm = 0;
}

void VSound::freeSound(Snd* s)
{
    if (!s) return;
    delete[] s->pcm; s->pcm = 0;
    s->len = 0; s->handle = 0;
}

VSound::Snd* VSound::findSound(int h)
{
    for (int i = 0; i < KMaxSounds; i++)
        if (sounds[i].handle == h && sounds[i].pcm) return &sounds[i];
    return 0;
}

VSound::Voice* VSound::findVoice(int h)
{
    for (int i = 0; i < KMaxVoices; i++)
        if (voices[i].handle == h) return &voices[i];
    return 0;
}

/* a voz deste handle, ou uma livre. Se nao houver livre, rouba a que nao esta tocando;
   em ultimo caso a primeira -- melhor cortar um som velho que ignorar o novo. */
VSound::Voice* VSound::takeVoice(int h)
{
    Voice* v = findVoice(h);
    if (v) return v;
    for (int i = 0; i < KMaxVoices; i++) if (voices[i].handle == 0) { v = &voices[i]; break; }
    if (!v) for (int i = 0; i < KMaxVoices; i++) if (!voices[i].playing) { v = &voices[i]; break; }
    if (!v) v = &voices[0];
    v->handle = h; v->snd = 0; v->playing = false;
    v->vol = 127; v->pan = 0; v->loop = 0; v->pos = 0; v->step = 1 << 16;
    return v;
}

int VSound::init()
{
    if (!enabled) return -1;
    started = true;
    return 0;
}

int VSound::dispose()
{
    for (int i = 0; i < KMaxVoices; i++){ voices[i].playing = false; voices[i].handle = 0; voices[i].snd = 0; }
    for (int i = 0; i < KMaxSounds; i++) freeSound(&sounds[i]);
    started = false;
    return 0;
}

int VSound::disposeHandle(int h)
{
    Voice* v = findVoice(h);
    if (v){ v->playing = false; v->handle = 0; v->snd = 0; }
    Snd* s = findSound(h);
    if (s) freeSound(s);
    return 0;
}

bool VSound::busy() const
{
    for (int i = 0; i < KMaxVoices; i++) if (voices[i].playing) return true;
    return false;
}

/* ---------------- carga de um som (NativeSoundHeader) ---------------- */
int VSound::getHandle(int dataPtr)
{
    if (!enabled || !vm) return -1;

    const int format    = vm->read32(dataPtr + 8);
    int       loopStart = vm->read32(dataPtr + 12);
    int       loopEnd   = vm->read32(dataPtr + 16);
    int       freq      = vm->read32(dataPtr + 20);
    const int bits      = vm->read8 (dataPtr + 24);
    const int bodySize  = vm->read32(dataPtr + 32);
    const int bodyOff   = dataPtr + 36;

    if (bodySize <= 0 || bodySize > (4 << 20)) return -1;
    if (freq <= 0) freq = 8000;

    i16* pcm = 0;
    int  len = 0;
    if (format == 2)
    {
        pcm = decodeImaAdpcm(bodyOff, bodySize, &len);
    }
    else if (bits == 16)
    {
        len = bodySize / 2;
        if (len <= 0) return -1;
        pcm = new i16[len];
        if (!pcm) return -1;
        for (int i = 0; i < len; i++)
            pcm[i] = (i16)((vm->read8(bodyOff + i*2) & 0xff) | (vm->read8(bodyOff + i*2 + 1) << 8));
    }
    else
    {
        /* 8 bits SEM sinal: silencio e 128, nao 0. Tratar como com sinal da aquele
           chiado alto que parece defeito do emulador. */
        len = bodySize;
        pcm = new i16[len];
        if (!pcm) return -1;
        for (int i = 0; i < len; i++)
            pcm[i] = (i16)((( vm->read8(bodyOff + i) & 0xff) - 128) << 8);
    }
    if (!pcm || len <= 0){ delete[] pcm; return -1; }

    /* acha um lugar livre */
    Snd* s = 0;
    for (int i = 0; i < KMaxSounds; i++) if (!sounds[i].pcm){ s = &sounds[i]; break; }
    if (!s){ delete[] pcm; return -1; }

    s->pcm       = pcm;
    s->len       = len;
    s->freq      = freq;
    s->loopStart = (loopStart >= 0 && loopStart < len) ? loopStart : 0;
    s->loopEnd   = (loopEnd   >  0 && loopEnd  <= len) ? loopEnd   : len;
    s->handle    = nextHandle++;
    if (nextHandle >= KBeepHandle) nextHandle = 1;
    sndLoaded++;
    return s->handle;
}

/* ---------------- controles (SNDCTRL) ---------------- */
int VSound::ctrl(int handle, int control, int param)
{
    Snd*   s = findSound(handle);
    Voice* v = findVoice(handle);

    switch (control)
    {
        case 1:                                     /* PLAY */
        {
            if (!s || !enabled) return 0;
            v = takeVoice(handle);
            v->snd  = s;
            v->pos  = ((i64)s->loopStart) << 16;
            v->step = ((i64)s->freq << 16) / KOutRate;
            if (v->step <= 0) v->step = 1;
            {
                const int lc = param & 0xffff;      /* bits 0-15 = repeticoes; 0xFFFF = infinito */
                v->loop = (lc == 0xffff) ? -1 : (lc > 0 ? lc : 0);
            }
            v->playing = true;
            playCount++;
            return 0;
        }
        case 2:                                     /* STOP */
        case 3:  if (v) v->playing = false; return 0;   /* PAUSE */
        case 4:  if (v && enabled) v->playing = true; return 0;   /* RESUME */
        case 5:                                     /* VOLUME 0..127 (-1 = ler) */
            if (v){ if (param == -1) return v->vol; v->vol = clampi(param, 0, 127); }
            return 0;
        case 6:                                     /* FREQ: pitch em Hz */
            if (v && param > 0){ v->step = ((i64)param << 16) / KOutRate; if (v->step <= 0) v->step = 1; }
            return 0;
        case 7:                                     /* PAN -127..127 (-1 = ler) */
            if (v){ if (param == -1) return v->pan; v->pan = clampi(param, -127, 127); }
            return 0;
        case 8:  return (v && v->playing) ? 1 : 0;  /* STATUS */
        case 9:  if (v && v->loop < 0) v->loop = 0; return 0;   /* STOPLOOPING */
        case 12:                                    /* MASTERVOLUME */
            if (param != -1) masterVol = clampi(param, 0, 127);
            return masterVol;
        default: return 0;
    }
}

/* ---------------- vBeep ----------------
   vBeep(freq, duracao) toca UM tom. Ha jogos cujo audio e SO isso (Mooktown Tennis,
   Alpine Ski), entao deixar como no-op e deixar o jogo mudo. Sintetizamos uma onda
   quadrada -- o timbre de buzzer do aparelho -- e tocamos pelo mixer que ja existe. */
void VSound::beep(int freq, int durMs)
{
    if (!enabled) return;
    if (freq <= 0 || durMs <= 0) return;
    if (freq > KOutRate/2) freq = KOutRate/2;      /* acima de Nyquist so gera alias */
    if (durMs > 5000) durMs = 5000;

    if (freq != beepFreq || durMs != beepDur || !beepSnd.pcm)
    {
        const int n = (int)(((i64)KOutRate * durMs) / 1000);
        if (n <= 0) return;
        i16* pcm = new i16[n];
        if (!pcm) return;
        /* periodo em amostras, ponto fixo 16.16 */
        const i64 period = ((i64)KOutRate << 16) / freq;
        const int ramp = KOutRate / 500;           /* 2 ms de rampa p/ nao estalar */
        for (int i = 0; i < n; i++)
        {
            const i64 ph = (((i64)i << 16) % period);
            int val = (ph * 2 < period) ? 9000 : -9000;
            if (ramp > 0)
            {
                if (i < ramp)          val = val * i / ramp;
                else if (i > n - ramp) val = val * (n - i) / ramp;
            }
            pcm[i] = (i16)val;
        }
        delete[] beepSnd.pcm;
        beepSnd.pcm = pcm; beepSnd.len = n; beepSnd.freq = KOutRate;
        beepSnd.loopStart = 0; beepSnd.loopEnd = n; beepSnd.handle = KBeepHandle;
        beepFreq = freq; beepDur = durMs;
    }

    Voice* v = takeVoice(KBeepHandle);
    v->snd = &beepSnd;
    v->pos = 0; v->step = 1 << 16; v->loop = 0;
    v->vol = 127; v->pan = 0; v->playing = true;
    beepCount++;
    started = true;                                /* jogos beepam sem chamar vSoundInit */
}

/* ---------------- mixer ----------------
   Mistura as vozes ativas em PCM16 estereo intercalado. Reamostragem com INTERPOLACAO
   LINEAR: sons de 8/11 kHz tocando a 22 kHz com vizinho-mais-proximo geram um alias bem
   audivel (chiado); a interpolacao corta boa parte disso. */
void VSound::RenderFrames(i16* dst, int frames)
{
    if (!dst || frames <= 0) return;
    for (int i = 0; i < frames * 2; i++) dst[i] = 0;
    if (!enabled) return;

    for (int vi = 0; vi < KMaxVoices; vi++)
    {
        Voice* v = &voices[vi];
        if (!v->playing || !v->snd || !v->snd->pcm) continue;
        const Snd* s = v->snd;

        /* ganho por canal em 0..256 (inteiro: nada de float no laco de amostra) */
        const int g  = (v->vol * masterVol * 256) / (127 * 127);
        const int gl = (v->pan > 0) ? (g * (127 - v->pan)) / 127 : g;
        const int gr = (v->pan < 0) ? (g * (127 + v->pan)) / 127 : g;

        const i64 loopEndFx   = ((i64)s->loopEnd)   << 16;
        const i64 loopStartFx = ((i64)s->loopStart) << 16;

        for (int i = 0; i < frames; i++)
        {
            if (v->pos >= loopEndFx)
            {
                if (v->loop != 0)
                {
                    if (v->loop > 0) v->loop--;
                    v->pos = loopStartFx + (v->pos - loopEndFx);
                    if (v->pos < loopStartFx || v->pos >= loopEndFx) v->pos = loopStartFx;
                }
                else { v->playing = false; break; }
            }

            const int ip = (int)(v->pos >> 16);
            int sm = 0;
            if (ip >= 0 && ip < s->len)
            {
                const int frac = (int)(v->pos & 0xffff);
                const int i1   = ip + 1;
                const int s0   = s->pcm[ip];
                const int s1   = (i1 < s->loopEnd && i1 < s->len)
                                   ? s->pcm[i1]
                                   : (v->loop != 0 ? s->pcm[s->loopStart] : s0);
                sm = s0 + (((s1 - s0) * frac) >> 16);
            }

            int l = dst[i*2]     + ((sm * gl) >> 8);
            int r = dst[i*2 + 1] + ((sm * gr) >> 8);
            dst[i*2]     = (i16)clampi(l, -32768, 32767);
            dst[i*2 + 1] = (i16)clampi(r, -32768, 32767);

            v->pos += v->step;
        }
    }
}

/* ---------------- IMA ADPCM ----------------
   2 amostras por byte, NIBBLE ALTO primeiro (variante do mophun). */
i16* VSound::decodeImaAdpcm(int bodyOff, int bodySize, int* outLen)
{
    const int n = bodySize * 2;
    i16* out = new i16[n];
    if (!out){ if (outLen) *outLen = 0; return 0; }

    int pred = 0, idx = 0, o = 0;
    for (int i = 0; i < bodySize; i++)
    {
        const int by = vm->read8(bodyOff + i) & 0xff;
        for (int half = 0; half < 2; half++)
        {
            const int nib  = (half == 0) ? (by >> 4) : (by & 0x0f);
            const int step = IMA_STEP[idx];
            int diff = step >> 3;
            if (nib & 1) diff += step >> 2;
            if (nib & 2) diff += step >> 1;
            if (nib & 4) diff += step;
            if (nib & 8) pred -= diff; else pred += diff;
            pred = clampi(pred, -32768, 32767);
            idx  = clampi(idx + IMA_IDX[nib], 0, 88);
            out[o++] = (i16)pred;
        }
    }
    if (outLen) *outLen = n;
    return out;
}
