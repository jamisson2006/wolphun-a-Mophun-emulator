/* WolphunAppUi.h - comandos do menu da softkey esquerda, listas de configuracao
 * nativas do sistema e mapeamento de teclas (QWERTY e numerico).
 */
#ifndef WOLPHUNAPPUI_H
#define WOLPHUNAPPUI_H

#include <aknappui.h>
#include "WolphunEngine.h"

class CWolphunAppView;

class CWolphunAppUi : public CAknAppUi, public MWolphunObserver
{
public:
    void ConstructL();
    CWolphunAppUi();
    ~CWolphunAppUi();

    /* CAknAppUi */
    void HandleCommandL(TInt aCommand);        /* envolve DoHandleCommandL num TRAP */
    void HandleResourceChangeL(TInt aType);
    TKeyResponse HandleKeyEventL(const TKeyEvent& aKeyEvent, TEventCode aType);
    void DynInitMenuPaneL(TInt aResourceId, CEikMenuPane* aMenuPane);
    /* saida garantida da tela cheia: perder o primeiro plano sempre devolve a
       interface do sistema (ver SetFullScreenL). */
    void HandleForegroundEventL(TBool aForeground);

    /* MWolphunObserver */
    void FrameReady();
    void GameStopped();

private:
    /* o comando de verdade; um leave aqui vira uma nota de erro, nao o fim do app */
    void DoHandleCommandL(TInt aCommand);
    void ShowGameListL();
    /* navegador de arquivos NATIVO do sistema (CommonDialogs).
       Devolve ETrue e o caminho completo escolhido. */
    TBool BrowseForMpnL(TDes& aPath, TInt aTitleResId);
    void  AddFolderL();
    void  RemoveFolderL();
    /* lista de escolha NATIVA do sistema (itens vem do recurso do dialogo);
       devolve ETrue se o usuario confirmou */
    TBool ShowChoiceL(TInt aItemCount, TInt aDialogResId, TInt& aValue);
    /** lista de escolha TRADUZIDA: itens e titulo vem da tabela de idiomas */
    TBool ShowChoiceT(const TInt* aKeys, TInt aCount, TInt aHeadKey, TInt& aValue);
    /** idem, mas o 1o item e uma lista de rotulos tecnicos que nao se traduz */
    TBool ShowChoiceListL(const CDesCArray& aItems, const TDesC& aHead, TInt& aValue);
    /** Editor do mapa livre: lista as acoes e entra em modo de captura na escolhida. */
    void EditKeysL();
    /** Texto das teclas de uma acao, p/ a lista ("Seta cima, W, 2" ou "---"). */
    void KeysTextL(TInt aAction, TDes& aOut) const;
    /** Nome legivel de um scan code. */
    static void KeyNameL(TInt aScanCode, TDes& aOut);
    /** Guarda/remove um scan code numa acao (alterna, igual ao emulador de PC). */
    void ToggleBindL(TInt aAction, TInt aScanCode);
    /** >=0 enquanto espera a proxima tecla para a acao de indice iCapturing.
        O valor KCapFullKey (abaixo) nao e uma acao de jogo: e a tecla de tela cheia. */
    TInt iCapturing;
    /** sentinela de iCapturing para a tecla de tela cheia. Vale -2 porque -1 ja
        significa "nao esta capturando" e >=0 e indice de acao. */
    enum { KCapFullKey = -2 };

    /** Tela cheia: o quadro do jogo ocupa a tela toda, sem barra de titulo nem
        softkeys. Estado de execucao, nao vai para a config: sair do app ou perder
        o primeiro plano sempre devolve a interface normal. */
    TBool iFullscreen;
    void SetFullScreenL(TBool aOn);
    void  ShowSettingL(TInt aCommand);
    void  ShowAboutL();
    void  NoteL(TInt aTbufResId);
    /** nota com texto da tabela de idiomas */
    void  NoteT(TInt aKey);
    void  UpdateStatusL();
    /* mapeia uma tecla do aparelho para os bits do mophun; 0 = nao mapeada */
    TInt  MapScanCode(TInt aScanCode) const;
    TInt  MapKeypadAscii(TInt aScanCode) const;

    CWolphunAppView* iAppView;
    CWolphunEngine*  iEngine;
    CWolphunFolders* iFolders;      /* pastas de jogos cadastradas pelo usuario */
    RFs              iFs;
    TBool            iFsOpen;
};

#endif /* WOLPHUNAPPUI_H */
