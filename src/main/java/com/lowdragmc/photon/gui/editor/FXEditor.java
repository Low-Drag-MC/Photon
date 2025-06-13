package com.lowdragmc.photon.gui.editor;

import com.lowdragmc.lowdraglib2.editor.ui.Editor;

public class FXEditor extends Editor {
    @Override
    protected void initMenus() {
        super.initMenus();
        fileMenu.addProjectProvider(FXProject.PROVIDER);
    }
}
