/* WolphunAppUi.cpp - ver WolphunAppUi.h
 *
 * TODAS as opcoes sao listas do PROPRIO SISTEMA:
 *  - o menu da softkey esquerda e um MENU_PANE do Avkon;
 *  - cada configuracao abre um CAknRadioButtonSettingPage (a lista de radio-botoes
 *    padrao do S60);
 *  - a escolha do jogo usa um CAknListQueryDialog (lista de consulta padrao).
 * Nenhuma interface e desenhada a mao.
 */
#include "WolphunAppUi.h"
#include "WolphunLang.h"
#include "WolphunAppView.h"
#include "WolphunConfig.h"
#include "wolphun.hrh"
#include <wolphun.rsg>

#include <avkon.hrh>
#include <avkon.rsg>                    /* R_AVKON_DEFAULT_SETTING_PAGE etc. */
#include <aknradiobuttonsettingpage.h>
#include <aknlistquerydialog.h>
#include <aknmessagequerydialog.h>
#include <aknnotewrappers.h>
#include <AknCommonDialogs.h>      /* navegador de arquivos nativo */
#include <akntitle.h>
#include <eikspane.h>                     /* esconder/mostrar a barra de status */
#include <eikbtgpc.h>                     /* esconder/mostrar as softkeys (CBA) */
#include <barsread.h>
#include <eikmenup.h>
#include <stringloader.h>
#include <pathinfo.h>
#include <e32keys.h>
#include <basched.h>        /* KLeaveExit */
#include <hal.h>

#include "Screen.h"

_LIT(KRomDirC, "C:\\Data\\Mophun\\");    /* criada na primeira execucao, por conveniencia */
_LIT(KMpnExt,  ".mpn");

/* Textos fixos do dialogo Sobre. O prefixo KWolphun nao e enfeite: <uikon.hrh> ja
   define KCopyright como 169, entao _LIT(KCopyright, ...) virava _LIT(169, ...) e o
   compilador reclamava de constante numerica onde esperava um nome. */
_LIT(KWolphunAppName,   "wolphun emulator");
_LIT(KWolphunVersion,   "0.3.0");
_LIT(KWolphunAuthor,    "Jamisson Tavares");
_LIT(KWolphunStudio,    "Maned Software");
_LIT(KWolphunCopyright, "(c) 2026 - youtube.com/@jamissontavares7671");
_LIT(KNl,  "\n");
_LIT(KSp,  " ");
_LIT(KSep, ": ");

/* Append e Copy que NUNCA estouram o buffer do chamador. Viraram necessarios quando os
   textos passaram a ser traduzidos: as versoes em russo e ucraniano sao bem mais longas
   que as em portugues, e passar do MaxLength faz PANIC em vez de truncar. */
static void AppFit(TDes& aOut, const TDesC& aText)
{
    const TInt livre = aOut.MaxLength() - aOut.Length();
    if (livre <= 0) return;
    aOut.Append(aText.Left(Min(aText.Length(), livre)));
}
static void CopyFit(TDes& aOut, const TDesC& aText)
{
    aOut.Copy(aText.Left(Min(aText.Length(), aOut.MaxLength())));
}

CWolphunAppUi::CWolphunAppUi() : iAppView(0), iEngine(0), iFolders(0), iFsOpen(EFalse), iCapturing(-1), iFullscreen(EFalse) {}

void CWolphunAppUi::ConstructL()
{
    BaseConstructL(CAknAppUi::EAknEnableSkin);   /* habilita o skin nativo da Nokia */

    User::LeaveIfError(iFs.Connect());
    iFsOpen = ETrue;
    iFs.MkDirAll(KRomDirC);                      /* cria a pasta p/ o usuario copiar ROMs */

    iAppView = CWolphunAppView::NewL(ClientRect());
    AddToStackL(iAppView);

    iEngine = CWolphunEngine::NewL(iFs, *this);
    iAppView->SetEngine(iEngine);

    /* lista de pastas de jogos: o usuario pode cadastrar quantas quiser */
    iFolders = CWolphunFolders::NewL(iFs, iEngine->PrivatePath());

    UpdateStatusL();
}

CWolphunAppUi::~CWolphunAppUi()
{
    delete iFolders;
    delete iEngine;
    if (iAppView){ RemoveFromStack(iAppView); delete iAppView; }
    if (iFsOpen) iFs.Close();
}

void CWolphunAppUi::HandleResourceChangeL(TInt aType)
{
    CAknAppUi::HandleResourceChangeL(aType);
    if (aType == KEikDynamicLayoutVariantSwitch && iAppView)
        /* rotacao / mudanca de layout. Em tela cheia o retangulo e a tela TODA:
           usar ClientRect() aqui faria a barra de titulo reaparecer como um
           buraco no desenho depois de girar o aparelho. */
        iAppView->SetRect(iFullscreen ? ApplicationRect() : ClientRect());
}

void CWolphunAppUi::NoteL(TInt aTbufResId)
{
    HBufC* text = StringLoader::LoadLC(aTbufResId);
    CAknInformationNote* note = new (ELeave) CAknInformationNote(ETrue);
    note->ExecuteLD(*text);
    CleanupStack::PopAndDestroy(text);
}

void CWolphunAppUi::NoteT(TInt aKey)
{
    CAknInformationNote* note = new (ELeave) CAknInformationNote(ETrue);
    note->ExecuteLD(WolphunLang::T(aKey));
}

/* Lista de escolha com itens JA no idioma do usuario. O dialogo generico do .rss entra
   vazio e tanto os itens quanto o titulo sao preenchidos aqui -- e assim que da para ter
   6 idiomas sem multiplicar recursos por 6. */
