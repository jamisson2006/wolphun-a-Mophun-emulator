/* WolphunConfig.cpp - ver WolphunConfig.h */
#include "WolphunConfig.h"
#include "WolphunLang.h"
#include <bautils.h>
#include <e32keys.h>      /* EStdKeyUpArrow, EStdKeySpace, ... */

/* tabela de resolucoes (indice -> tamanho). Vai so ate EWolphunResAuto: esse ultimo
   item da lista nao e um tamanho, e "deduzir do jogo". */
static const TInt KResW[EWolphunResAuto] = { 101, 128, 176, 176, 208, 240, 320 };
static const TInt KResH[EWolphunResAuto] = {  80, 160, 208, 220, 320, 320, 240 };

TWolphunConfig::TWolphunConfig(){ SetDefaults(); }

void TWolphunConfig::SetDefaults()
{
    /* Automatica: o proprio jogo diz para que aparelho foi feito (etiqueta [T300],
       [T610], [176x208]... no nome). Escolher errado nao so corta a imagem: os jogos
       que nao sao "multiscreen" abortam com "Terminal not supported!". */
    iResolution = EWolphunResAuto;
    iScaling    = EWolphunScaleAspect;
    iKeyProfile = EWolphunKeysAuto;
    iSound      = 1;
    iTexFilter  = 0;
    iPowerSave  = 1;
    /* Shift esquerdo: no QWERTY dos E-series e a tecla de baixo a esquerda, longe
       das de jogo (WASD, espaco, enter) e nenhum jogo mophun a usa. Da para trocar
       em Configuracoes > Tecla de tela cheia. */
    iFullKey    = EStdKeyLeftShift;
    iVibrate    = 1;        /* ligada, igual ao padrao do Android */
    iLanguage   = 0;        /* ingles, igual ao desktop */        /* alta: ~40% de CPU, o meio-termo que ainda roda bem */

    /* Mapa livre: comeca com o MESMO conjunto do perfil "Automatico", para que abrir
       "Editar teclas" mostre algo util em vez de uma lista vazia, e para que trocar o
       perfil para Personalizado nao deixe o jogo sem controle nenhum.
       Coluna 0 = d-pad, coluna 1 = QWERTY, coluna 2 = teclado numerico. */
    for (TInt a = 0; a < EWolphunActionCount; a++)
        for (TInt k = 0; k < KWolphunKeysPerAction; k++)
            iBind[a][k] = 0;

    iBind[EWolphunActUp][0]    = EStdKeyUpArrow;    iBind[EWolphunActUp][1]    = 'W'; iBind[EWolphunActUp][2]    = '2';
    iBind[EWolphunActDown][0]  = EStdKeyDownArrow;  iBind[EWolphunActDown][1]  = 'S'; iBind[EWolphunActDown][2]  = '8';
    iBind[EWolphunActLeft][0]  = EStdKeyLeftArrow;  iBind[EWolphunActLeft][1]  = 'A'; iBind[EWolphunActLeft][2]  = '4';
    iBind[EWolphunActRight][0] = EStdKeyRightArrow; iBind[EWolphunActRight][1] = 'D'; iBind[EWolphunActRight][2] = '6';
    iBind[EWolphunActFire][0]  = EStdKeyDevice3;    iBind[EWolphunActFire][1]  = EStdKeySpace; iBind[EWolphunActFire][2] = '5';
    iBind[EWolphunActFire2][0] = EStdKeyHash;       iBind[EWolphunActFire2][1] = 'E';
    iBind[EWolphunActSelect][0]= '0';               iBind[EWolphunActSelect][1]= 'Q';

    /* Teclas literais: por padrao cada uma responde a ela mesma, que e o comportamento
       antigo do MapKeypadAscii. Num QWERTY sem numerico o usuario remapeia para letras. */
    for (TInt d = 0; d <= 9; d++) iBind[EWolphunActKey0 + d][0] = '0' + d;
    iBind[EWolphunActKeyHash][0] = EStdKeyHash;
    iBind[EWolphunActKeyStar][0] = EStdKeyNkpAsterisk;
    iBind[EWolphunActPhoneBack][0]  = EStdKeyBackspace;   /* "voltar" natural no QWERTY */
    iBind[EWolphunActPhoneClear][0] = 'C';
}

TInt TWolphunConfig::ActionAscii(TInt aAction)
{
    if (aAction >= EWolphunActKey0 && aAction <= EWolphunActKey9) return '0' + (aAction - EWolphunActKey0);
    if (aAction == EWolphunActKeyHash) return '#';
    if (aAction == EWolphunActKeyStar) return '*';
    if (aAction == EWolphunActPhoneBack)  return 203;     /* KEY_SONYERICSSON_NO    */
    if (aAction == EWolphunActPhoneClear) return 204;     /* KEY_SONYERICSSON_CLEAR */
    return -1;
}

