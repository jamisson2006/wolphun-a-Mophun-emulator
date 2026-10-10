/* WolphunAppView.cpp - ver WolphunAppView.h */
#include "WolphunAppView.h"
#include "WolphunEngine.h"
#include "WolphunConfig.h"

#include <AknsDrawUtils.h>
#include <AknsBasicBackgroundControlContext.h>
#include <AknsUtils.h>
#include <AknUtils.h>
#include <fbs.h>
#include <eikenv.h>

CWolphunAppView* CWolphunAppView::NewL(const TRect& aRect)
{
    CWolphunAppView* self = new (ELeave) CWolphunAppView();
    CleanupStack::PushL(self);
    self->ConstructL(aRect);
    CleanupStack::Pop(self);
    return self;
}

CWolphunAppView::CWolphunAppView() : iEngine(0), iBgContext(0), iStatus(0) {}

CWolphunAppView::~CWolphunAppView()
{
    delete iBgContext;
    delete iStatus;
}

void CWolphunAppView::ConstructL(const TRect& aRect)
{
    CreateWindowL();
    /* Contexto de fundo do SKIN, dando ao emulador a aparencia nativa da Nokia.
     ERA KAknsIIDQsnBgAreaMain com o rect do CONTROLE, e isso deixava uma FAIXA PRETA
     na parte de baixo: esse item do tema e o papel de parede do "main pane", cujo
     bitmap tem a altura do main pane padrao; o que sobrasse do nosso controle ficava
     sem pintar nenhuma -- e, por nao ser pintado, acumulava sujeira de desenhos
     anteriores (o "smearing").
     KAknsIIDQsnBgScreen e o fundo da TELA INTEIRA e, com o rect da tela (nao o do
     controle), o contexto cobre tudo sem faixa nem resto. */
    TRect screen;
    AknLayoutUtils::LayoutMetricsRect(AknLayoutUtils::EScreen, screen);
    iBgContext = CAknsBasicBackgroundControlContext::NewL(
        KAknsIIDQsnBgScreen, screen, ETrue /* parent absolute */);
    iStatus = KNullDesC().AllocL();
    SetRect(aRect);
    ActivateL();
}

void CWolphunAppView::SizeChanged()
{
    /* o contexto segue a TELA, nao o controle (ver ConstructL) */
    if (iBgContext)
    {
        TRect screen;
        AknLayoutUtils::LayoutMetricsRect(AknLayoutUtils::EScreen, screen);
        iBgContext->SetRect(screen);
    }
}

/* Entrega o contexto de skin para o framework (e para controles filhos). */
TTypeUid::Ptr CWolphunAppView::MopSupplyObject(TTypeUid aId)
{
    if (iBgContext) return MAknsControlContext::SupplyMopObject(aId, iBgContext);
    return CCoeControl::MopSupplyObject(aId);
}

void CWolphunAppView::SetStatusText(const TDesC& aText)
{
    HBufC* copy = aText.Alloc();
    if (!copy) return;                 /* sem memoria: mantem o texto anterior */
    delete iStatus;
    iStatus = copy;
    DrawDeferred();
}

/* Onde o quadro do jogo cai na tela (aDst) e que parte do quadro e usada (aSrc).
 *
 * O "Preencher a tela" ESTICAVA o quadro ate o retangulo inteiro. Numa tela LANDSCAPE
 * -- 320x240 do E63, do E5, da maioria dos S60 3rd -- com um jogo RETRATO de 176x208, a
 * conta da deformacao e 320/176 = 1,82x na horizontal contra 196/208 = 0,94x na vertical:
 * quase o DOBRO de esticamento num eixo so. Era isso que achatava o carro do Rally Pro.
 *
 * Agora ele AMPLIA MANTENDO A PROPORCAO ate a tela ficar cheia e corta o excesso pelas
 * bordas -- o que "preencher" quer dizer em qualquer interface (o object-fit: cover do
 * CSS). Nunca deforma. Em troca, com proporcoes muito diferentes ele esconde parte do
 * quadro; quem precisa ver TUDO usa "Ampliar mantendo proporcao", o modo vizinho, que
 * mostra o quadro inteiro e deixa barra nas laterais.
 *
 * O tamanho vem do BITMAP, nao do GameSize(): aSrc tem de estar em coordenadas do
 * bitmap, e os dois podem divergir se o jogo trocar de modo depois do AllocBitmapL.
 */
