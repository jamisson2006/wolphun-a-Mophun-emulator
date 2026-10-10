/* WolphunAppView.h - area de desenho do emulador.
 *
 * Quando NAO ha jogo aberto, desenha o FUNDO NATIVO DA NOKIA (o skin do tema que o
 * usuario escolheu no aparelho, via AknsDrawUtils) com uma mensagem - assim a tela do
 * emulador tem a mesma cara do resto do sistema. Com um jogo aberto, o quadro da VM e
 * blitado por cima, centralizado/ampliado conforme a configuracao.
 */
#ifndef WOLPHUNAPPVIEW_H
#define WOLPHUNAPPVIEW_H

#include <coecntrl.h>

class CWolphunEngine;
class CAknsBasicBackgroundControlContext;   /* contexto do skin (tema) da Nokia */

class CWolphunAppView : public CCoeControl
{
public:
    static CWolphunAppView* NewL(const TRect& aRect);
    ~CWolphunAppView();

    void SetEngine(CWolphunEngine* aEngine){ iEngine = aEngine; }
    void SetStatusText(const TDesC& aText);

    /* CCoeControl */
    void Draw(const TRect& aRect) const;

private:
    /* desenha texto branco sobre tarja preta (legivel em qualquer tema/fundo) */
    void DrawLabel(CWindowGc& aGc, const TDesC& aText,
                   const TPoint& aBaseline, const CFont* aFont) const;

public:
    void SizeChanged();
    /* fornece o contexto de skin aos controles filhos (fundo do tema da Nokia) */
    TTypeUid::Ptr MopSupplyObject(TTypeUid aId);

private:
    CWolphunAppView();
    void ConstructL(const TRect& aRect);
    /* onde o quadro cai na tela (aDst) e QUE PARTE dele e usada (aSrc, em
       coordenadas do bitmap). Sao dois porque o modo "preencher" corta em vez
       de deformar -- ver o comentario na implementacao. */
    void GameRects(TRect& aDst, TRect& aSrc) const;

    CWolphunEngine*      iEngine;        /* nao possui */
    CAknsBasicBackgroundControlContext* iBgContext;
    HBufC*               iStatus;
};

#endif /* WOLPHUNAPPVIEW_H */
