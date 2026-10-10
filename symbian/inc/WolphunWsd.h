/* WolphunWsd.h - the emulator's only MUTABLE global state.
 *
 * Why this exists: on Symbian 9.x (EKA2) the app is an .exe and may hold mutable
 * static variables like any other program. On Symbian 8.1 (EKA1) the app is a
 * polymorphic DLL (.app), and an EKA1 DLL may NOT hold mutable static data -- petran
 * rejects the binary with "has initialised data". Symbian's standard way around that
 * is thread-local storage: one pointer per DLL per thread. A TInt fits inside that
 * pointer, so there is no allocation here and nothing to free.
 *
 * Keep this class the ONLY place with mutable global state: one new mutable static in
 * any .cpp and the 8.1 target stops linking.
 */
#ifndef WOLPHUNWSD_H
#define WOLPHUNWSD_H

#include <e32std.h>

class WolphunWsd
{
public:
    /* UI language index (0 = English, the default) */
    static TInt Lang();
    static void SetLang(TInt aIndex);
};

#endif