TBool CWolphunAppUi::ShowChoiceListL(const CDesCArray& aItems, const TDesC& aHead, TInt& aValue)
{
    TInt value = aValue;
    if (value < 0 || value >= aItems.MdcaCount()) value = 0;

    CAknListQueryDialog* dlg = new (ELeave) CAknListQueryDialog(&value);
    dlg->PrepareLC(R_WOLPHUN_CHOICE_GENERIC);
    dlg->SetItemTextArray(const_cast<CDesCArray*>(&aItems));
    dlg->SetOwnershipType(ELbmDoesNotOwnItemArray);
    if (dlg->QueryHeading()) dlg->QueryHeading()->SetTextL(aHead);
    const TBool ok = dlg->RunLD();
    if (ok) aValue = value;
    return ok;
}

TBool CWolphunAppUi::ShowChoiceT(const TInt* aKeys, TInt aCount, TInt aHeadKey, TInt& aValue)
{
    CDesCArrayFlat* items = new (ELeave) CDesCArrayFlat(aCount > 0 ? aCount : 1);
    CleanupStack::PushL(items);
    for (TInt i = 0; i < aCount; i++) items->AppendL(WolphunLang::T(aKeys[i]));
    const TBool ok = ShowChoiceListL(*items, WolphunLang::T(aHeadKey), aValue);
    CleanupStack::PopAndDestroy(items);
    return ok;
}

void CWolphunAppUi::UpdateStatusL()
{
    if (!iAppView) return;
    if (iEngine && iEngine->HasGame())
    {
        iAppView->SetStatusText(iEngine->RomName());
    }
    else
    {
        HBufC* text = WolphunLang::T(EStrNoGameNow).AllocLC();
        iAppView->SetStatusText(*text);
        CleanupStack::PopAndDestroy(text);
    }
}

/* ---------------- menu ---------------- */
void CWolphunAppUi::DynInitMenuPaneL(TInt aResourceId, CEikMenuPane* aMenuPane)
{
    /* TEXTO DOS MENUS EM TEMPO DE EXECUCAO. Os txt do .rss viraram so reserva: o
       rotulo mostrado vem da tabela de idiomas, senao seriam 3 menus x 6 idiomas em
       recurso. */
    if (aResourceId == R_WOLPHUN_MENU)
    {
        aMenuPane->SetItemTextL(EWolphunCmdOpenRom,    WolphunLang::T(EStrOpenGame));
        aMenuPane->SetItemTextL(EWolphunCmdBrowseFile, WolphunLang::T(EStrBrowseFile));
        aMenuPane->SetItemTextL(EWolphunCmdFolders,    WolphunLang::T(EStrFolders));
        aMenuPane->SetItemTextL(EWolphunCmdRestart,    WolphunLang::T(EStrRestartGame));
        aMenuPane->SetItemTextL(EWolphunCmdCloseRom,   WolphunLang::T(EStrCloseGame));
        aMenuPane->SetItemTextL(EWolphunCmdSettings,   WolphunLang::T(EStrSettings));
        aMenuPane->SetItemTextL(EWolphunCmdAbout,      WolphunLang::T(EStrAbout));
        aMenuPane->SetItemTextL(EAknSoftkeyExit,       WolphunLang::T(EStrExit));
        /* itens que so fazem sentido com um jogo aberto */
        const TBool has = (iEngine && iEngine->HasGame());
        aMenuPane->SetItemDimmed(EWolphunCmdRestart,  !has);
        aMenuPane->SetItemDimmed(EWolphunCmdCloseRom, !has);
    }
    else if (aResourceId == R_WOLPHUN_FOLDERS_MENU)
    {
        aMenuPane->SetItemTextL(EWolphunCmdAddFolder,    WolphunLang::T(EStrAddFolder));
        aMenuPane->SetItemTextL(EWolphunCmdRemoveFolder, WolphunLang::T(EStrRemoveFolder));
    }
    else if (aResourceId == R_WOLPHUN_SETTINGS_MENU)
    {
        aMenuPane->SetItemTextL(EWolphunCmdSetResolution, WolphunLang::T(EStrResolution));
        aMenuPane->SetItemTextL(EWolphunCmdSetScaling,    WolphunLang::T(EStrScaling));
        aMenuPane->SetItemTextL(EWolphunCmdSetKeyProfile, WolphunLang::T(EStrControls));
        aMenuPane->SetItemTextL(EWolphunCmdEditKeys,      WolphunLang::T(EStrEditKeys));
        aMenuPane->SetItemTextL(EWolphunCmdSetSound,      WolphunLang::T(EStrSound));
        aMenuPane->SetItemTextL(EWolphunCmdSetTexFilter,  WolphunLang::T(EStrTexFilter));
        aMenuPane->SetItemTextL(EWolphunCmdSetPowerSave,  WolphunLang::T(EStrPowerSave));
        aMenuPane->SetItemTextL(EWolphunCmdFullscreen,    WolphunLang::T(EStrFullscreen));
        aMenuPane->SetItemTextL(EWolphunCmdSetFullKey,    WolphunLang::T(EStrFullKey));
        aMenuPane->SetItemTextL(EWolphunCmdSetVibrate,    WolphunLang::T(EStrVibrate));
        aMenuPane->SetItemTextL(EWolphunCmdSetLanguage,   WolphunLang::T(EStrLanguage));
    }
}

/* Qualquer leave aqui dentro fechava o aplicativo inteiro (foi o que aconteceu no E63).
   Agora o comando roda dentro de um TRAP e o erro aparece numa nota -- o emulador
   continua vivo e o usuario consegue dizer QUAL foi o erro. */
