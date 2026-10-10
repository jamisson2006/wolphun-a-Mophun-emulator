/* WolphunWsd.cpp - see WolphunWsd.h */
#include "WolphunWsd.h"

#ifdef __SYMBIAN_9__

/* EKA2: the target is an .exe, so mutable statics are allowed. */
static TInt gLang = 0;   /* default: English, as on the desktop */

TInt WolphunWsd::Lang(){ return gLang; }
void WolphunWsd::SetLang(TInt aIndex){ gLang = aIndex; }

#else

/* EKA1: the value lives in the DLL's own TLS. Before the first SetLang, Tls() returns
   NULL, which converts to 0 -- already English, exactly the default we want. */
TInt WolphunWsd::Lang(){ return (TInt)Dll::Tls(); }
void WolphunWsd::SetLang(TInt aIndex){ Dll::SetTls((TAny*)aIndex); }

#endif