TInt TWolphunConfig::ActionBit(TInt aAction)
{
    switch (aAction)
    {
        case EWolphunActUp:     return 0x01;    /* Screen::UP    */
        case EWolphunActDown:   return 0x02;    /* Screen::DOWN  */
        case EWolphunActLeft:   return 0x04;    /* Screen::LEFT  */
        case EWolphunActRight:  return 0x08;    /* Screen::RIGHT */
        case EWolphunActFire:   return 0x10;    /* Screen::FIRE  */
        case EWolphunActSelect: return 0x20;    /* Screen::SELECT*/
        case EWolphunActFire2:  return 0x100;   /* Screen::FIRE2 */
        default: return 0;
    }
}

/* Nomes das acoes, TRADUZIDOS. Eram _LIT em portugues: o menu ja estava em seis idiomas
   mas esta lista -- Configuracoes > Editar teclas -- continuava fixa em portugues.
   As "Num 0".."Num 9" ficam de fora de proposito: ja sao neutras em qualquer idioma. */
TPtrC TWolphunConfig::ActionName(TInt aAction)
{
    switch (aAction)
    {
        case EWolphunActUp:     return WolphunLang::T(EStrActUp);
        case EWolphunActDown:   return WolphunLang::T(EStrActDown);
        case EWolphunActLeft:   return WolphunLang::T(EStrActLeft);
        case EWolphunActRight:  return WolphunLang::T(EStrActRight);
        case EWolphunActFire:   return WolphunLang::T(EStrActFire);
        case EWolphunActFire2:  return WolphunLang::T(EStrActFire2);
        case EWolphunActSelect: return WolphunLang::T(EStrActSelect);
        case EWolphunActKeyHash:  return WolphunLang::T(EStrActHash);
        case EWolphunActKeyStar:  return WolphunLang::T(EStrActStar);
        case EWolphunActPhoneBack:  return WolphunLang::T(EStrActBack);
        case EWolphunActPhoneClear: return WolphunLang::T(EStrActClear);
        default: break;
    }
    /* teclas literais "Num 0".."Num 9". Eram montadas em tempo de execucao num TBuf<10>
       estatico, mas dado estatico MUTAVEL nao pode existir numa DLL do EKA1 -- e o app do
       alvo 8.1 e exatamente isso, um .app polimorfico; o petran recusaria o binario. Com
       _LIT sao 10 literais CONSTANTES: vale nos dois alvos e some a inicializacao
       preguicosa. Nao passam por WolphunLang porque nao ha o que traduzir num numero. */
    switch (aAction)
    {
        case EWolphunActKey0: { _LIT(k,"Num 0"); return TPtrC(k); }
        case EWolphunActKey1: { _LIT(k,"Num 1"); return TPtrC(k); }
        case EWolphunActKey2: { _LIT(k,"Num 2"); return TPtrC(k); }
        case EWolphunActKey3: { _LIT(k,"Num 3"); return TPtrC(k); }
        case EWolphunActKey4: { _LIT(k,"Num 4"); return TPtrC(k); }
        case EWolphunActKey5: { _LIT(k,"Num 5"); return TPtrC(k); }
        case EWolphunActKey6: { _LIT(k,"Num 6"); return TPtrC(k); }
        case EWolphunActKey7: { _LIT(k,"Num 7"); return TPtrC(k); }
        case EWolphunActKey8: { _LIT(k,"Num 8"); return TPtrC(k); }
        case EWolphunActKey9: { _LIT(k,"Num 9"); return TPtrC(k); }
        default: break;
    }
    _LIT(KActNone,"?");
    return TPtrC(KActNone);
}

TSize TWolphunConfig::ResolutionSize(TInt aIndex)
{
    /* EWolphunResAuto nao tem tamanho fixo: quem resolve e o motor, que conhece o nome
       da ROM (ver CWolphunEngine::BuildGameL). Aqui so o valor de reserva. */
    if (aIndex < 0 || aIndex >= EWolphunResAuto) aIndex = EWolphunRes176x208;
    return TSize(KResW[aIndex], KResH[aIndex]);
}

/* ---------------- armazenamento ---------------- */
CWolphunConfigStore* CWolphunConfigStore::NewL(RFs& aFs)
{
    CWolphunConfigStore* self = new (ELeave) CWolphunConfigStore(aFs);
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
}

CWolphunConfigStore::CWolphunConfigStore(RFs& aFs) : iFs(aFs) {}
CWolphunConfigStore::~CWolphunConfigStore(){}

void CWolphunConfigStore::ConstructL()
{
    /* diretorio privado do app (C:\private\<uid3>\) - nao precisa de capability */
    User::LeaveIfError(iFs.PrivatePath(iPrivatePath));
    TFileName full;
    full.Append(_L("C:"));
    full.Append(iPrivatePath);
    iPrivatePath = full;
    iFs.MkDirAll(iPrivatePath);   /* ignora KErrAlreadyExists */
}

