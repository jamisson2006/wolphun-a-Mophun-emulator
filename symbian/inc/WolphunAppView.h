/* WolphunAppView.h - the emulator's drawing area.
 *
 * With NO game open it draws NOKIA'S NATIVE BACKGROUND (the skin of whichever theme the
 * user picked on the phone, through AknsDrawUtils) plus a message, so the emulator's
 * screen looks like the rest of the system. With a game open, the VM's frame is blitted
 * on top, centred or scaled according to the settings.
 */
#ifndef WOLPHUNAPPVIEW_H
#define WOLPHUNAPPVIEW_H

#include <coecntrl.h>

class CWolphunEngine;
class CAknsBasicBackgroundControlContext;   /* Nokia skin (theme) context */

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
    /* draws white text on a black band, readable over any theme or background */
    void DrawLabel(CWindowGc& aGc, const TDesC& aText,
                   const TPoint& aBaseline, const CFont* aFont) const;

public:
    void SizeChanged();
    /* hands the skin context to child controls (the Nokia theme background) */
    TTypeUid::Ptr MopSupplyObject(TTypeUid aId);

private:
    CWolphunAppView();
    void ConstructL(const TRect& aRect);
    /* where the frame lands on screen (aDst) and WHICH PART of it is used (aSrc, in
       bitmap coordinates). Two rectangles because the "fill" mode crops instead of
       distorting -- see the comment in the implementation. */
    void GameRects(TRect& aDst, TRect& aSrc) const;

    CWolphunEngine*      iEngine;        /* not owned */
    CAknsBasicBackgroundControlContext* iBgContext;
    HBufC*               iStatus;
};

#endif /* WOLPHUNAPPVIEW_H */
