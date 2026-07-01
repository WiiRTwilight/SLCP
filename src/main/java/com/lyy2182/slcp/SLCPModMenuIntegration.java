package com.lyy2182.slcp;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成。将 {@link SLCPConfigScreen} 注册为模组配置界面。
 */
public class SLCPModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SLCPConfigScreen::new;
    }
}
