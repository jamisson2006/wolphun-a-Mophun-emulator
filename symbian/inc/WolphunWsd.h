/* WolphunWsd.h - o unico dado global MUTAVEL do emulador.
 *
 * Por que isto existe: no Symbian 9.x (EKA2) o app e um .exe e pode ter variavel
 * estatica mutavel como qualquer programa. No Symbian 8.1 (EKA1) o app e uma DLL
 * polimorfica (.app), e DLL em EKA1 NAO pode ter dado estatico mutavel -- o petran
 * recusa o binario ("has initialised data"). A saida padrao do Symbian para isso e o
 * TLS: um ponteiro por DLL por thread. Um TInt cabe dentro do proprio ponteiro, entao
 * aqui nao ha alocacao nenhuma e nada para liberar.
 *
 * Mantenha esta classe como o UNICO lugar com estado global mutavel: se aparecer outro
 * estatico mutavel em qualquer .cpp, o alvo 8.1 para de linkar.
 */
#ifndef WOLPHUNWSD_H
#define WOLPHUNWSD_H

#include <e32std.h>

class WolphunWsd
{
public:
    /* indice do idioma da interface (0 = ingles, o padrao) */
    static TInt Lang();
    static void SetLang(TInt aIndex);
};

#endif
