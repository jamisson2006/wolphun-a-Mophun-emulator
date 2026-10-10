/* WolphunConfig.h - PER-GAME settings, persisted in the app's private directory.
 *
 * Each ROM (.mpn) gets its own "<name>.cfg". Resolution is per game because Mophun
 * titles were built for different handsets: a game reads the screen size through
 * vGetCaps at STARTUP, so changing the resolution restarts the session.
 */
#ifndef WOLPHUNCONFIG_H
#define WOLPHUNCONFIG_H

#include <e32base.h>
#include <f32file.h>
#include <badesca.h>     /* CDesCArrayFlat */

/* Resolutions: the ones from the real handsets the PC emulator maps (Config.java),
   plus the two QVGA modes of the newer Nokia devices. */
enum TWolphunRes {
    EWolphunRes101x80 = 0,
    EWolphunRes128x160,
    EWolphunRes176x208,     /* the default: S60 portrait */
    EWolphunRes176x220,
    EWolphunRes208x320,
    EWolphunRes240x320,
    EWolphunRes320x240,     /* E-series landscape */
    /* Automatic: deduced from THE GAME ITSELF, either a model tag ([T300], [T610]...)
       or a [WxH] tag in the name. A new index goes at the END so the .cfg files already
       saved stay valid. */
    EWolphunResAuto,
    EWolphunResCount
};

enum TWolphunScaling {
    EWolphunScaleCenter = 0,    /* centred 1:1, the sharpest */
    EWolphunScaleAspect,        /* scale up keeping the aspect ratio */
    EWolphunScaleFill           /* fill the screen */
};

enum TWolphunKeyProfile {
    EWolphunKeysAuto = 0,       /* detected from the handset */
    EWolphunKeysQwerty,         /* WASD + space (E-series) */
    EWolphunKeysNumeric,        /* the 2/4/6/8/5 keypad */
    EWolphunKeysJoystickOnly,
    EWolphunKeysCustom,         /* only iBind's free map (Settings > Edit keys) */
    EWolphunKeyProfileCount
};

/* Game actions that can be bound to a key. The ORDER is written into the .cfg, so do
   not reorder, only append. Mirrors the PC emulator's model (Config.ACTIONS). */
enum TWolphunAction {
    EWolphunActUp = 0, EWolphunActDown, EWolphunActLeft, EWolphunActRight,
    EWolphunActFire, EWolphunActFire2, EWolphunActSelect,
    /* LITERAL KEYS. Many games do not read only the button bits: they read the raw
       handset key through vTestKey/vScanKeys (Bomb Jack moves with 2 and 3). A QWERTY
       device like the E63 has no numeric keypad to press, so without remapping those
       games are unplayable. The ORDER is written into the .cfg: only append. */
    EWolphunActKey0, EWolphunActKey1, EWolphunActKey2, EWolphunActKey3, EWolphunActKey4,
    EWolphunActKey5, EWolphunActKey6, EWolphunActKey7, EWolphunActKey8, EWolphunActKey9,
    EWolphunActKeyHash, EWolphunActKeyStar,
    /* HANDSET KEYS (vmgpsonyericsson.h: KEY_SONYERICSSON_NO = 203, _CLEAR = 204).
       There is no equivalent button bit, so they travel the same path as the literal
       keys. The PC emulator already offered them; Symbian was sending neither. Found by
       static analysis of the ROMs: Ice Digger, Bomb Jack and SAAF ask for 203, 4-in-1
       asks for 204. */
    EWolphunActPhoneBack, EWolphunActPhoneClear,
    EWolphunActionCount
};
/* How many keys per action. Three covers d-pad, QWERTY and numeric simultaneously,
   which is exactly what the "Automatic" profile already did. */
const TInt KWolphunKeysPerAction = 3;

class TWolphunConfig
{
public:
    TWolphunConfig();
    void SetDefaults();

    /* the dimensions for a given resolution index */
    static TSize ResolutionSize(TInt aIndex);

