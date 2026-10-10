/* WolphunConfig.h - configuracoes POR JOGO, persistidas no diretorio privado do app.
 *
 * Cada ROM (.mpn) tem o seu arquivo "<nome>.cfg". A resolucao e por jogo porque os
 * jogos mophun foram feitos para aparelhos diferentes: o jogo le o tamanho da tela em
 * vGetCaps na INICIALIZACAO, entao trocar a resolucao reinicia a sessao.
 */
#ifndef WOLPHUNCONFIG_H
#define WOLPHUNCONFIG_H

#include <e32base.h>
#include <f32file.h>
#include <badesca.h>     /* CDesCArrayFlat */

/* Resolucoes: as mesmas dos modelos reais que o emulador de PC mapeia (Config.java),
   mais as duas QVGA dos aparelhos Nokia modernos. */
enum TWolphunRes {
    EWolphunRes101x80 = 0,
    EWolphunRes128x160,
    EWolphunRes176x208,     /* padrao: S60 retrato */
    EWolphunRes176x220,
    EWolphunRes208x320,
    EWolphunRes240x320,
    EWolphunRes320x240,     /* E-series paisagem */
    /* Automatico: deduz do PROPRIO jogo (modelo [T300]/[T610]... ou etiqueta
       [LxA] no nome). Novo indice no FIM para nao invalidar os .cfg ja salvos. */
    EWolphunResAuto,
    EWolphunResCount
};

enum TWolphunScaling {
    EWolphunScaleCenter = 0,    /* 1:1 centralizado (mais nitido) */
    EWolphunScaleAspect,        /* amplia mantendo proporcao */
    EWolphunScaleFill           /* preenche a tela */
};

enum TWolphunKeyProfile {
    EWolphunKeysAuto = 0,       /* detecta pelo aparelho */
    EWolphunKeysQwerty,         /* WASD + espaco (E-series) */
    EWolphunKeysNumeric,        /* teclado 2/4/6/8/5 */
    EWolphunKeysJoystickOnly,
    EWolphunKeysCustom,         /* so o mapa livre de iBind (Configuracoes > Editar teclas) */
    EWolphunKeyProfileCount
};

/* Acoes do jogo que podem receber tecla. A ORDEM e gravada no .cfg: nao reordenar,
   so acrescentar no fim. Espelha o modelo do emulador de PC (Config.ACTIONS). */
enum TWolphunAction {
    EWolphunActUp = 0, EWolphunActDown, EWolphunActLeft, EWolphunActRight,
    EWolphunActFire, EWolphunActFire2, EWolphunActSelect,
    /* TECLAS LITERAIS. Muitos jogos nao leem so os bits de botao: leem a tecla do telefone
       crua por vTestKey/vScanKeys (o Bomb Jack anda com 2 e 3). Num aparelho QWERTY como o
       E63 nao existe teclado numerico para pressionar, entao sem poder remapear esses jogos
       ficam injogaveis. A ORDEM e gravada no .cfg: so acrescentar no fim. */
    EWolphunActKey0, EWolphunActKey1, EWolphunActKey2, EWolphunActKey3, EWolphunActKey4,
    EWolphunActKey5, EWolphunActKey6, EWolphunActKey7, EWolphunActKey8, EWolphunActKey9,
    EWolphunActKeyHash, EWolphunActKeyStar,
    /* TECLAS DO APARELHO (vmgpsonyericsson.h: KEY_SONYERICSSON_NO = 203, _CLEAR = 204).
       Nao existe bit de botao equivalente: viajam pelo mesmo caminho das teclas literais.
       O emulador de PC ja as oferecia; o Symbian nao enviava nenhuma das duas. Achadas por
       analise estatica das ROMs: Ice Digger / Bomb Jack / SAAF pedem 203, 4-in-1 pede 204. */
    EWolphunActPhoneBack, EWolphunActPhoneClear,
    EWolphunActionCount
};
/* Quantas teclas por acao. 3 cobre d-pad + QWERTY + numerico simultaneos, que e
   exatamente o que o perfil "Automatico" ja fazia. */
const TInt KWolphunKeysPerAction = 3;

class TWolphunConfig
{
public:
    TWolphunConfig();
    void SetDefaults();

    /* dimensoes correspondentes ao indice de resolucao */
    static TSize ResolutionSize(TInt aIndex);

    TInt iResolution;      /* TWolphunRes */
    TInt iScaling;         /* TWolphunScaling */
    TInt iKeyProfile;      /* TWolphunKeyProfile */
    TInt iSound;           /* 0 = desligado, 1 = ligado */
    TInt iTexFilter;       /* 0 = segue o jogo, 1 = bilinear, 2 = nearest */

    /* Mapa LIVRE de teclas: scan code do Symbian (TStdScanCode ou 'W'/'2'), 0 = vazio.
       So vale quando iKeyProfile == EWolphunKeysCustom; assim os quatro perfis antigos
       continuam funcionando exatamente como antes para quem nao quiser mexer. */
    TInt iBind[EWolphunActionCount][KWolphunKeysPerAction];

