/* WolphunConfig.cpp - see WolphunConfig.h */
#include "WolphunConfig.h"
#include "WolphunLang.h"
#include <bautils.h>
#include <e32keys.h>      /* EStdKeyUpArrow, EStdKeySpace, ... */

/* The resolution table (index -> size). It stops at EWolphunResAuto, because that last
   list item is not a size but "work it out from the game". */
static const TInt KResW[EWolphunResAuto] = { 101, 128, 176, 176, 208, 240, 320 };
static const TInt KResH[EWolphunResAuto] = {  80, 160, 208, 220, 320, 320, 240 };

TWolphunConfig::TWolphunConfig(){ SetDefaults(); }

void TWolphunConfig::SetDefaults()
{
    /* Automatic: the game itself says which handset it was built for, through a
       [T300], [T610] or [176x208] tag in its name. Choosing wrong does more than crop
       the picture: games that are not "multiscreen" abort with
       "Terminal not supported!". */
    iResolution = EWolphunResAuto;
    iScaling    = EWolphunScaleAspect;
    iKeyProfile = EWolphunKeysAuto;
    iSound      = 1;
    iTexFilter  = 0;
    iPowerSave  = 1;
    /* Left shift: on the E-series QWERTY it is the bottom-left key, well away from the
       game keys (WASD, space, enter), and no Mophun game uses it. It can be changed in
       Settings > Fullscreen key. */
    iFullKey    = EStdKeyLeftShift;
    iVibrate    = 1;        /* on, matching the Android default */
    iLanguage   = 0;        /* English, matching the desktop */

    /* The free map starts with the SAME set as the "Automatic" profile, so that opening
       "Edit keys" shows something useful instead of an empty list, and so that switching
       the profile to Custom does not leave the game with no controls at all.
       Column 0 = d-pad, column 1 = QWERTY, column 2 = the numeric keypad. */
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

    /* Literal keys: by default each one answers to itself, which is MapKeypadAscii's
       original behaviour. On a QWERTY with no keypad the user remaps them to letters. */
    for (TInt d = 0; d <= 9; d++) iBind[EWolphunActKey0 + d][0] = '0' + d;
    iBind[EWolphunActKeyHash][0] = EStdKeyHash;
    iBind[EWolphunActKeyStar][0] = EStdKeyNkpAsterisk;
    iBind[EWolphunActPhoneBack][0]  = EStdKeyBackspace;   /* the natural "back" on a QWERTY */
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

/* Action names, TRANSLATED. They used to be Portuguese _LIT literals: the menu was
   already in six languages while this list -- Settings > Edit keys -- stayed fixed in
   Portuguese. "Num 0".."Num 9" are deliberately left out: they are already neutral in
   any language. */
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
    /* The literal keys "Num 0".."Num 9". They used to be built at runtime into a static
       TBuf<10>, but MUTABLE static data cannot exist in an EKA1 DLL -- and the 8.1
       target's app is exactly that, a polymorphic .app; petran would reject the binary.
       As _LIT they are 10 CONSTANT literals: valid on both targets, and the lazy
       initialisation disappears. They do not go through WolphunLang because there is
       nothing to translate in a number. */
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
    /* EWolphunResAuto has no fixed size: the engine resolves it, because it knows the
       ROM's name (see CWolphunEngine::BuildGameL). This is only the fallback. */
    if (aIndex < 0 || aIndex >= EWolphunResAuto) aIndex = EWolphunRes176x208;
    return TSize(KResW[aIndex], KResH[aIndex]);
}

/* ---------------- storage ---------------- */
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
    /* the app's private directory (C:\private\<uid3>\) -- needs no capability */
    User::LeaveIfError(iFs.PrivatePath(iPrivatePath));
    TFileName full;
    full.Append(_L("C:"));
    full.Append(iPrivatePath);
    iPrivatePath = full;
    iFs.MkDirAll(iPrivatePath);   /* KErrAlreadyExists is ignored */
}

void CWolphunConfigStore::BuildPathL(const TDesC& aRomName, TDes& aOut, const TDesC& aExt)
{
    aOut.Zero();
    aOut.Append(iPrivatePath);
    /* use only the base name, without the extension, replacing awkward characters */
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

/* The emulator's GLOBAL config: the defaults inherited by games that have no .cfg of
   their own yet. Without it, every new game started from the factory key map and the
   user had to redo the setup game by game. */
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

/* Reads a .cfg over the top of aCfg. Fields the file does not carry -- an older,
   shorter version -- stay as they were, which is precisely why appending fields at the
   END of the struct never invalidates a configuration already saved. */
void CWolphunConfigStore::ReadIntoL(RFile& aFile, TWolphunConfig& aCfg)
{
    const TInt KMinSize = (TInt)(sizeof(TInt) * 5);   /* res, scaling, keyProfile, sound, texFilter */
    TWolphunConfig loaded = aCfg;                      /* the baseline: whatever was in force */
    TPtr8 raw((TUint8*)&loaded, 0, sizeof(TWolphunConfig));
    if (aFile.Read(raw) != KErrNone) return;
    if (raw.Length() < KMinSize || raw.Length() > (TInt)sizeof(TWolphunConfig)) return;
    /* validate: a corrupt file must not bring the emulator down */
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
