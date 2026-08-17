package wolphun.vm;

import wolphun.Screen;

/** Teste do round-trip de save de arquivo (vStream* type=File): grava e le de volta. */
public final class SaveTest {
    public static void main(String[] a) throws Exception {
        Vmgp exe = Vmgp.load("assets/game.mpn");
        Screen scr = new Screen(128,160,1,true);
        Pip2 vm = new Pip2(exe, 4*1024*1024, 256*1024);
        Sdk sdk = new Sdk(vm, scr);
        vm.sdk = sdk;
        java.io.File dir = new java.io.File(System.getenv("TEMP"), "savetest_"+System.nanoTime());
        sdk.saveDir = dir;

        // escreve 8 bytes de payload na memoria da VM (endereco = heapBase)
        int addr = vm.heapBase;
        byte[] payload = {1,2,3,4,(byte)0xAA,(byte)0xBB,(byte)0xCC,(byte)0xDD};
        for(int i=0;i<payload.length;i++) vm.write8(addr+i, payload[i]&0xff);

        // 1) GRAVA: abre "resume" em Write|Create (0x200|0x800), escreve, fecha
        int fd = sdk.fileOpen("resume", Sdk.SF_WRITE|Sdk.SF_CREATE);
        System.out.println("open(write) fd="+fd);
        int wr = sdk.streamWrite(fd, addr, payload.length);
        System.out.println("write="+wr+" bytes");
        java.io.File f = new java.io.File(dir, "resume.sav");
        System.out.println("arquivo existe apos WRITE (antes do close)? "+f.isFile()+" ("+(f.isFile()?f.length():0)+" bytes)");
        sdk.streamClose(fd);

        // 2) LE de volta: abre "resume" em Read (0x100), le, compara
        int fd2 = sdk.fileOpen("resume", Sdk.SF_READ);
        System.out.println("open(read) fd="+fd2+(fd2<0?" (FALHOU!)":""));
        int rdAddr = addr+256;
        int rd = sdk.streamRead(fd2, rdAddr, payload.length);
        boolean ok = (rd==payload.length);
        for(int i=0;i<payload.length && ok;i++) if((vm.read8(rdAddr+i)&0xff)!=(payload[i]&0xff)) ok=false;
        System.out.println("read="+rd+" bytes  round-trip "+(ok?"OK ✓":"FALHOU ✗"));
        sdk.streamClose(fd2);

        // 3) LE de save INEXISTENTE deve FALHAR (-> jogo sabe que nao ha save -> sem Continue)
        int fd3 = sdk.fileOpen("naoexiste", Sdk.SF_READ);
        System.out.println("open(read) de inexistente = "+fd3+(fd3<0?" (correto: -1)":" (ERRO: deveria ser -1!)"));

        // 4) MODOS EXATOS DO HC2: resume=0x600 (Write|Binary), highscore=0xF00 (Read|Write|Binary|Create).
        //    Ambos devem retornar fd valido (!= -1) e criar o arquivo.
        int r600 = sdk.fileOpen("resume2", 0x600);
        System.out.println("HC2 resume open(0x600) = "+r600+(r600>=0?" OK (cria+grava)":" FALHOU!"));
        if(r600>=0){ sdk.streamWrite(r600, addr, payload.length); sdk.streamClose(r600); }
        System.out.println("  resume2.sav existe? "+new java.io.File(dir,"resume2.sav").isFile());
        int hF00 = sdk.fileOpen("highscore2", 0xF00);
        System.out.println("HC2 highscore open(0xF00) = "+hF00+(hF00>=0?" OK (cria+grava)":" FALHOU!"));
        if(hF00>=0){ sdk.streamWrite(hF00, addr, 4); sdk.streamClose(hF00); }
        System.out.println("  highscore2.sav existe? "+new java.io.File(dir,"highscore2.sav").isFile());
    }
}