    /* ECONOMIA DE BATERIA: 0=maxima, 1=alta (padrao), 2=media, 3=desligada.
       Nao e enfeite. Depois que o rasterizador ficou rapido, um Nokia E63 com bateria
       gasta DESLIGA SOZINHO no Rally Pro Contest em alta velocidade: os LEDs do teclado
       apagam, o brilho cai e ele reinicia com a logo -- queda de tensao sob carga
       sustentada. Cada nivel limita o CICLO DE TRABALHO da CPU (quanto tempo a VM roda
       dentro de cada tique de 20 ms), que e o que a bateria sente; limitar so os quadros
       nao resolve, porque numa cena pesada o jogo ja esta abaixo do teto.
       Campo NO FIM da struct de proposito: o .cfg e despejo cru e o ReadIntoL le o
       prefixo por cima dos padroes, entao config antiga continua valendo. */
    TInt iPowerSave;

    /* IDIOMA DA INTERFACE: 0=English (padrao), 1=Espanol, 2=Deutsch, 3=Portugues,
       4=Ukrainian, 5=Russian -- a MESMA ordem do desktop (src/mophun/Lang.java), para
       que o indice salvo signifique o mesmo nos dois. Tambem NO FIM da struct. */
    /* Tecla que alterna a TELA CHEIA (scancode do Symbian; 0 = nenhuma).
       Fica no FIM da struct como os anteriores: a config e gravada como BLOCO
       BRUTO e lida por prefixo, entao campo novo no meio invalidaria os .cfg
       que ja existem no aparelho. */
    TInt iFullKey;

    /* vibracao do aparelho no vSysCtl(SYSCTL_SETVIBRATE): 0 = desligada, 1 = ligada.
       No FIM da struct como os anteriores -- a config e um bloco bruto lido por
       prefixo, campo novo no meio invalidaria os .cfg que ja estao no aparelho. */
    TInt iVibrate;

    TInt iLanguage;

    /** Bit do Screen correspondente a acao (UP=0x01 ... FIRE2=0x100). */
    static TInt ActionBit(TInt aAction);
    /** Nome curto da acao, para a tela de edicao. */
    /* Devolve por VALOR: o texto vem de WolphunLang::T(), que monta um TPtrC na
       hora. Devolver const TDesC& seria referencia a temporario. */
    static TPtrC ActionName(TInt aAction);
    /** ASCII da tecla literal do telefone ('0'..'9','#','*'), ou -1 se for acao de botao. */
    static TInt ActionAscii(TInt aAction);
};

/* Carrega/grava a configuracao de um jogo no diretorio privado do app. */
class CWolphunConfigStore : public CBase
{
public:
    static CWolphunConfigStore* NewL(RFs& aFs);
    ~CWolphunConfigStore();

    /* aRomName = nome do arquivo da ROM (sem caminho). */
    void LoadL(const TDesC& aRomName, TWolphunConfig& aCfg);
    void SaveL(const TDesC& aRomName, const TWolphunConfig& aCfg);

    /* Padrao do SISTEMA (global.cfg): herdado por jogos que ainda nao tem .cfg proprio,
       para o usuario nao ter de refazer o mapa de teclas em cada jogo novo. */
    void LoadGlobalL(TWolphunConfig& aCfg);
    void SaveGlobalL(const TWolphunConfig& aCfg);

    /* diretorio privado (tambem usado para os saves nativos dos jogos) */
    const TDesC& PrivatePath() const { return iPrivatePath; }

private:
    CWolphunConfigStore(RFs& aFs);
    void ConstructL();
    void BuildPathL(const TDesC& aRomName, TDes& aOut, const TDesC& aExt);
    static void ReadIntoL(RFile& aFile, TWolphunConfig& aCfg);

    RFs& iFs;
    TFileName iPrivatePath;
};

/* ---------------------------------------------------------------------------
 * Lista de PASTAS DE JOGOS gerenciada pelo usuario (adicionar/remover varias).
 * "Abrir jogo" varre TODAS elas de uma vez (bulk), em vez de ficar preso a um
 * caminho fixo. Persistida em <privado>\folders.txt, um caminho por linha.
 * -------------------------------------------------------------------------*/
class CWolphunFolders : public CBase
{
public:
    static CWolphunFolders* NewL(RFs& aFs, const TDesC& aPrivatePath);
    ~CWolphunFolders();

    TInt Count() const;
    TPtrC At(TInt aIndex) const;

    /* Adiciona se ainda nao existir (comparacao sem diferenciar maiusculas).
       Devolve EFalse se ja estava na lista. */
    TBool AddL(const TDesC& aPath);
    void  RemoveL(TInt aIndex);
    void  SaveL();

private:
    CWolphunFolders(RFs& aFs);
    void ConstructL(const TDesC& aPrivatePath);
    void LoadL();

    RFs&            iFs;
    TFileName       iStorePath;
    CDesCArrayFlat* iPaths;
};

#endif /* WOLPHUNCONFIG_H */
