/* MidiSeq.h - SEQUENCIADOR de Standard MIDI File. NAO sintetiza nada.
 *
 * O QUE ELE FAZ: le um SMF e entrega as mensagens MIDI (note on/off, program change,
 * controle, pitch bend) ja com o INSTANTE em microssegundos em que cada uma deve sair.
 * So isso. Quem produz o SOM e o sintetizador e o banco General MIDI DO PROPRIO
 * APARELHO -- igual a um toque de celular ou a um jogo J2ME. Nao ha onda quadrada,
 * nao ha soundfont nosso, nao ha emulacao de MIDI.
 *
 * E o mesmo desenho do emulador de PC, onde `Sequencer` conta o tempo e `Synthesizer`
 * (o do sistema) faz o som. No Symbian o papel do Synthesizer e do CMidiClientUtility
 * engatado (EClosedEngaged), que recebe NoteOnL/NoteOffL/SetInstrumentL ao vivo.
 *
 * POR QUE NAO ENTREGAR O ARQUIVO PRONTO AO APARELHO: no Symbian, carregar um recurso
 * MIDI e `OpenFile`, que sobe um processo controlador MMF inteiro por som -- demora
 * segundos, acumula memoria e, trocando de efeito no meio do jogo, derruba o processo.
 * O jogo mophun troca de efeito varias vezes por segundo. Contando o tempo aqui, o
 * motor do aparelho e aberto UMA vez na vida e nunca mais se mexe nele.
 *
 * Portavel: sem STL, sem libc, sem Symbian. Compila no g++ do PC (ha teste) e no GCCE.
 * Tudo em inteiros -- muitos S60 3rd nao tem unidade de ponto flutuante.
 */
#ifndef MIDISEQ_H
#define MIDISEQ_H

#include "vmcommon.h"

class MidiSeq
{
public:
    /* Uma mensagem MIDI de canal, com o instante absoluto desde o inicio da sequencia.
       `status` ja traz o canal nos 4 bits baixos. `size` e 2 ou 3 bytes no fio. */
    struct Msg
    {
        u32 us;         /* quando tocar, em microssegundos desde o inicio */
        u8  status;     /* 0x80..0xEF (nunca meta nem sysex: esses sao consumidos aqui) */
        u8  d1, d2;
        u8  size;       /* 2 = status+d1, 3 = status+d1+d2 */
    };

    /* Um SMF de musica pode ter muitos eventos; os efeitos do mophun tem dezenas. O teto
       protege a memoria do aparelho: passando disso, a sequencia e cortada (e melhor
       tocar o comeco do que recusar o som). */
    enum { KMaxEvents = 2048, KMaxTempos = 64 };

    MidiSeq();
    ~MidiSeq();

    /** Interpreta um SMF. false = nao e "MThd" valido, ou nao coube nada.
        Substitui o que estiver carregado. */
    bool Load(const u8* aSmf, int aLen);

    /** Esquece a sequencia e libera a memoria. */
    void Clear();

    bool Loaded() const { return iMsg != 0 && iCount > 0; }
    int  Count()  const { return iCount; }

    /** Duracao total, em microssegundos (ultimo evento; minimo 1). Usada para repetir. */
    u32 DurationUs() const { return iDurationUs; }

    /** Canais usados pela sequencia, 1 bit por canal. Serve para mandar "all notes off"
        so onde e preciso, em vez de varrer os 16. */
    u16 ChannelMask() const { return iChannelMask; }

    /** Proxima mensagem cujo instante ja chegou (us <= aNowUs), ou 0 se nao ha.
        Avanca o cursor: chame em laco ate devolver 0. */
    const Msg* Next(u32 aNowUs);

    /** Terminou de entregar tudo? */
    bool Finished() const { return iNext >= iCount; }

    /** Volta o cursor ao inicio (para repetir). Nao recarrega nada. */
    void Rewind() { iNext = 0; }

private:
    /* evento ainda em TICKS, antes de virar microssegundos */
    struct Raw { u32 tick; u32 order; u8 status, d1, d2, size; };

    static void MergeSort(Raw* aArr, Raw* aTmp, int aN);

    Msg* iMsg;
    int  iCount;
    int  iNext;
    u32  iDurationUs;
    u16  iChannelMask;
};

#endif /* MIDISEQ_H */
