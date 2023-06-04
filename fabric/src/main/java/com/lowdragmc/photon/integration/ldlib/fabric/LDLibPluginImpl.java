package com.lowdragmc.photon.integration.ldlib.fabric;

import com.lowdragmc.lowdraglib.fabric.ILDLibPlugin;
import com.lowdragmc.photon.integration.LDLibPlugin;

public class LDLibPluginImpl extends LDLibPlugin implements ILDLibPlugin {
    @Override
    public void onLoad() {
        LDLibPlugin.init();
    }

}