void CWolphunConfigStore::BuildPathL(const TDesC& aRomName, TDes& aOut, const TDesC& aExt)
{
    aOut.Zero();
    aOut.Append(iPrivatePath);
    /* usa so o nome base, sem extensao, e troca caracteres problematicos */
    TInt dot = aRomName.LocateReverse('.');
    TPtrC base = (dot > 0) ? aRomName.Left(dot) : aRomName;
    const TInt maxBase = aOut.MaxLength() - aOut.Length() - aExt.Length() - 1;
    if (maxBase <= 0) User::Leave(KErrOverflow);
    TInt n = Min(base.Length(), maxBase);
    for (TInt i = 0; i < n; i++)
    {
        TChar c = base[i];
        if (c.IsAlphaDigit() || c == '_' || c == '-') aOut.Append(c);
        else aOut.Append('_');
    }
    aOut.Append(aExt);
}

/* Config GLOBAL do emulador: o padrao herdado por jogos que ainda nao tem .cfg proprio.
   Sem isto, cada jogo novo nascia com o mapa de teclas de fabrica e o usuario tinha de
   refazer a configuracao jogo a jogo. */
_LIT(KGlobalCfg, "global.cfg");

void CWolphunConfigStore::LoadGlobalL(TWolphunConfig& aCfg)
{
    aCfg.SetDefaults();
    TFileName path(iPrivatePath);
    path.Append(KGlobalCfg);
    RFile f;
    if (f.Open(iFs, path, EFileRead) != KErrNone) return;
    CleanupClosePushL(f);
    ReadIntoL(f, aCfg);
    CleanupStack::PopAndDestroy(&f);
}

void CWolphunConfigStore::SaveGlobalL(const TWolphunConfig& aCfg)
{
    TFileName path(iPrivatePath);
    path.Append(KGlobalCfg);
    RFile f;
    if (f.Replace(iFs, path, EFileWrite) != KErrNone) return;
    CleanupClosePushL(f);
    TPckgC<TWolphunConfig> pkg(aCfg);
    f.Write(pkg);
    CleanupStack::PopAndDestroy(&f);
}

/* Le um .cfg por cima de aCfg. Campos que o arquivo nao tiver (versao antiga, menor)
   permanecem como estavam -- e por isso que acrescentar campos no FIM da struct nunca
   invalida a configuracao ja salva. */
void CWolphunConfigStore::ReadIntoL(RFile& aFile, TWolphunConfig& aCfg)
{
    const TInt KMinSize = (TInt)(sizeof(TInt) * 5);   /* res, scaling, keyProfile, sound, texFilter */
    TWolphunConfig loaded = aCfg;                      /* base: o que ja estava valendo */
    TPtr8 raw((TUint8*)&loaded, 0, sizeof(TWolphunConfig));
    if (aFile.Read(raw) != KErrNone) return;
    if (raw.Length() < KMinSize || raw.Length() > (TInt)sizeof(TWolphunConfig)) return;
    /* valida: um arquivo corrompido nao pode derrubar o emulador */
    if (loaded.iResolution >= 0 && loaded.iResolution < EWolphunResCount
        && loaded.iScaling >= 0 && loaded.iScaling <= EWolphunScaleFill
        && loaded.iKeyProfile >= 0 && loaded.iKeyProfile < EWolphunKeyProfileCount
        && loaded.iTexFilter >= 0 && loaded.iTexFilter <= 2)
    {
        aCfg = loaded;
        aCfg.iSound = loaded.iSound ? 1 : 0;
    }
}

void CWolphunConfigStore::LoadL(const TDesC& aRomName, TWolphunConfig& aCfg)
{
    /* 1) parte do padrao GLOBAL (teclas, som, escala, filtro que o usuario deixou salvos) */
    LoadGlobalL(aCfg);
    /* 2) a RESOLUCAO nao se herda: ela e genuinamente por jogo -- o jogo le o tamanho da
       tela em vGetCaps e os que nao sao "multiscreen" abortam com "Terminal not supported!"
       se receberem a resolucao de outro aparelho. Jogo novo comeca sempre em Automatico. */
    aCfg.iResolution = EWolphunResAuto;
    /* 3) por cima, o .cfg do proprio jogo, se existir */
    TFileName path;
    BuildPathL(aRomName, path, _L(".cfg"));
    RFile f;
    if (f.Open(iFs, path, EFileRead) != KErrNone) return;   /* sem config do jogo: fica o global */
    CleanupClosePushL(f);
    ReadIntoL(f, aCfg);
    CleanupStack::PopAndDestroy(&f);
}

