/* MophunModel.h - which handset the emulator claims to be, and at what resolution.
 *
 * Ported from GameLibrary.detectModel/detectRes and the model table in Config.java.
 *
 * WHY THIS EXISTS: Mophun games that are not "multiscreen" ask for the handset model
 * (vGetCaps System -> deviceId) and abort with "Terminal not supported!" if they do not
 * recognise the answer. Without this, every game tagged [T300], [T610] and so on dies
 * within about 30 instructions and the screen stays black. The model also decides the
 * native resolution of the assets.
 *
 * The file name is the only clue available -- the PC emulator does the same thing.
 * Portable: plain char*, no STL, no libc, no Symbian.
 */
#ifndef MOPHUNMODEL_H
#define MOPHUNMODEL_H

#include "vmcommon.h"

/* SystemDeviceModel indices (from nofun), the same ones the PC emulator uses */
struct MophunModel
{
    enum { kCount = 16 };

    static const int* VendorTable()
    {
        static const int v[kCount] = { 3,3,3,4, 3,3,5,4, 4,7,3,4, 5,8,0,10 };
        return v;
    }
    static const int* ResWTable()
    {
        static const int w[kCount] = { 101,101,128,176, 208,101,208,176,
                                       176,176,208,176, 208,176,176,176 };
        return w;
    }
    static const int* ResHTable()
    {
        static const int h[kCount] = { 80,80,160,208, 320,80,320,208,
                                       208,220,320,208, 320,220,220,220 };
        return h;
    }

    static int VendorFor(int aModel)
    { return (aModel >= 0 && aModel < kCount) ? VendorTable()[aModel] : 3; }

    /* ASCII lowercase */
    static char Lower(char c){ return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c; }

    /* does the name contain 'needle'? comparison is plain lowercase ASCII, no accents */
    static bool Has(const char* name, const char* needle)
    {
        for (int i = 0; name[i]; i++)
        {
            int j = 0;
            while (needle[j] && Lower(name[i + j]) == needle[j]) j++;
            if (!needle[j]) return true;
        }
        return false;
    }

    /* Model deduced from the file name; -1 = multiscreen or unknown, i.e. adaptive. */
    static int FromName(const char* name)
    {
        if (!name) return -1;
        if (Has(name, "multiscreen") || Has(name, "miltiscreen")) return -1;  /* a real typo in the archive */
        if (Has(name, "t300")) return 0;
        if (Has(name, "t310")) return 1;
        if (Has(name, "t226") || Has(name, "t230")) return 5;
        if (Has(name, "t610") || Has(name, "t616") || Has(name, "t630") || Has(name, "t6x0")) return 2;
        if (Has(name, "p800")) return 4;
        if (Has(name, "p900")) return 10;
        if (Has(name, "7650")) return 3;
        if (Has(name, "3650")) return 8;
        if (Has(name, "6600")) return 11;
        if (Has(name, "ngage") || Has(name, "n-gage") || Has(name, "n_gage")) return 7;
        if (Has(name, "a920")) return 6;
        if (Has(name, "a925")) return 12;
        if (Has(name, "sx1"))  return 13;
        if (Has(name, "sendo"))return 15;
        return -1;
    }

    /* Native resolution from a [WIDTHxHEIGHT] tag in the name, as in
       "..._[176x208]_...". Returns true and fills in w/h. */
    static bool ResFromTag(const char* name, int* w, int* h)
    {
        if (!name) return false;
        for (int i = 0; name[i]; i++)
        {
            if (name[i] != '[') continue;
            int j = i + 1, a = 0, da = 0;
            while (name[j] >= '0' && name[j] <= '9'){ a = a*10 + (name[j]-'0'); j++; da++; }
            if (da < 2 || da > 4 || Lower(name[j]) != 'x') continue;
            j++;
            int b = 0, db = 0;
            while (name[j] >= '0' && name[j] <= '9'){ b = b*10 + (name[j]-'0'); j++; db++; }
            if (db < 2 || db > 4 || name[j] != ']') continue;
            if (a < 48 || b < 48 || a > 1024 || b > 1024) continue;
            if (w) *w = a;
            if (h) *h = b;
            return true;
        }
        return false;
    }

    /* Resolution for automatic mode: a known model first, then a [WxH] tag, then the default. */
    static void AutoRes(const char* name, int* w, int* h)
    {
        const int m = FromName(name);
        if (m >= 0){ if (w) *w = ResWTable()[m]; if (h) *h = ResHTable()[m]; return; }
        if (ResFromTag(name, w, h)) return;
        if (w) *w = 176;            /* multiscreen: the classic S60 portrait */
        if (h) *h = 208;
    }
};

#endif /* MOPHUNMODEL_H */
