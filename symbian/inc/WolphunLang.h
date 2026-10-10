/* WolphunLang.h - textos da interface em 6 idiomas (i18n).
 *
 * Mesma logica do desktop (src/mophun/Lang.java): a MESMA ordem de idiomas
 * (en, es, de, pt, uk, ru), INGLES como padrao e o idioma guardado na config.
 * Diferenca: aqui a chave e um enum, nao texto -- sem mapa de strings no
 * aparelho, a busca vira indexacao direta.
 *
 * O .cpp escapa TODO caractere nao-ASCII como \xNNNN: nao da para confiar em
 * como o GCCE de 2007 interpreta UTF-8 dentro de um literal L"".
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