void CWolphunConfigStore::SaveL(const TDesC& aRomName, const TWolphunConfig& aCfg)
{
    TFileName path;
    BuildPathL(aRomName, path, _L(".cfg"));
    RFile f;
    TInt err = f.Replace(iFs, path, EFileWrite);
    if (err != KErrNone) return;            /* sem espaco/permissao: nao e fatal */
    CleanupClosePushL(f);
    TPckgC<TWolphunConfig> pkg(aCfg);
    f.Write(pkg);
    CleanupStack::PopAndDestroy(&f);
    /* ...e tambem como padrao do SISTEMA, para o proximo jogo ja nascer configurado.
       A resolucao vai junto mas e ignorada na leitura (ver LoadL). */
    SaveGlobalL(aCfg);
}

/* ===================== lista de pastas de jogos ===================== */
_LIT(KFoldersFile, "folders.txt");
_LIT(KDefaultDirC, "C:\\Data\\Mophun\\");
_LIT(KDefaultDirE, "E:\\Mophun\\");

CWolphunFolders* CWolphunFolders::NewL(RFs& aFs, const TDesC& aPrivatePath)
{
    CWolphunFolders* self = new (ELeave) CWolphunFolders(aFs);
    CleanupStack::PushL(self);
    self->ConstructL(aPrivatePath);
    CleanupStack::Pop(self);
    return self;
}

CWolphunFolders::CWolphunFolders(RFs& aFs) : iFs(aFs), iPaths(0) {}

CWolphunFolders::~CWolphunFolders(){ delete iPaths; }

void CWolphunFolders::ConstructL(const TDesC& aPrivatePath)
{
    iPaths = new (ELeave) CDesCArrayFlat(4);
    iStorePath.Copy(aPrivatePath);
    iStorePath.Append(KFoldersFile);
    LoadL();
    if (iPaths->Count() == 0)
    {
        /* primeira execucao: comeca com as duas pastas convencionais */
        iPaths->AppendL(KDefaultDirC);
        iPaths->AppendL(KDefaultDirE);
        SaveL();
    }
}

TInt CWolphunFolders::Count() const { return iPaths ? iPaths->Count() : 0; }

TPtrC CWolphunFolders::At(TInt aIndex) const
{
    if (!iPaths || aIndex < 0 || aIndex >= iPaths->Count()) return TPtrC();
    return (*iPaths)[aIndex];
}

TBool CWolphunFolders::AddL(const TDesC& aPath)
{
    if (aPath.Length() == 0) return EFalse;
    /* normaliza: sempre termina com barra */
    TFileName p;
    p.Copy(aPath.Left(p.MaxLength() - 1));
    if (p[p.Length()-1] != '\\') p.Append('\\');

    for (TInt i = 0; i < iPaths->Count(); i++)
        if ((*iPaths)[i].CompareF(p) == 0) return EFalse;   /* ja existe */

    iPaths->AppendL(p);
    SaveL();
    return ETrue;
}

void CWolphunFolders::RemoveL(TInt aIndex)
{
    if (!iPaths || aIndex < 0 || aIndex >= iPaths->Count()) return;
    iPaths->Delete(aIndex);
    iPaths->Compress();
    SaveL();
}

void CWolphunFolders::LoadL()
{
    RFile f;
    if (f.Open(iFs, iStorePath, EFileRead) != KErrNone) return;
    CleanupClosePushL(f);
    TInt size = 0;
    if (f.Size(size) == KErrNone && size > 0 && size < 32768)
    {
        HBufC8* raw = HBufC8::NewLC(size);
        TPtr8 p = raw->Des();
        if (f.Read(p) == KErrNone)
        {
            /* um caminho por linha (texto de 8 bits: caminhos sao ASCII) */
            TInt start = 0;
            for (TInt i = 0; i <= p.Length(); i++)
            {
                const TBool end = (i == p.Length());
                if (end || p[i] == '\n' || p[i] == '\r')
                {
                    if (i > start)
                    {
                        TPtrC8 line8 = p.Mid(start, i - start);
                        TFileName line;
                        line.Copy(line8.Left(line.MaxLength()));
                        if (line.Length() > 2) iPaths->AppendL(line);
                    }
                    start = i + 1;
                }
            }
        }
        CleanupStack::PopAndDestroy(raw);
    }
    CleanupStack::PopAndDestroy(&f);
}

void CWolphunFolders::SaveL()
{
    RFile f;
    if (f.Replace(iFs, iStorePath, EFileWrite) != KErrNone) return;
    CleanupClosePushL(f);
    for (TInt i = 0; i < iPaths->Count(); i++)
    {
        TBuf8<KMaxFileName + 2> line;
        line.Copy((*iPaths)[i].Left(KMaxFileName));
        line.Append(_L8("\r\n"));
        f.Write(line);
    }
    CleanupStack::PopAndDestroy(&f);
}
