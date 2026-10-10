/* WolphunApp.cpp - Application + Document + the entry point.
 * All three pieces are small, so they live together to keep the tree lean.
 */
#include <eikstart.h>
#include <aknapp.h>
#include <akndoc.h>
#include "WolphunAppUi.h"

const TUid KUidWolphunApp = { 0xA0000180 };   /* the self-signable range */

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

/* ---------------- entry point ---------------- */
LOCAL_C CApaApplication* NewApplication(){ return new CWolphunApplication; }

GLDEF_C TInt E32Main(){ return EikStart::RunApplication(NewApplication); }