void CWolphunAppUi::HandleCommandL(TInt aCommand)
{
    /* SAIR nao pode passar pelo TRAP. CEikAppUi::Exit() encerra o aplicativo FAZENDO
       um leave com KLeaveExit (-1003, basched.h), que o active scheduler captura para
       fechar a aplicacao. Capturar esse leave aqui fazia o app recusar a sair e exibir
       "Erro -1003" -- foi o que apareceu no EKA2L1 e no E63. */
    if (aCommand == EEikCmdExit || aCommand == EAknSoftkeyExit)
    {
        if (iEngine) { iEngine->StopRunning(); iEngine->CloseRom(); }
        Exit();
        return;
    }

    TRAPD(err, DoHandleCommandL(aCommand));
    if (err == KLeaveExit) User::Leave(err);     /* pedido de saida: deixa passar */
    if (err != KErrNone)
    {
        TBuf<64> msg;
        msg.Copy(WolphunLang::T(EStrError));
        msg.AppendNum(err);
        TRAP_IGNORE(
            CAknErrorNote* note = new (ELeave) CAknErrorNote(ETrue);
            note->ExecuteLD(msg);
        );
    }
}

void CWolphunAppUi::DoHandleCommandL(TInt aCommand)
{
    switch (aCommand)
    {
        /* EEikCmdExit / EAknSoftkeyExit sao tratados em HandleCommandL, fora do TRAP */

        case EWolphunCmdOpenRom:
            ShowGameListL();
            break;

        case EWolphunCmdBrowseFile:
        {
            /* navegador NATIVO: abre o .mpn escolhido direto, venha de onde vier */
            TFileName path;
            if (BrowseForMpnL(path, R_WOLPHUN_BROWSE_TITLE))
            {
                TRAPD(err, iEngine->LoadRomL(path));
                if (err == KErrNone && iFolders)
                {
                    /* a pasta de onde veio este jogo passa a alimentar "Abrir jogo":
                       abrir UM jogo de uma pasta ja cadastra a pasta inteira. */
                    TParsePtrC parse(path);
                    TRAP_IGNORE(iFolders->AddL(parse.DriveAndPath()));
                }
                UpdateStatusL();
                iAppView->DrawDeferred();
            }
            break;
        }

        case EWolphunCmdAddFolder:
            AddFolderL();
            break;

        case EWolphunCmdRemoveFolder:
            RemoveFolderL();
            break;

        case EWolphunCmdRestart:
            if (iEngine && iEngine->HasGame()){ iEngine->RestartL(); UpdateStatusL(); }
            break;

        case EWolphunCmdCloseRom:
            SetFullScreenL(EFalse);   /* sem jogo na tela, tela cheia nao faz sentido */
            if (iEngine){ iEngine->CloseRom(); UpdateStatusL(); iAppView->DrawDeferred(); }
            break;

        case EWolphunCmdSetResolution:
        case EWolphunCmdSetScaling:
        case EWolphunCmdSetKeyProfile:
        case EWolphunCmdSetSound:
        case EWolphunCmdSetTexFilter:
        case EWolphunCmdEditKeys:          /* faltava aqui: o item existia no menu mas caia no default */
        case EWolphunCmdSetPowerSave:
        case EWolphunCmdSetLanguage:
        case EWolphunCmdSetFullKey:
        case EWolphunCmdSetVibrate:
            ShowSettingL(aCommand);
            break;

        case EWolphunCmdFullscreen:
            /* sem jogo aberto isto so esconderia a interface e deixaria uma tela
               vazia -- aviso em vez de obedecer. */
            if (iEngine && iEngine->HasGame()) SetFullScreenL(!iFullscreen);
            else NoteT(EStrNoGameNow);
            break;

        case EWolphunCmdAbout:
            ShowAboutL();
            break;

        default:
            break;
    }
}

/* ---------------- Sobre ----------------
 *
 * MESMA estrutura e MESMAS traducoes do dialogo da versao desktop (Emulator.aboutBody()
 * + Branding.java): nome, lema, versao, desenvolvedor e ESTUDIO. O estudio e "Maned
 * Software" -- referencia ao lobo-guara, em ingles maned wolf, de onde sai tambem o nome
 * do emulador. Mantido literal, como no desktop: nome proprio nao se traduz.
 *
 * Aqui o dialogo e texto puro, nao HTML como no desktop, entao o que la era negrito vira
 * so a quebra de linha. O texto do recurso (R_WOLPHUN_ABOUT_TXT) deixou de ser usado:
 * era portugues fixo e nao acompanhava a escolha de idioma.
 */
void CWolphunAppUi::ShowAboutL()
{
    HBufC* full = HBufC::NewLC(512);
    TPtr p = full->Des();

    p.Copy(KWolphunAppName);
    p.Append(KNl);
    AppFit(p, WolphunLang::T(EStrAboutTagline));
    p.Append(KNl);
    p.Append(KNl);

    AppFit(p, WolphunLang::T(EStrAboutVersion));
    p.Append(KSp);
    p.Append(KWolphunVersion);
    p.Append(KSp);
    AppFit(p, WolphunLang::T(EStrAboutBeta));
    p.Append(KNl);

    AppFit(p, WolphunLang::T(EStrAboutDev));
    p.Append(KSep);
    p.Append(KWolphunAuthor);
    p.Append(KNl);

    AppFit(p, WolphunLang::T(EStrAboutStudio));
    p.Append(KSep);
    p.Append(KWolphunStudio);
    p.Append(KNl);
    p.Append(KNl);

    AppFit(p, WolphunLang::T(EStrAboutPort));
    p.Append(KNl);
    p.Append(KWolphunCopyright);

    /* Junta o estado do audio: 0 = o aparelho aceitou abrir; qualquer outro numero e o
       codigo de erro que ELE devolveu. "pedidos" = quantas vezes o jogo pediu som.
       Sem isto, "nao sai som" nao diz se a culpa e do emulador, do aparelho ou do jogo. */
    if (iEngine)
    {
        TBuf<160> snd;
        iEngine->AudioDiagnostics(snd);
        p.Append(KNl);
        p.Append(KNl);
        AppFit(p, snd);
    }

    CAknMessageQueryDialog* dlg = new (ELeave) CAknMessageQueryDialog();
    dlg->PrepareLC(R_AVKON_MESSAGE_QUERY_DIALOG);
    dlg->SetMessageTextL(*full);
    dlg->RunLD();

    CleanupStack::PopAndDestroy(full);
}