void CWolphunAppView::GameRects(TRect& aDst, TRect& aSrc) const
{
    const TRect area = Rect();
    aDst = area;

    CFbsBitmap* bmp = iEngine ? iEngine->Bitmap() : 0;
    const TSize game = bmp ? bmp->SizeInPixels() : TSize(0, 0);
    aSrc = TRect(TPoint(0, 0), game);
    if (game.iWidth <= 0 || game.iHeight <= 0 || area.Width() <= 0 || area.Height() <= 0)
        return;

    const TInt mode = iEngine->Config().iScaling;

    if (mode == EWolphunScaleAspect)
    {
        /* CABER: a maior ampliacao que ainda mostra o quadro INTEIRO. Sobra barra. */
        const TInt sx = (area.Width()  * 1000) / game.iWidth;
        const TInt sy = (area.Height() * 1000) / game.iHeight;
        const TInt s  = Min(sx, sy);
        const TSize out((game.iWidth * s) / 1000, (game.iHeight * s) / 1000);
        aDst = TRect(TPoint(area.iTl.iX + (area.Width()  - out.iWidth)  / 2,
                            area.iTl.iY + (area.Height() - out.iHeight) / 2), out);
        return;   /* origem = quadro inteiro */
    }

    if (mode == EWolphunScaleFill)
    {
        /* COBRIR: o destino ja e a area inteira; quem encolhe e a ORIGEM, para um recorte
           CENTRADO com a proporcao da tela. Um eixo usa o quadro todo e o outro e cortado
           -- nunca os dois, e nunca ha deformacao. Comparacao cruzada para decidir qual
           eixo manda, sem divisao e sem ponto flutuante. */
        TSize src = game;
        if (area.Width() * game.iHeight >= area.Height() * game.iWidth)
            src.iHeight = (game.iWidth  * area.Height()) / area.Width();   /* corta em cima/baixo */
        else
            src.iWidth  = (game.iHeight * area.Width())  / area.Height();  /* corta nos lados */
        if (src.iWidth  < 1) src.iWidth  = 1;
        if (src.iHeight < 1) src.iHeight = 1;
        aSrc = TRect(TPoint((game.iWidth  - src.iWidth)  / 2,
                            (game.iHeight - src.iHeight) / 2), src);
        return;
    }

    /* EWolphunScaleCenter: 1:1 DE VERDADE. Se o quadro for maior que a area, mostra o
       MIOLO em escala 1:1 -- antes ele era ENCOLHIDO para caber, o que nao e 1:1. */
    const TSize src(Min(game.iWidth, area.Width()), Min(game.iHeight, area.Height()));
    aSrc = TRect(TPoint((game.iWidth  - src.iWidth)  / 2,
                        (game.iHeight - src.iHeight) / 2), src);
    aDst = TRect(TPoint(area.iTl.iX + (area.Width()  - src.iWidth)  / 2,
                        area.iTl.iY + (area.Height() - src.iHeight) / 2), src);
}

/* Texto legivel sobre QUALQUER fundo: o tema pode ser claro ou escuro, e o quadro do
   jogo e preto antes do primeiro flip. Antes isto usava a cor do tema e sumia -- era
   por isso que a linha de diagnostico nao aparecia na foto. Agora vai branco sobre uma
   tarja escura. */
void CWolphunAppView::DrawLabel(CWindowGc& aGc, const TDesC& aText,
                                const TPoint& aBaseline, const CFont* aFont) const
{
    if (aText.Length() == 0 || !aFont) return;
    const TInt w = aFont->TextWidthInPixels(aText);
    const TInt h = aFont->HeightInPixels();
    TRect box(TPoint(aBaseline.iX - 2, aBaseline.iY - h - 1),
              TSize(w + 4, h + 4));
    aGc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    aGc.SetBrushColor(TRgb(0, 0, 0));
    aGc.SetPenStyle(CGraphicsContext::ENullPen);
    aGc.DrawRect(box);
    aGc.SetPenStyle(CGraphicsContext::ESolidPen);
    aGc.SetPenColor(TRgb(255, 255, 255));
    aGc.SetBrushStyle(CGraphicsContext::ENullBrush);
    aGc.DrawText(aText, aBaseline);
}

void CWolphunAppView::Draw(const TRect& /*aRect*/) const
{
    CWindowGc& gc = SystemGc();
    const TRect rect = Rect();

    /* 1) fundo: o tema NATIVO do aparelho. Se o skin falhar (tema sem imagem de fundo),
          cai para o preenchimento simples - nunca deixa lixo na tela. */
    MAknsSkinInstance* skin = AknsUtils::SkinInstance();
    MAknsControlContext* cc  = AknsDrawUtils::ControlContext(this);
    if (!AknsDrawUtils::Background(skin, cc, this, gc, rect))
    {
        gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
        gc.SetBrushColor(KRgbBlack);
        gc.Clear(rect);
    }

    /* 2) o quadro do jogo por cima */
    CFbsBitmap* bmp = iEngine ? iEngine->Bitmap() : 0;
    const TBool hasGame = (iEngine && iEngine->HasGame());
    if (bmp && hasGame)
    {
        TRect dst, src;
        GameRects(dst, src);
        gc.SetBrushStyle(CGraphicsContext::ENullBrush);
        if (dst.Size() == src.Size())
            gc.BitBlt(dst.iTl, bmp, src);                  /* 1:1, sem reamostrar */
        else
            gc.DrawBitmap(dst, bmp, src);                  /* ampliado */
    }

    /* 3) texto: o nome do jogo / aviso, e -- enquanto o jogo nao produziu NENHUM
          quadro -- uma linha de diagnostico. Sem ela, um jogo que simplesmente nao
          desenha fica indistinguivel de um emulador quebrado. */
    const CFont* font = AknLayoutUtils::FontFromId(EAknLogicalFontSecondaryFont);
    gc.UseFont(font);

    if (!hasGame && iStatus && iStatus->Length())
    {
        DrawLabel(gc, *iStatus,
                  TPoint(rect.iTl.iX + 6, rect.iTl.iY + rect.Height()/2), font);
    }
    if (hasGame && iEngine->FrameCount() == 0)
    {
        TBuf<96> diag;
        TRAP_IGNORE(iEngine->DiagnosticsL(diag));
        DrawLabel(gc, diag,
                  TPoint(rect.iTl.iX + 6, rect.iTl.iY + font->HeightInPixels() + 2), font);
    }
    gc.DiscardFont();
}
