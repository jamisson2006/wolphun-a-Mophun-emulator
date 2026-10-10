/* WolphunApp.cpp - Application + Document + ponto de entrada.
 * As tres pecas sao pequenas; ficam juntas para manter a arvore enxuta.
 */
#include <eikstart.h>
#include <aknapp.h>
#include <akndoc.h>
#include "WolphunAppUi.h"

const TUid KUidWolphunApp = { 0xA0000180 };   /* faixa auto-assinavel */

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

/* ---------------- entrada ---------------- */
LOCAL_C CApaApplication* NewApplication(){ return new CWolphunApplication; }

GLDEF_C TInt E32Main(){ return EikStart::RunApplication(NewApplication); }
