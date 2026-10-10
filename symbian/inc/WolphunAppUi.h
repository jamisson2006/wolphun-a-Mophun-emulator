/* WolphunAppUi.h - the left-softkey menu commands, the system's own native settings
 * lists, and key mapping (QWERTY and numeric).
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
    void HandleCommandL(TInt aCommand);        /* wraps DoHandleCommandL in a TRAP */
    void HandleResourceChangeL(TInt aType);
    TKeyResponse HandleKeyEventL(const TKeyEvent& aKeyEvent, TEventCode aType);
    void DynInitMenuPaneL(TInt aResourceId, CEikMenuPane* aMenuPane);
    /* the guaranteed way out of fullscreen: losing the foreground always restores the
       system interface (see SetFullScreenL). */
    void HandleForegroundEventL(TBool aForeground);

    /* MWolphunObserver */
    void FrameReady();
    void GameStopped();

private:
    /* the real command handler; a leave here becomes an error note, not the end of the app */
    void DoHandleCommandL(TInt aCommand);
    void ShowGameListL();
    /* the system's NATIVE file browser (CommonDialogs).
       Returns ETrue along with the full path chosen. */
    TBool BrowseForMpnL(TDes& aPath, TInt aTitleResId);
    void  AddFolderL();
    void  RemoveFolderL();
    /* the system's NATIVE choice list, with the items coming from the dialog resource;
       returns ETrue if the user confirmed */
    TBool ShowChoiceL(TInt aItemCount, TInt aDialogResId, TInt& aValue);
    /** a TRANSLATED choice list: both items and heading come from the language table */
    TBool ShowChoiceT(const TInt* aKeys, TInt aCount, TInt aHeadKey, TInt& aValue);
    /** the same, but the items are technical labels that are not translated */
    TBool ShowChoiceListL(const CDesCArray& aItems, const TDesC& aHead, TInt& aValue);
    /** The free-map editor: lists the actions and enters capture mode on the chosen one. */
    void EditKeysL();
    /** The keys bound to an action, as text for the list ("Up arrow, W, 2" or "---"). */
    void KeysTextL(TInt aAction, TDes& aOut) const;
    /** A readable name for a scan code. */
    static void KeyNameL(TInt aScanCode, TDes& aOut);
    /** Binds or unbinds a scan code on an action -- it toggles, like the PC emulator. */
    void ToggleBindL(TInt aAction, TInt aScanCode);
    /** >=0 while waiting for the next key, for the action at index iCapturing.
        The value KCapFullKey (below) is not a game action: it is the fullscreen key. */
    TInt iCapturing;
    /** iCapturing's sentinel for the fullscreen key. It is -2 because -1 already means
        "not capturing" and >=0 is an action index. */
    enum { KCapFullKey = -2 };

    /** Fullscreen: the game's frame takes the whole display, with no title bar and no
        softkeys. This is runtime state and is not stored in the config: leaving the app
        or losing the foreground always restores the normal interface. */
    TBool iFullscreen;
    void SetFullScreenL(TBool aOn);
    void  ShowSettingL(TInt aCommand);
    void  ShowAboutL();
    void  NoteL(TInt aTbufResId);
    /** a note whose text comes from the language table */
    void  NoteT(TInt aKey);
    void  UpdateStatusL();
    /* maps a handset key to Mophun's button bits; 0 = not mapped */
    TInt  MapScanCode(TInt aScanCode) const;
    TInt  MapKeypadAscii(TInt aScanCode) const;

    CWolphunAppView* iAppView;
    CWolphunEngine*  iEngine;
    CWolphunFolders* iFolders;      /* the game folders the user registered */
    RFs              iFs;
    TBool            iFsOpen;
};

#endif /* WOLPHUNAPPUI_H */
