/* MidiSeq.h - a Standard MIDI File SEQUENCER. It synthesises nothing.
 *
 * WHAT IT DOES: reads an SMF and hands out the MIDI messages (note on/off, program
 * change, controller, pitch bend), each already tagged with the MOMENT, in
 * microseconds, at which it should be sent. That is all. The SOUND is produced by THE
 * PHONE'S OWN synthesizer and General MIDI bank -- exactly like a ringtone or a J2ME
 * game. There is no square wave, no soundfont of ours, no MIDI emulation.
 *
 * Same design as the PC emulator, where `Sequencer` keeps time and the system's
 * `Synthesizer` makes the sound. On Symbian the Synthesizer's role belongs to an
 * engaged CMidiClientUtility (EClosedEngaged), fed NoteOnL/NoteOffL/SetInstrumentL live.
 *
 * WHY NOT HAND THE WHOLE FILE TO THE PHONE: on Symbian, loading a MIDI resource means
 * `OpenFile`, which spins up an entire MMF controller process per sound -- it takes
 * seconds, piles up memory, and swapping effects mid-game brings the process down. A
 * Mophun game swaps effects several times per second. By keeping time here, the phone's
 * engine is opened ONCE for the whole session and never touched again.
 *
 * Portable: no STL, no libc, no Symbian. Builds with a desktop g++ (there is a test) and
 * with GCCE. All integer -- many S60 3rd devices have no floating-point unit.
 */
#ifndef MIDISEQ_H
#define MIDISEQ_H

#include "vmcommon.h"

class MidiSeq
{
public:
    /* One MIDI channel message, with its absolute time from the start of the sequence.
       `status` already carries the channel in its low 4 bits. `size` is the 2 or 3 bytes
       that go on the wire. */
    struct Msg
    {
        u32 us;         /* when to play it, in microseconds from the start */
        u8  status;     /* 0x80..0xEF; never meta or sysex, those are consumed here */
        u8  d1, d2;
        u8  size;       /* 2 = status+d1, 3 = status+d1+d2 */
    };

    /* A music SMF can hold a lot of events; Mophun's effects hold dozens. The ceiling
       protects the phone's memory: beyond it the sequence is truncated, on the grounds
       that playing the beginning beats refusing the sound outright. */
    enum { KMaxEvents = 2048, KMaxTempos = 64 };

    MidiSeq();
    ~MidiSeq();

    /** Parses an SMF. false means it is not a valid "MThd", or that nothing fitted.
        Replaces whatever was loaded. */
    bool Load(const u8* aSmf, int aLen);

    /** Forgets the sequence and frees its memory. */
    void Clear();

    bool Loaded() const { return iMsg != 0 && iCount > 0; }
    int  Count()  const { return iCount; }

    /** Total duration in microseconds (the last event; minimum 1). Used for looping. */
    u32 DurationUs() const { return iDurationUs; }

    /** Channels the sequence uses, one bit each. Lets "all notes off" go only where it
        is needed, instead of sweeping all 16. */
    u16 ChannelMask() const { return iChannelMask; }

    /** The next message whose time has arrived (us <= aNowUs), or 0 if there is none.
        Advances the cursor, so call it in a loop until it returns 0. */
    const Msg* Next(u32 aNowUs);

    /** Has everything been delivered? */
    bool Finished() const { return iNext >= iCount; }

    /** Rewinds the cursor to the start, for looping. Reloads nothing. */
    void Rewind() { iNext = 0; }

private:
    /* an event still in TICKS, before it becomes microseconds */
    struct Raw { u32 tick; u32 order; u8 status, d1, d2, size; };

    static void MergeSort(Raw* aArr, Raw* aTmp, int aN);

    Msg* iMsg;
    int  iCount;
    int  iNext;
    u32  iDurationUs;
    u16  iChannelMask;
};

#endif /* MIDISEQ_H */
