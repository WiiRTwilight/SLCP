package com.lyy2182.slcp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class SLCPConfigScreen extends Screen {

    private final Screen parent;

    protected SLCPConfigScreen(Screen parent) {
        super(Text.translatable("screen.slcp.config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("button.slcp.redownload"),
                button -> {
                    SLCPConfig cfg = SLCPMod.config;
                    if (cfg == null || cfg.isEmpty()) {
                        if (this.client != null && this.client.player != null) {
                            this.client.player.sendMessage(
                                    Text.translatable("text.slcp.redownload_failed"), false);
                        }
                        return;
                    }
                    var client = MinecraftClient.getInstance();
                    DownloadManager.downloadAll(cfg, false, () -> {
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.translatable("text.slcp.redownload_done"), false);
                        }
                    });
                })
                .dimensions(centerX - 100, centerY - 10, 200, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                button -> {
                    if (this.client != null) {
                        this.client.setScreen(parent);
                    }
                })
                .dimensions(centerX - 100, centerY + 20, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