/* ---------------- lista de jogos (dialogo nativo) ---------------- */
void CWolphunAppUi::ShowGameListL()
{
    CDesCArrayFlat* names = new (ELeave) CDesCArrayFlat(8);
    CleanupStack::PushL(names);
    CDesCArrayFlat* paths = new (ELeave) CDesCArrayFlat(8);
    CleanupStack::PushL(paths);

    /* BULK: varre TODAS as pastas cadastradas pelo usuario, de uma vez */
    const TInt folderCount = iFolders ? iFolders->Count() : 0;
    for (TInt d = 0; d < folderCount; d++)
    {
        const TPtrC dirPath = iFolders->At(d);
        CDir* dir = 0;
        if (iFs.GetDir(dirPath, KEntryAttNormal, ESortByName, dir) != KErrNone) continue;
        CleanupStack::PushL(dir);
        for (TInt i = 0; i < dir->Count(); i++)
        {
            const TEntry& e = (*dir)[i];
            if (e.iName.Right(4).CompareF(KMpnExt) != 0) continue;
            names->AppendL(e.iName);
            TFileName full;
            full.Append(dirPath);
            full.Append(e.iName);
            paths->AppendL(full);
        }
        CleanupStack::PopAndDestroy(dir);
    }

    if (names->Count() == 0)
    {
        CleanupStack::PopAndDestroy(2);   /* paths, names */
        NoteT(EStrNoGames);
        return;
    }

    TInt index = 0;
    CAknListQueryDialog* dlg = new (ELeave) CAknListQueryDialog(&index);
    dlg->PrepareLC(R_WOLPHUN_GAME_LIST);
    /* o heading do .rss e portugues fixo; o texto de verdade vem da tabela */
    if (dlg->QueryHeading()) dlg->QueryHeading()->SetTextL(WolphunLang::T(EStrHeadGames));
    dlg->SetItemTextArray(names);
    dlg->SetOwnershipType(ELbmDoesNotOwnItemArray);
    const TBool ok = dlg->RunLD();

    if (ok && index >= 0 && index < paths->Count())
    {
        TFileName chosen = (*paths)[index];
        CleanupStack::PopAndDestroy(2);   /* paths, names */
        TRAPD(err, iEngine->LoadRomL(chosen));
        if (err == KErrNone) UpdateStatusL();
        else UpdateStatusL();             /* o erro ja foi mostrado pelo motor */
        iAppView->DrawDeferred();
        return;
    }
    CleanupStack::PopAndDestroy(2);       /* paths, names */
}

/* ---------------- navegador de arquivos NATIVO ----------------
   AknCommonDialogs mostra a escolha de memoria (telefone / cartao) e depois o
   navegador de pastas do proprio sistema -- o mesmo do Gestor de Arquivos. */
TBool CWolphunAppUi::BrowseForMpnL(TDes& aPath, TInt /*aTitleResId*/)
{
    /* O titulo e o filtro ".mpn" vem do recurso FILESELECTIONDIALOG: nao existe
       sobrecarga que aceite titulo E filtro como parametros, mas o recurso aceita
       os dois juntos. */
    aPath.Zero();
    return AknCommonDialogs::RunSelectDlgLD(
        aPath, R_WOLPHUN_MEMORY_SELECTION, R_WOLPHUN_FILE_SELECTION);
}

/* Cadastra mais uma pasta: o usuario navega ate ela e escolhe QUALQUER .mpn de
   dentro; guardamos a pasta que contem o arquivo. (O S60 3rd nao tem um dialogo
   de "selecionar pasta", entao esse e o caminho nativo.) */
void CWolphunAppUi::AddFolderL()
{
    /* RunMoveDlgLD e o seletor de PASTA do proprio sistema: o usuario navega e confirma
       a pasta com a tecla do meio (nao precisa escolher um arquivo dentro dela). */
    TFileName folder;
    if (!AknCommonDialogs::RunMoveDlgLD(folder, R_WOLPHUN_MEMORY_SELECTION)) return;
    if (folder.Length() == 0) return;
    const TBool added = iFolders->AddL(folder);
    NoteT(added ? EStrFolderAdded : EStrFolderDup);
}

void CWolphunAppUi::RemoveFolderL()
{
    const TInt count = iFolders ? iFolders->Count() : 0;
    if (count == 0){ NoteT(EStrNoFolders); return; }

    CDesCArrayFlat* items = new (ELeave) CDesCArrayFlat(4);
    CleanupStack::PushL(items);
    for (TInt i = 0; i < count; i++) items->AppendL(iFolders->At(i));

    TInt index = 0;
    CAknListQueryDialog* dlg = new (ELeave) CAknListQueryDialog(&index);
    dlg->PrepareLC(R_WOLPHUN_FOLDER_LIST);
    if (dlg->QueryHeading()) dlg->QueryHeading()->SetTextL(WolphunLang::T(EStrHeadFolders));
    dlg->SetItemTextArray(items);
    dlg->SetOwnershipType(ELbmDoesNotOwnItemArray);
    const TBool ok = dlg->RunLD();

    if (ok && index >= 0 && index < count) iFolders->RemoveL(index);
    CleanupStack::PopAndDestroy(items);
}

