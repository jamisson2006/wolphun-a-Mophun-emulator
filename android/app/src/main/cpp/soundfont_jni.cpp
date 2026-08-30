// Ponte JNI para tocar MIDI (SMF) usando um SoundFont .sf2, via TinySoundFont (tsf.h)
// + TinyMidiLoader (tml.h). Renderiza PCM 16-bit stereo @ 44100 em tempo real; a camada
// Java (SoundFontSynth) alimenta um AudioTrack. Baseado no fluxo de referencia do TSF/JL-Mod.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#define TSF_IMPLEMENTATION
#include "tsf.h"
#define TML_IMPLEMENTATION
#include "tml.h"

#define OUT_RATE 44100

struct Player {
    tsf* sf;
    tml_message* midiFirst;
    tml_message* midiCurrent;
    double msec;
    unsigned int lengthMs;
    int loop;
};

extern "C" {

// cria o synth a partir de um .sf2 (caminho real). Retorna ponteiro (0 = falhou).
JNIEXPORT jlong JNICALL
Java_com_manedsoftware_wolphun_SoundFontSynth_nativeCreate(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, 0);
    tsf* sf = tsf_load_filename(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!sf) return 0;
    tsf_set_output(sf, TSF_STEREO_INTERLEAVED, OUT_RATE, 0.0f);
    Player* p = (Player*)calloc(1, sizeof(Player));
    if (!p) { tsf_close(sf); return 0; }
    p->sf = sf;
    return (jlong)(intptr_t)p;
}

// carrega um novo SMF (bytes) e reinicia a reproducao. loop = repetir infinito.
JNIEXPORT void JNICALL
Java_com_manedsoftware_wolphun_SoundFontSynth_nativeSetMidi(JNIEnv* env, jclass, jlong ptr, jbyteArray data, jboolean loop) {
    Player* p = (Player*)(intptr_t)ptr;
    if (!p) return;
    if (p->midiFirst) { tml_free(p->midiFirst); p->midiFirst = 0; }
    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, 0);
    p->midiFirst = tml_load_memory(buf, (int)len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    p->midiCurrent = p->midiFirst;
    p->msec = 0;
    p->loop = loop ? 1 : 0;
    p->lengthMs = 0;
    if (p->midiFirst) tml_get_info(p->midiFirst, 0, 0, 0, 0, &p->lengthMs);
    tsf_reset(p->sf);
    // Canal 9 (10 em General MIDI) e SEMPRE percussao/drums (bank 128). O TSF, diferente do
    // Gervill (desktop), NAO assume isso sozinho -> sem isto, notas de bateria no canal 9 (ex.: o
    // "tiro" do HC2 = nota 35 bumbo) tocavam com PIANO. Fixa o kit de drums padrao no canal 9.
    tsf_channel_set_presetnumber(p->sf, 9, 0, 1);
}

// renderiza 'frames' amostras (stereo interleaved) em out. Retorna 1 se ainda tocando, 0 se terminou.
JNIEXPORT jint JNICALL
Java_com_manedsoftware_wolphun_SoundFontSynth_nativeRender(JNIEnv* env, jclass, jlong ptr, jshortArray out, jint frames) {
    Player* p = (Player*)(intptr_t)ptr;
    if (!p || !p->sf) return 0;
    jshort* buffer = env->GetShortArrayElements(out, 0);
    int playing = 1;
    if (p->midiFirst) {
        p->msec += frames * (1000.0 / OUT_RATE);
        for (; p->midiCurrent && p->msec >= p->midiCurrent->time; p->midiCurrent = p->midiCurrent->next) {
            tml_message* m = p->midiCurrent;
            switch (m->type) {
                case TML_PROGRAM_CHANGE: tsf_channel_set_presetnumber(p->sf, m->channel, m->program, (m->channel == 9)); break;
                case TML_NOTE_ON:        tsf_channel_note_on(p->sf, m->channel, m->key, m->velocity / 127.0f); break;
                case TML_NOTE_OFF:       tsf_channel_note_off(p->sf, m->channel, m->key); break;
                case TML_PITCH_BEND:     tsf_channel_set_pitchwheel(p->sf, m->channel, m->pitch_bend); break;
                case TML_CONTROL_CHANGE: tsf_channel_midi_control(p->sf, m->channel, m->control, m->control_value); break;
                default: break;
            }
        }
        if (!p->midiCurrent && p->msec >= p->lengthMs) {   // fim do MIDI
            if (p->loop) { p->midiCurrent = p->midiFirst; p->msec = 0; tsf_reset(p->sf); }
            else playing = 0;
        }
    } else {
        playing = 0;
    }
    tsf_render_short(p->sf, buffer, frames, 0);            // 0 = sobrescreve o buffer
    env->ReleaseShortArrayElements(out, buffer, 0);        // 0 = copia de volta p/ o Java
    return playing;
}

// para o som atual (mantem o synth vivo).
JNIEXPORT void JNICALL
Java_com_manedsoftware_wolphun_SoundFontSynth_nativeStop(JNIEnv*, jclass, jlong ptr) {
    Player* p = (Player*)(intptr_t)ptr;
    if (!p || !p->sf) return;
    p->midiCurrent = 0;
    tsf_note_off_all(p->sf);
}

// libera tudo.
JNIEXPORT void JNICALL
Java_com_manedsoftware_wolphun_SoundFontSynth_nativeDestroy(JNIEnv*, jclass, jlong ptr) {
    Player* p = (Player*)(intptr_t)ptr;
    if (!p) return;
    if (p->midiFirst) tml_free(p->midiFirst);
    if (p->sf) tsf_close(p->sf);
    free(p);
}

}
