package me.cortex.voxy.client;

import me.cortex.voxy.client.taskbar.Taskbar;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.UUID;
import java.util.function.BooleanSupplier;

public class ClientImportManager extends ImportManager {
    protected class ClientImportTask extends ImportTask {
        private final UUID bossbarUUID;
        private final ClientBossBar bossBar;
        protected ClientImportTask(IDataImporter importer) {
            super(importer);

            this.bossbarUUID = MathHelper.randomUuid();
            this.bossBar = new ClientBossBar(this.bossbarUUID, Text.of("Voxy world importer"), 0.0f, BossBar.Color.GREEN, BossBar.Style.PROGRESS, false, false, false);
            MinecraftClient.getInstance().execute(()->{
                MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.put(bossBar.getUuid(), bossBar);
            });
        }

        @Override
        protected boolean onUpdate(int completed, int outOf) {
            if (!super.onUpdate(completed, outOf)) {
                return false;
            }
            MinecraftClient.getInstance().execute(()->{
                this.bossBar.setPercent((float) (((double)completed) / ((double) Math.max(1, outOf))));
                this.bossBar.setName(Text.of("Voxy import: " + completed + "/" + outOf + " chunks"));
            });
            return true;
        }

        @Override
        protected void onCompleted(int total) {
            super.onCompleted(total);
            MinecraftClient.getInstance().execute(()->{
                MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.remove(this.bossbarUUID);
                long delta = Math.max(System.currentTimeMillis() - this.startTime, 1);

                String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(total/(delta/1000f)) + " chunks per second";
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(msg));
                Logger.info(msg);
            });
        }
    }

    @Override
    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ClientImportTask(importer);
    }
}