/* ---------------- listas de configuracao (lista de consulta nativa) ----------------
   Antes isto usava CAknRadioButtonSettingPage com R_AVKON_DEFAULT_SETTING_PAGE -- mas
   esse recurso tem type=0xffff e editor_resource_id=0, ou seja NAO e uma pagina com
   listbox, e o aplicativo FECHAVA ao abrir qualquer configuracao (relatado no E63).
   A lista de consulta e o mesmo widget da lista de jogos: nativo, confirmado com a
   tecla do meio, e o indice ja entra pre-selecionado. */
/* Lista de escolha NATIVA. Os itens vem do proprio recurso do dialogo (array_id no
   LISTBOX), igual ao exemplo S60Ex/HWRMTestApp do SDK -- nao ha array montado em codigo,
   entao nao ha SetItemTextArray aqui. */
TBool CWolphunAppUi::ShowChoiceL(TInt aItemCount, TInt aDialogResId, TInt& aValue)
{
    TInt value = aValue;
    if (value < 0 || value >= aItemCount) value = 0;

    CAknListQueryDialog* dlg = new (ELeave) CAknListQueryDialog(&value);
    dlg->PrepareLC(aDialogResId);
    const TBool ok = dlg->RunLD();

    if (ok) aValue = value;
    return ok;
}

void CWolphunAppUi::ShowSettingL(TInt aCommand)
{
    if (!iEngine) return;
    TWolphunConfig& cfg = iEngine->Config();

    switch (aCommand)
    {
        case EWolphunCmdSetResolution:
        {
            /* A resolucao e POR JOGO e o jogo le o tamanho da tela em vGetCaps durante a
               inicializacao -- por isso trocar exige reiniciar a sessao. */
            const TInt before = cfg.iResolution;
            /* os 7 primeiros sao rotulos TECNICOS (nomes de aparelho e numeros), que
               nao se traduzem; so o "Automatica" tem texto de verdade. */
            CDesCArrayFlat* res = new (ELeave) CDesCArrayFlat(EWolphunResCount);
            CleanupStack::PushL(res);
            _LIT(KR0,"101x80  (SE T300)");    res->AppendL(KR0);
            _LIT(KR1,"128x160 (SE T610)");    res->AppendL(KR1);
            _LIT(KR2,"176x208 (Nokia S60)");  res->AppendL(KR2);
            _LIT(KR3,"176x220 (Sendo X)");    res->AppendL(KR3);
            _LIT(KR4,"208x320 (SE P800)");    res->AppendL(KR4);
            _LIT(KR5,"240x320 (QVGA)");       res->AppendL(KR5);
            _LIT(KR6,"320x240 (QVGA)");       res->AppendL(KR6);
            res->AppendL(WolphunLang::T(EStrResAuto));
            const TBool okRes = ShowChoiceListL(*res, WolphunLang::T(EStrResolution), cfg.iResolution);
            CleanupStack::PopAndDestroy(res);
            if (!okRes) return;
            iEngine->SaveConfigL();
            if (cfg.iResolution != before && iEngine->HasGame())
            {
                NoteT(EStrRestartRes);
                iEngine->RestartL();
            }
            break;
        }
        case EWolphunCmdSetScaling: {
            static const TInt k[] = { EStrScaleCenter, EStrScaleAspect, EStrScaleFill };
            if (ShowChoiceT(k, 3, EStrScaling, cfg.iScaling))
                iEngine->SaveConfigL();
            break; }

        case EWolphunCmdSetKeyProfile: {
            static const TInt k[] = { EStrKeyAuto, EStrKeyQwerty, EStrKeyNumeric,
                                      EStrKeyJoystick, EStrKeyCustom };
            if (ShowChoiceT(k, EWolphunKeyProfileCount, EStrControls, cfg.iKeyProfile))
                iEngine->SaveConfigL();
            break; }

        case EWolphunCmdEditKeys:
            EditKeysL();
            break;

        /* mesma mecanica do editor de teclas: a captura acontece na PROXIMA tecla,
           ja com o dialogo fechado (ver HandleKeyEventL). */
        case EWolphunCmdSetFullKey:
            iCapturing = KCapFullKey;
            NoteT(EStrKeyPressNow);
            break;

        /* A vibracao e recriada pelo ApplyLiveSettings: ligar ou desligar vale na hora,
           sem reiniciar o jogo, igual ao som. */
        case EWolphunCmdSetVibrate: {
            static const TInt k[] = { EStrOff, EStrOn };
            if (ShowChoiceT(k, 2, EStrVibrate, cfg.iVibrate))
            {
                iEngine->ApplyLiveSettings();
                iEngine->SaveConfigL();
            }
            break; }

        case EWolphunCmdSetSound: {
            static const TInt k[] = { EStrOff, EStrOn };
            if (ShowChoiceT(k, 2, EStrSound, cfg.iSound))
            {
                iEngine->ApplyLiveSettings();
                iEngine->SaveConfigL();
            }
            break; }

        case EWolphunCmdSetTexFilter: {
            static const TInt k[] = { EStrTfGame, EStrTfLinear, EStrTfNearest };
            if (ShowChoiceT(k, 3, EStrTexFilter, cfg.iTexFilter))
            {
                iEngine->ApplyLiveSettings();
                iEngine->SaveConfigL();
            }
            break; }

        case EWolphunCmdSetPowerSave: {
            static const TInt k[] = { EStrPsMax, EStrPsHigh, EStrPsMed, EStrPsOff };
            if (ShowChoiceT(k, 4, EStrPowerSave, cfg.iPowerSave))
            {
                iEngine->ApplyLiveSettings();
                iEngine->SaveConfigL();
            }
            break; }

        /* IDIOMA: a lista NAO se traduz -- cada idioma aparece escrito nele mesmo, que
           e como o usuario reconhece o proprio. Vale na hora, sem reiniciar. */
        case EWolphunCmdSetLanguage: {
            CDesCArrayFlat* langs = new (ELeave) CDesCArrayFlat(WolphunLang::EWolphunLangCount);
            CleanupStack::PushL(langs);
            for (TInt i = 0; i < WolphunLang::EWolphunLangCount; i++)
                langs->AppendL(WolphunLang::LanguageName(i));
            const TBool okLang = ShowChoiceListL(*langs, WolphunLang::T(EStrLanguage), cfg.iLanguage);
            CleanupStack::PopAndDestroy(langs);
            if (okLang)
            {
                WolphunLang::SetLanguage(cfg.iLanguage);
                iEngine->SaveConfigL();
                UpdateStatusL();
            }
            break; }

        default: break;
    }
    if (iAppView) iAppView->DrawDeferred();
}

