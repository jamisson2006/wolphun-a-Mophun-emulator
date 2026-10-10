/* WolphunLang.h - the interface strings in 6 languages (i18n).
 *
 * Same scheme as the desktop (src/mophun/Lang.java): the SAME language order
 * (en, es, de, pt, uk, ru), ENGLISH as the default, and the choice stored in the
 * config. The difference is that the key here is an enum rather than text, so there is
 * no string map on the phone and a lookup is a direct index.
 *
 * The .cpp escapes EVERY non-ASCII character as \xNNNN: the 2007 GCCE cannot be
 * trusted to interpret UTF-8 inside an L"" literal.
 */
#ifndef WOLPHUNLANG_H
#define WOLPHUNLANG_H

#include <e32base.h>

enum TWolphunStr
{
    EStrOpenGame,
    EStrBrowseFile,
    EStrFolders,
    EStrAddFolder,
    EStrRemoveFolder,
    EStrRestartGame,
    EStrCloseGame,
    EStrSettings,
    EStrAbout,
    EStrExit,
    EStrResolution,
    EStrScaling,
    EStrControls,
    EStrEditKeys,
    EStrSound,
    EStrTexFilter,
    EStrPowerSave,
    EStrLanguage,
    EStrResAuto,
    EStrScaleCenter,
    EStrScaleAspect,
    EStrScaleFill,
    EStrKeyAuto,
    EStrKeyQwerty,
    EStrKeyNumeric,
    EStrKeyJoystick,
    EStrKeyCustom,
    EStrOff,
    EStrOn,
    EStrTfGame,
    EStrTfLinear,
    EStrTfNearest,
    EStrPsMax,
    EStrPsHigh,
    EStrPsMed,
    EStrPsOff,
    EStrHeadGames,
    EStrHeadFolders,
    EStrNoGames,
    EStrFolderAdded,
    EStrFolderDup,
    EStrNoFolders,
    EStrBrowseTitle,
    EStrAddFolderTitle,
    EStrNoGameNow,
    EStrKeyPressNow,
    EStrKeySet,
    EStrKeyRemoved,
    EStrKeyFull,
    EStrRestartRes,
    EStrFullscreen,
    EStrFullKey,
    EStrActUp,
    EStrActDown,
    EStrActLeft,
    EStrActRight,
    EStrActFire,
    EStrActFire2,
    EStrActSelect,
    EStrActHash,
    EStrActStar,
    EStrActBack,
    EStrActClear,
    EStrKeyUp,
    EStrKeyDown,
    EStrKeyLeft,
    EStrKeyRight,
    EStrKeyCentre,
    EStrKeySpace,
    EStrKeyBksp,
    EStrKeyLShift,
    EStrKeyRShift,
    EStrKeyCode,
    EStrError,
    EStrAboutText,
    EStrDiagFrames,
    EStrDiagStop,
    EStrDiagNoDraw,
    EStrSndNoOut,
    EStrSndPcm,
    EStrSndVoices,
    EStrSndReady,
    EStrSndReq,
    EStrSndPlayed,
    EStrAboutTagline,
    EStrAboutBeta,
    EStrAboutDev,
    EStrAboutStudio,
    EStrAboutVersion,
    EStrAboutPort,
    EStrVibrate,
    EWolphunStrCount
};

class WolphunLang
{
public:
    enum { EWolphunLangCount = 6 };
    /** 0=English (padrao), 1=Espanol, 2=Deutsch, 3=Portugues, 4=Ukrainian, 5=Russian */
    static void  SetLanguage(TInt aIndex);
    static TInt  Language();
    /** texto da chave no idioma atual */
    static TPtrC T(TInt aKey);
    /** nome do idioma, escrito NO PROPRIO idioma (nunca traduzido) */
    static TPtrC LanguageName(TInt aIndex);
};

#endif /* WOLPHUNLANG_H */
