package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.VoxyRenderSystem;

public interface IGetVoxyRenderSystem {
    VoxyRenderSystem getVoxyRenderSystem();
    void shutdownRenderer();
    void createRenderer();
}