/* ---------------- editor do mapa livre de teclas ----------------
   Mesma logica do emulador de PC: cada ACAO tem uma lista de teclas e a tecla pressionada
   ALTERNA (entra se nao estava, sai se estava). A diferenca e que no Symbian nao da para
   capturar tecla com um dialogo modal aberto -- o dialogo consome os eventos -- entao o
   fluxo e: lista de acoes -> escolhe uma -> o dialogo FECHA -> a proxima tecla e capturada
   pelo HandleKeyEventL. O aviso na tela diz o que fazer.
   Funciona com o teclado QWERTY (E63) e com o numerico porque capturamos o SCAN CODE cru,
   sem supor qual teclado o aparelho tem. */

void CWolphunAppUi::KeyNameL(TInt aScanCode, TDes& aOut)
{
    aOut.Zero();
    switch (aScanCode)
    {
        case EStdKeyUpArrow:    CopyFit(aOut, WolphunLang::T(EStrKeyUp));     return;
        case EStdKeyDownArrow:  CopyFit(aOut, WolphunLang::T(EStrKeyDown));   return;
        case EStdKeyLeftArrow:  CopyFit(aOut, WolphunLang::T(EStrKeyLeft));   return;
        case EStdKeyRightArrow: CopyFit(aOut, WolphunLang::T(EStrKeyRight));  return;
        case EStdKeyDevice3:    CopyFit(aOut, WolphunLang::T(EStrKeyCentre)); return;
        case EStdKeySpace:      CopyFit(aOut, WolphunLang::T(EStrKeySpace));  return;
        case EStdKeyBackspace:  CopyFit(aOut, WolphunLang::T(EStrKeyBksp));   return;
        case EStdKeyLeftShift:  CopyFit(aOut, WolphunLang::T(EStrKeyLShift)); return;
        case EStdKeyRightShift: CopyFit(aOut, WolphunLang::T(EStrKeyRShift)); return;
        /* estes tres nao se traduzem: sao o proprio simbolo da tecla */
        case EStdKeyEnter:      aOut.Copy(_L("Enter"));        return;
        case EStdKeyHash:       aOut.Copy(_L("#"));            return;
        case EStdKeyNkpAsterisk:aOut.Copy(_L("*"));            return;
        default: break;
    }
    /* imprimivel (letra do QWERTY, digito do teclado numerico): mostra o proprio caractere */
    if (aScanCode > 32 && aScanCode < 127){ aOut.Append((TChar)aScanCode); return; }
    CopyFit(aOut, WolphunLang::T(EStrKeyCode));
    if (aOut.Length() + 8 <= aOut.MaxLength()) aOut.AppendNum(aScanCode);
}

void CWolphunAppUi::KeysTextL(TInt aAction, TDes& aOut) const
{
    aOut.Zero();
    if (!iEngine) return;
    const TWolphunConfig& cfg = iEngine->Config();
    TBuf<24> nome;
    for (TInt k = 0; k < KWolphunKeysPerAction; k++)
    {
        const TInt sc = cfg.iBind[aAction][k];
        if (sc == 0) continue;
        KeyNameL(sc, nome);
        if (aOut.Length() + nome.Length() + 2 > aOut.MaxLength()) break;
        if (aOut.Length()) aOut.Append(_L(", "));
        aOut.Append(nome);
    }
    if (aOut.Length() == 0) aOut.Copy(_L("---"));
}

void CWolphunAppUi::ToggleBindL(TInt aAction, TInt aScanCode)
{
    if (!iEngine || aAction < 0 || aAction >= EWolphunActionCount) return;
    TWolphunConfig& cfg = iEngine->Config();

    /* ja estava nesta acao? entao a tecla SAI (foi isso que o usuario pediu ao repetir) */
    for (TInt k = 0; k < KWolphunKeysPerAction; k++)
        if (cfg.iBind[aAction][k] == aScanCode)
        {
            cfg.iBind[aAction][k] = 0;
            iEngine->SaveConfigL();
            NoteT(EStrKeyRemoved);
            return;
        }

    /* uma tecla nao pode servir a duas acoes ao mesmo tempo: tira das outras antes */
    for (TInt a = 0; a < EWolphunActionCount; a++)
        for (TInt k = 0; k < KWolphunKeysPerAction; k++)
            if (cfg.iBind[a][k] == aScanCode) cfg.iBind[a][k] = 0;

    for (TInt k = 0; k < KWolphunKeysPerAction; k++)
        if (cfg.iBind[aAction][k] == 0)
        {
            cfg.iBind[aAction][k] = aScanCode;
            /* Guardar tecla so serve se o perfil for o Personalizado -- trocar sozinho
               evita o "configurei e nao mudou nada". */
            cfg.iKeyProfile = EWolphunKeysCustom;
            iEngine->SaveConfigL();
            NoteT(EStrKeySet);
            return;
        }

    NoteT(EStrKeyFull);      /* 3 teclas ja ocupadas nessa acao */
}

