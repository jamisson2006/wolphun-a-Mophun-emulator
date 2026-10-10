/* WolphunAppView.cpp - see WolphunAppView.h */
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
    /* The SKIN background context, which gives the emulator Nokia's native look.
     It USED to be KAknsIIDQsnBgAreaMain with the CONTROL's rectangle, and that left a
     BLACK BAND along the bottom: that theme item is the wallpaper of the "main pane",
     whose bitmap is only as tall as the standard main pane, so whatever of our control
     stuck out past it was never painted -- and, being unpainted, accumulated leftovers
     from earlier drawing, the smearing.
     KAknsIIDQsnBgScreen is the WHOLE SCREEN's background and, given the screen's
     rectangle rather than the control's, the context covers everything with no band and
     nothing left over. */
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
    /* the context follows the SCREEN, not the control (see ConstructL) */
    if (iBgContext)
    {
        TRect screen;
        AknLayoutUtils::LayoutMetricsRect(AknLayoutUtils::EScreen, screen);
        iBgContext->SetRect(screen);
    }
}

/* Hands the skin context to the framework, and to any child controls. */
TTypeUid::Ptr CWolphunAppView::MopSupplyObject(TTypeUid aId)
{
    if (iBgContext) return MAknsControlContext::SupplyMopObject(aId, iBgContext);
    return CCoeControl::MopSupplyObject(aId);
}

void CWolphunAppView::SetStatusText(const TDesC& aText)
{
    HBufC* copy = aText.Alloc();
    if (!copy) return;                 /* out of memory: keep the previous text */
    delete iStatus;
    iStatus = copy;
    DrawDeferred();
}

/* Where the game's frame lands on screen (aDst) and which part of it is used (aSrc).
 *
 * "Fill the screen" used to STRETCH the frame to the whole rectangle. On a LANDSCAPE
 * display -- the 320x240 of the E63, the E5 and most S60 3rd devices -- with a PORTRAIT
 * 176x208 game, the distortion works out as 320/176 = 1.82x horizontally against
 * 196/208 = 0.94x vertically: nearly TWICE the stretch on one axis. That is what
 * flattened the car in Rally Pro Contest.
 *
 * It now SCALES UP KEEPING THE ASPECT RATIO until the screen is full and crops the
 * overflow at the edges -- what "fill" means in any interface (CSS's object-fit:
 * cover). It never distorts. In exchange, with very different aspect ratios it hides
 * part of the frame; anyone who needs to see ALL of it uses "Scale keeping aspect", the
 * neighbouring mode, which shows the whole frame and leaves bars at the sides.
 *
 * The size comes from the BITMAP, not from GameSize(): aSrc has to be in bitmap
 * coordinates, and the two can diverge if the game changes mode after AllocBitmapL.
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
        /* CONTAIN: the largest scale that still shows the WHOLE frame. Bars remain. */
        const TInt sx = (area.Width()  * 1000) / game.iWidth;
        const TInt sy = (area.Height() * 1000) / game.iHeight;
        const TInt s  = Min(sx, sy);
        const TSize out((game.iWidth * s) / 1000, (game.iHeight * s) / 1000);
        aDst = TRect(TPoint(area.iTl.iX + (area.Width()  - out.iWidth)  / 2,
                            area.iTl.iY + (area.Height() - out.iHeight) / 2), out);
        return;   /* source = the whole frame */
    }

    if (mode == EWolphunScaleFill)
    {
        /* COVER: the destination is already the whole area; it is the SOURCE that
           shrinks, to a CENTRED crop with the screen's aspect ratio. One axis uses the
           entire frame and the other is cropped -- never both, and never any distortion.
           A cross-multiplication decides which axis wins, with no division and no
           floating point. */
        TSize src = game;
        if (area.Width() * game.iHeight >= area.Height() * game.iWidth)
            src.iHeight = (game.iWidth  * area.Height()) / area.Width();   /* crop top and bottom */
        else
            src.iWidth  = (game.iHeight * area.Width())  / area.Height();  /* crop the sides */
        if (src.iWidth  < 1) src.iWidth  = 1;
        if (src.iHeight < 1) src.iHeight = 1;
        aSrc = TRect(TPoint((game.iWidth  - src.iWidth)  / 2,
                            (game.iHeight - src.iHeight) / 2), src);
        return;
    }

    /* EWolphunScaleCenter: a REAL 1:1. If the frame is larger than the area, it shows
       the MIDDLE of it at 1:1 -- it used to be SHRUNK to fit, which is not 1:1. */
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
