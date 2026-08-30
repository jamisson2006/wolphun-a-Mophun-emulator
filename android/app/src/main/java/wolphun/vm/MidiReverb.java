package wolphun.vm;

/**
 * Remove o "eco" (reverb) de um SMF (MIDI). No Android o SMF e tocado direto pelo
 * Sonivox (MediaPlayer), sem acesso ao synth p/ filtrar controladores em tempo real;
 * entao transformamos os BYTES do arquivo: (1) zeramos o VALOR de todo Control Change
 * 91 (Reverb Send Level) presente e (2) injetamos CC91=0 nas 16 vozes no inicio da 1a
 * track — o padrao General MIDI e 40 (reverb LIGADO) mesmo quando a musica nunca pede.
 * Qualquer erro de parsing -> devolve o SMF original (nunca quebra o som).
 */
public final class MidiReverb {
    private MidiReverb(){}

    public static byte[] kill(byte[] smf){
        try {
            if(smf==null || smf.length<14) return smf;
            if(smf[0]!='M'||smf[1]!='T'||smf[2]!='h'||smf[3]!='d') return smf;
            int headerLen = be32(smf,4);
            int pos = 8 + headerLen;                 // fim do chunk MThd
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(smf.length+80);
            out.write(smf, 0, pos);                  // copia o MThd inteiro
            boolean firstTrack = true;
            while(pos+8 <= smf.length){
                if(smf[pos]!='M'||smf[pos+1]!='T'||smf[pos+2]!='r'||smf[pos+3]!='k') break;
                int len = be32(smf, pos+4);
                int dataStart = pos+8;
                int dataEnd = dataStart+len; if(dataEnd>smf.length) dataEnd=smf.length;
                byte[] track = zeroReverbInTrack(smf, dataStart, dataEnd);   // zera CC91 existentes
                byte[] inject = firstTrack ? injectPreamble() : new byte[0]; // injeta CC91=0 na 1a track
                int newLen = inject.length + track.length;
                out.write('M'); out.write('T'); out.write('r'); out.write('k');
                writeBe32(out, newLen);
                out.write(inject, 0, inject.length);
                out.write(track, 0, track.length);
                firstTrack = false;
                pos = dataEnd;
            }
            return out.toByteArray();
        } catch(Throwable t){ return smf; }
    }

    // 16 eventos "delta=0, Bn 5B 00" (CC91=0 nas vozes 0..15)
    static byte[] injectPreamble(){
        byte[] a = new byte[16*4];
        int i=0;
        for(int ch=0; ch<16; ch++){
            a[i++]=0x00;               // delta-time
            a[i++]=(byte)(0xB0|ch);    // Control Change, canal ch
            a[i++]=(byte)0x5B;         // controlador 91 (reverb send)
            a[i++]=0x00;               // valor 0
        }
        return a;
    }

    // copia a track zerando o VALOR de todo CC91; respeita delta-times, running status, sysex e meta.
    static byte[] zeroReverbInTrack(byte[] b, int start, int end){
        byte[] t = new byte[end-start];
        System.arraycopy(b, start, t, 0, t.length);
        int p=0, status=0;
        while(p < t.length){
            int[] r = readVarlen(t, p); if(r==null) break; p=r[1];   // delta-time
            if(p>=t.length) break;
            int c = t[p]&0xFF;
            if(c>=0x80){ status=c; p++; }   // status novo
            else { c=status; }              // running status
            if(c==0xFF){                    // meta: FF type len data
                if(p>=t.length) break; p++;                 // type
                int[] m = readVarlen(t, p); if(m==null) break; p=m[1]+m[0];
            } else if(c==0xF0 || c==0xF7){  // sysex: len data
                int[] m = readVarlen(t, p); if(m==null) break; p=m[1]+m[0];
            } else {
                int hi = c & 0xF0;
                if(hi==0xC0 || hi==0xD0){   // program change / channel pressure: 1 dado
                    p += 1;
                } else {                    // 2 dados (note, CC, pitch, etc.)
                    if(hi==0xB0){           // Control Change
                        int ctrl = (p<t.length)? (t[p]&0xFF) : -1;
                        if(ctrl==0x5B && p+1<t.length) t[p+1]=0;   // reverb send -> 0
                    }
                    p += 2;
                }
            }
        }
        return t;
    }

    static int[] readVarlen(byte[] b, int p){
        int v=0, n=0;
        while(p<b.length && n<4){
            int c=b[p]&0xFF; p++; n++;
            v=(v<<7)|(c&0x7F);
            if((c&0x80)==0) return new int[]{v,p};
        }
        return null;   // malformado
    }
    static int be32(byte[] b,int i){ return ((b[i]&0xFF)<<24)|((b[i+1]&0xFF)<<16)|((b[i+2]&0xFF)<<8)|(b[i+3]&0xFF); }
    static void writeBe32(java.io.ByteArrayOutputStream o,int v){ o.write((v>>>24)&0xFF); o.write((v>>>16)&0xFF); o.write((v>>>8)&0xFF); o.write(v&0xFF); }
}