void CWolphunAppUi::EditKeysL()
{
    if (!iEngine) return;

    CDesCArrayFlat* items = new (ELeave) CDesCArrayFlat(EWolphunActionCount);
    CleanupStack::PushL(items);
    /* 128, nao 80: os nomes de acao agora sao TRADUZIDOS, e em russo
       "Right softkey (Fire2)" vira "1f403032304f ...", bem mais longo.
       Com 80 a soma nome + ": " + ate tres nomes de tecla passava do limite, e Append
       alem do MaxLength faz PANIC em vez de truncar -- dai tambem os AppFit abaixo. */
    TBuf<128> linha; TBuf<64> teclas;
    for (TInt a = 0; a < EWolphunActionCount; a++)
    {
        KeysTextL(a, teclas);
        linha.Zero();
        AppFit(linha, TWolphunConfig::ActionName(a));
        AppFit(linha, _L(": "));
        AppFit(linha, teclas);
        items->AppendL(linha);
    }

    TInt index = 0;
    CAknListQueryDialog* dlg = new (ELeave) CAknListQueryDialog(&index);
    dlg->PrepareLC(R_WOLPHUN_KEYS_LIST);
    if (dlg->QueryHeading()) dlg->QueryHeading()->SetTextL(WolphunLang::T(EStrEditKeys));
    dlg->SetItemTextArray(items);
    dlg->SetOwnershipType(ELbmDoesNotOwnItemArray);
    const TBool ok = dlg->RunLD();
    CleanupStack::PopAndDestroy(items);

    if (!ok || index < 0 || index >= EWolphunActionCount) return;

    /* a captura acontece depois que o dialogo fecha (ver HandleKeyEventL) */
    iCapturing = index;
    NoteT(EStrKeyPressNow);
}

/* ---------------- teclado ----------------
   Mapeia tanto o d-pad/joystick quanto o teclado NUMERICO e o QWERTY dos E-series.
   O perfil escolhido em Configuracoes > Controles filtra o que vale. */
TInt CWolphunAppUi::MapScanCode(TInt aScanCode) const
{
    const TInt profile = iEngine ? iEngine->Config().iKeyProfile : EWolphunKeysAuto;

    /* PERSONALIZADO: so vale o mapa livre montado em Configuracoes > Editar teclas.
       Deliberadamente NAO caimos nos perfis depois -- se o usuario tirou uma tecla, ela
       tem de ficar fora mesmo; cair no perfil faria a remocao parecer que nao funcionou. */
    if (profile == EWolphunKeysCustom)
    {
        const TWolphunConfig& cfg = iEngine->Config();
        for (TInt a = 0; a < EWolphunActionCount; a++)
            for (TInt k = 0; k < KWolphunKeysPerAction; k++)
                if (cfg.iBind[a][k] != 0 && cfg.iBind[a][k] == aScanCode)
                    return TWolphunConfig::ActionBit(a);
        return 0;
    }

    /* joystick/d-pad: sempre ativo (todo aparelho tem) */
    switch (aScanCode)
    {
        case EStdKeyUpArrow:    return Screen::UP;
        case EStdKeyDownArrow:  return Screen::DOWN;
        case EStdKeyLeftArrow:  return Screen::LEFT;
        case EStdKeyRightArrow: return Screen::RIGHT;
        case EStdKeyDevice3:    return Screen::FIRE;    /* tecla central do joystick */
        default: break;
    }
    if (profile == EWolphunKeysJoystickOnly) return 0;

    /* QWERTY (E-series): WASD + espaco/enter */
    if (profile == EWolphunKeysAuto || profile == EWolphunKeysQwerty)
    {
        switch (aScanCode)
        {
            case 'W': return Screen::UP;
            case 'S': return Screen::DOWN;
            case 'A': return Screen::LEFT;
            case 'D': return Screen::RIGHT;
            case EStdKeySpace: return Screen::FIRE;
            case EStdKeyEnter: return Screen::FIRE;
            case 'Q': return Screen::SELECT;
            case 'E': return Screen::FIRE2;
            default: break;
        }
    }
    /* teclado numerico do celular */
    if (profile == EWolphunKeysAuto || profile == EWolphunKeysNumeric)
    {
        switch (aScanCode)
        {
            case '2': return Screen::UP;
            case '8': return Screen::DOWN;
            case '4': return Screen::LEFT;
            case '6': return Screen::RIGHT;
            case '5': return Screen::FIRE;
            case '0': return Screen::SELECT;
            case EStdKeyHash: return Screen::FIRE2;
            default: break;
        }
    }
    return 0;
}

/* Alem dos bits de botao, varios jogos leem as teclas numericas LITERAIS por
   vTestKey/vScanKeys (ex.: Bomb Jack move com 2/3). Repassamos o ASCII tambem. */
TInt CWolphunAppUi::MapKeypadAscii(TInt aScanCode) const
{
    /* PERSONALIZADO: as teclas literais tambem saem do mapa livre. E o que torna jogavel
       num QWERTY um jogo que anda pelas teclas do telefone (Bomb Jack usa 2 e 3): o
       usuario aponta, por exemplo, J e L para "Tecla 2" e "Tecla 3". */
    if (iEngine && iEngine->Config().iKeyProfile == EWolphunKeysCustom)
    {
        const TWolphunConfig& cfg = iEngine->Config();
        for (TInt a = EWolphunActKey0; a <= EWolphunActKeyStar; a++)
            for (TInt k = 0; k < KWolphunKeysPerAction; k++)
                if (cfg.iBind[a][k] != 0 && cfg.iBind[a][k] == aScanCode)
                    return TWolphunConfig::ActionAscii(a);
        return -1;
    }
    if (aScanCode >= '0' && aScanCode <= '9') return aScanCode;
    if (aScanCode == EStdKeyHash)     return '#';
    if (aScanCode == EStdKeyNkpAsterisk) return '*';
    /* Backspace = tecla "voltar" do aparelho (203). Antes o Symbian nao enviava nenhuma
       tecla de aparelho, entao jogos que so saem de um menu pelo BACK ficavam presos. */
    if (aScanCode == EStdKeyBackspace) return 203;
    return -1;
}

