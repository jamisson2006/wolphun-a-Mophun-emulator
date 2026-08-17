package com.manedsoftware.wolphun;

import java.io.File;

/** Uma ROM na biblioteca: nome de exibição + arquivo .mpn. */
public final class GameEntry {
    public final String name;
    public final File file;
    public GameEntry(String name, File file){ this.name = name; this.file = file; }
}
