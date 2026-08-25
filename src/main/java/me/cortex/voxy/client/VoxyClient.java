package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
        import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class VoxyClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(VoxyCommands.register());
        });
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
    }
}