/* ---------------- tela cheia ----------------
 *
 * O jogo passa a ocupar a tela INTEIRA: somem a barra de titulo (status pane) e as
 * softkeys (CBA). Nao ha esticamento nenhum nisto -- quem decide a geometria continua
 * sendo o "Ajuste na tela"; a tela cheia so AUMENTA o retangulo disponivel, e num
 * aparelho como o E63 isso devolve as 44 linhas que o sistema ocupava.
 *
 * Esconder o CBA NAO desliga as softkeys fisicas: o HandleKeyEventL deixa EStdKeyDevice0
 * e EStdKeyDevice1 passarem SEMPRE, entao a softkey esquerda continua abrindo o menu de
 * Opcoes por cima do jogo. Essa e a segunda saida, caso a tecla escolhida nao funcione
 * no aparelho -- e e por isso que da para entregar isto sem risco de prender o usuario.
 * A terceira e perder o primeiro plano (tecla vermelha, chamada recebida): ver
 * HandleForegroundEventL.
 */
void CWolphunAppUi::SetFullScreenL(TBool aOn)
{
    if (!iAppView) return;
    aOn = aOn ? (TBool)ETrue : (TBool)EFalse;
    if (aOn == iFullscreen) return;
    iFullscreen = aOn;

    CEikStatusPane* sp = StatusPane();
    if (sp) sp->MakeVisible(!aOn);
    CEikButtonGroupContainer* cba = Cba();
    if (cba) cba->MakeVisible(!aOn);

    /* ClientRect() ja desconta titulo e softkeys; ApplicationRect() e a tela toda.
       O proprio SetRect dispara o SizeChanged da view, que reposiciona o fundo do tema. */
    iAppView->SetRect(aOn ? ApplicationRect() : ClientRect());
    iAppView->DrawDeferred();
}

/* Sair do primeiro plano SEMPRE devolve a interface normal. Duas razoes: um aplicativo
   em segundo plano nao pode ficar segurando a tela cheia, e isto e a rede de seguranca
   -- a tecla vermelha sempre existe, entao nunca da para ficar presa sem interface. */
void CWolphunAppUi::HandleForegroundEventL(TBool aForeground)
{
    CAknAppUi::HandleForegroundEventL(aForeground);
    if (!aForeground && iFullscreen) SetFullScreenL(EFalse);
}

TKeyResponse CWolphunAppUi::HandleKeyEventL(const TKeyEvent& aKeyEvent, TEventCode aType)
{
    if (aType != EEventKeyDown && aType != EEventKeyUp) return EKeyWasNotConsumed;

    /* MODO DE CAPTURA (Configuracoes > Editar teclas): a proxima tecla vira o atalho da
       acao escolhida, em vez de ir para o jogo. Fica ANTES do teste de "tem jogo aberto"
       para dar para remapear com a lista de jogos na tela. A softkey esquerda continua
       passando direto, senao o usuario perderia o acesso ao menu. */
    if (iCapturing != -1 && aType == EEventKeyDown
        && aKeyEvent.iScanCode != EStdKeyDevice0 && aKeyEvent.iScanCode != EStdKeyDevice1)
    {
        const TInt acao = iCapturing;
        iCapturing = -1;
        if (acao == KCapFullKey)
        {
            /* uma tecla so, e ela substitui a anterior -- nao ha o acumulo de ate 3 que
               faz sentido para as acoes de jogo. */
            if (iEngine)
            {
                iEngine->Config().iFullKey = aKeyEvent.iScanCode;
                iEngine->SaveConfigL();
            }
            NoteT(EStrKeySet);
        }
        else ToggleBindL(acao, aKeyEvent.iScanCode);
        return EKeyWasConsumed;
    }

    /* TELA CHEIA: testada ANTES do mapeamento do jogo e em QUALQUER perfil de teclas,
       senao ela so valeria no perfil Personalizado. So no KeyDown -- no KeyUp
       alternaria de novo e o modo nunca mudaria de fato. */
    if (aType == EEventKeyDown && iEngine && iEngine->HasGame()
        && iEngine->Config().iFullKey != 0
        && aKeyEvent.iScanCode == iEngine->Config().iFullKey)
    {
        SetFullScreenL(!iFullscreen);
        return EKeyWasConsumed;
    }

    if (!iEngine || !iEngine->HasGame()) return EKeyWasNotConsumed;

    /* a softkey esquerda (menu de Opcoes) NUNCA e capturada pelo jogo: e o acesso do
       usuario a todas as configuracoes. */
    if (aKeyEvent.iScanCode == EStdKeyDevice0 || aKeyEvent.iScanCode == EStdKeyDevice1)
        return EKeyWasNotConsumed;

    const TBool down = (aType == EEventKeyDown);
    TBool used = EFalse;

    const TInt bit = MapScanCode(aKeyEvent.iScanCode);
    if (bit){ iEngine->SetButton(bit, down); used = ETrue; }

    const TInt ascii = MapKeypadAscii(aKeyEvent.iScanCode);
    if (ascii >= 0){ iEngine->SetKeypad(ascii, down); used = ETrue; }

    return used ? EKeyWasConsumed : EKeyWasNotConsumed;
}

/* ---------------- observador do motor ---------------- */
void CWolphunAppUi::FrameReady()
{
    if (iAppView) iAppView->DrawNow();
}

void CWolphunAppUi::GameStopped()
{
    TRAP_IGNORE(UpdateStatusL());
    if (iAppView) iAppView->DrawDeferred();
}
