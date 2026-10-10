/* WolphunApp2nd.cpp - Application + Document + the entry point for the SYMBIAN 8.1
 * target.
 *
 * Twin of WolphunApp.cpp, which serves 9.x. One file cannot do both, because the shape
 * of the BINARY changes, not just a call:
 *
 *   9.x (EKA2)  the app is an .exe. The entry point is E32Main() and the UI framework
 *               is brought up by EikStart::RunApplication(), from <eikstart.h>.
 *   8.1 (EKA1)  the app is a POLYMORPHIC DLL called wolphun.app. There is no E32Main:
 *               AppArc loads the DLL and calls the export at ordinal 1,
 *               NewApplication(). <eikstart.h> does not even exist in that SDK.
 *
 * Everything else -- AppUi, AppView, the engine, the VM -- is exactly the same code in
 * both targets.
 */
#include <aknapp.h>
#include <akndoc.h>
#include "WolphunAppUi.h"

const TUid KUidWolphunApp = { 0xA0000180 };   /* the same UID3 as the 9.x target */

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

/* ---------------- the DLL entry points (EKA1) ---------------- */

/* The entry point every EKA1 DLL must have. There is nothing to initialise: the
   emulator's mutable global state lives in TLS (see WolphunWsd.h), which the kernel
   itself creates per thread on demand. */
GLDEF_C TInt E32Dll(TDllReason /*aReason*/)
{
    return KErrNone;
}

/* The DLL's ordinal 1: this is how AppArc instantiates the application. */
EXPORT_C CApaApplication* NewApplication()
{
    return static_cast<CApaApplication*>(new CWolphunApplication);
}