    TInt iResolution;      /* TWolphunRes */
    TInt iScaling;         /* TWolphunScaling */
    TInt iKeyProfile;      /* TWolphunKeyProfile */
    TInt iSound;           /* 0 = off, 1 = on */
    TInt iTexFilter;       /* 0 = follow the game, 1 = bilinear, 2 = nearest */

    /* FREE key map: a Symbian scan code (TStdScanCode, or 'W'/'2'), 0 = empty.
       It only applies when iKeyProfile == EWolphunKeysCustom, so the four original
       profiles keep behaving exactly as before for anyone who does not want to fiddle. */
    TInt iBind[EWolphunActionCount][KWolphunKeysPerAction];

    /* BATTERY SAVING: 0 = maximum, 1 = high (default), 2 = medium, 3 = off.
       This is not decoration. Once the rasterizer got fast, a Nokia E63 with a worn
       battery SWITCHES ITSELF OFF in Rally Pro Contest at speed: the keyboard LEDs go
       out, the brightness drops and it reboots showing the logo -- a voltage sag under
       sustained load. Each level limits the CPU DUTY CYCLE (how long the VM runs inside
       each 20 ms tick), which is what the battery actually feels; capping frames alone
       does not help, because in a heavy scene the game is already below the cap.
       Deliberately at the END of the struct: the .cfg is a raw dump and ReadIntoL reads
       a prefix of it over the defaults, so an older config stays valid. */
    TInt iPowerSave;

    /* Key that toggles FULLSCREEN (a Symbian scan code; 0 = none).
       At the END of the struct like the ones above: the config is written as a RAW
       BLOCK and read back by prefix, so a new field in the middle would invalidate the
       .cfg files already on the phone. */
    TInt iFullKey;

    /* Device vibration for vSysCtl(SYSCTL_SETVIBRATE): 0 = off, 1 = on.
       At the END of the struct for the same reason as the others. */
    TInt iVibrate;

    /* UI LANGUAGE: 0 = English (default), 1 = Espanol, 2 = Deutsch, 3 = Portugues,
       4 = Ukrainian, 5 = Russian -- the SAME order as the desktop
       (src/mophun/Lang.java), so a stored index means the same thing in both.
       Also at the END of the struct. */
    TInt iLanguage;

    /** The Screen bit matching this action (UP=0x01 ... FIRE2=0x100). */
    static TInt ActionBit(TInt aAction);
    /** Short name of the action, for the key-editing screen. */
    /* Returned BY VALUE: the text comes from WolphunLang::T(), which builds a TPtrC on
       the spot. Returning const TDesC& would be a reference to a temporary. */
    static TPtrC ActionName(TInt aAction);
    /** ASCII of the literal handset key ('0'..'9','#','*'), or -1 for a button action. */
    static TInt ActionAscii(TInt aAction);
};

/* Loads and saves a game's configuration in the app's private directory. */
class CWolphunConfigStore : public CBase
{
public:
    static CWolphunConfigStore* NewL(RFs& aFs);
    ~CWolphunConfigStore();

    /* aRomName = the ROM's file name, without a path. */
    void LoadL(const TDesC& aRomName, TWolphunConfig& aCfg);
    void SaveL(const TDesC& aRomName, const TWolphunConfig& aCfg);

    /* SYSTEM defaults (global.cfg): inherited by games that have no .cfg of their own
       yet, so the user does not have to redo the key map for every new game. */
    void LoadGlobalL(TWolphunConfig& aCfg);
    void SaveGlobalL(const TWolphunConfig& aCfg);

    /* the private directory, also used for the games' native saves */
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
 * The list of GAME FOLDERS, managed by the user (add and remove as many as wanted).
 * "Open game" scans ALL of them at once instead of being tied to one fixed path.
 * Persisted in <private>\folders.txt, one path per line.
 * -------------------------------------------------------------------------*/
class CWolphunFolders : public CBase
{
public:
    static CWolphunFolders* NewL(RFs& aFs, const TDesC& aPrivatePath);
    ~CWolphunFolders();

    TInt Count() const;
    TPtrC At(TInt aIndex) const;

    /* Adds it if it is not there yet; the comparison ignores case.
       Returns EFalse if it was already in the list. */
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
