/* WolphunApp2nd.cpp - Application + Document + ponto de entrada do alvo SYMBIAN 8.1.
 *
 * Gemeo do WolphunApp.cpp, que serve o 9.x. Nao da para um arquivo so porque a forma do
 * BINARIO muda, nao so uma chamada:
 *
 *   9.x (EKA2)  o app e um .exe. A entrada e E32Main() e quem monta o framework de UI e
 *               EikStart::RunApplication(), declarado em <eikstart.h>.
 *   8.1 (EKA1)  o app e uma DLL POLIMORFICA chamada wolphun.app. Nao ha E32Main: o
 *               AppArc carrega a DLL e chama o export de ordinal 1, NewApplication().
 *               <eikstart.h> nem existe neste SDK.
 *
 * O resto -- AppUi, AppView, motor, VM -- e exatamente o mesmo codigo nos dois alvos.
 */
#include <aknapp.h>
#include <akndoc.h>
#include "WolphunAppUi.h"

const TUid KUidWolphunApp = { 0xA0000180 };   /* o mesmo UID3 do alvo 9.x */

/* ---------------- Document ---------------- */
class CWolphunDocument : public CAknDocument
{
public:
    static CWolphunDocument* NewL(CEikApplication& aApp);
    ~CWolphunDocument();
private:
    CWolphunDocument(CEikApplication& aApp);
    CEikAppUi* CreateAppUiL();
};

CWolphunDocument* CWolphunDocument::NewL(CEikApplication& aApp)
{
    return new (ELeave) CWolphunDocument(aApp);
}
CWolphunDocument::CWolphunDocument(CEikApplication& aApp) : CAknDocument(aApp) {}
CWolphunDocument::~CWolphunDocument() {}
CEikAppUi* CWolphunDocument::CreateAppUiL(){ return new (ELeave) CWolphunAppUi; }

/* ---------------- Application ---------------- */
class CWolphunApplication : public CAknApplication
{
private:
    CApaDocument* CreateDocumentL();
    TUid AppDllUid() const;
};

TUid CWolphunApplication::AppDllUid() const { return KUidWolphunApp; }
CApaDocument* CWolphunApplication::CreateDocumentL()
{
    return CWolphunDocument::NewL(*this);
}

/* ---------------- entrada da DLL (EKA1) ---------------- */

/* Ponto de entrada obrigatorio de qualquer DLL do EKA1. Nao ha nada a inicializar:
   o estado global mutavel do emulador esta no TLS (ver WolphunWsd.h), que o proprio
   kernel cria por thread sob demanda. */
GLDEF_C TInt E32Dll(TDllReason /*aReason*/)
{
    return KErrNone;
}

/* Ordinal 1 da DLL: e por aqui que o AppArc instancia o aplicativo. */
EXPORT_C CApaApplication* NewApplication()
{
    return static_cast<CApaApplication*>(new CWolphunApplication);
}
