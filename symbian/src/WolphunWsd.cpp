/* WolphunWsd.cpp - ver WolphunWsd.h */
#include "WolphunWsd.h"

#ifdef __SYMBIAN_9__

/* EKA2: o alvo e um .exe, estatico mutavel e permitido. */
static TInt gLang = 0;   /* padrao: ingles, igual ao desktop */

TInt WolphunWsd::Lang(){ return gLang; }
void WolphunWsd::SetLang(TInt aIndex){ gLang = aIndex; }

#else

/* EKA1: o valor vive no TLS da propria DLL. Antes do primeiro SetLang, Tls() devolve
   NULL -- que convertido da 0, que ja e o ingles, exatamente o padrao que queremos. */
TInt WolphunWsd::Lang(){ return (TInt)Dll::Tls(); }
void WolphunWsd::SetLang(TInt aIndex){ Dll::SetTls((TAny*)aIndex); }

#endif
