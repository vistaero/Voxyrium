package me.cortex.voxy.client.core;

import me.cortex.voxy.client.taskbar.Taskbar;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.UUID;
import java.util.function.Consumer;

public class WorldImportWrapper {
    private WorldImporter importer;
    private final ServiceThreadPool pool;
    private final WorldEngine world;
    private UUID importerBossBarUUID;

    public WorldImportWrapper(ServiceThreadPool pool, WorldEngine world) {
        this.pool = pool;
        this.world = world;
    }

    public void shutdown() {
        Logger.info("Shutting down importer");
        if (this.importer != null) {
            try {
                this.importer.shutdown();
                this.importer = null;
            } catch (Exception e) {
                Logger.error("Error shutting down importer", e);
            }
        }

        //Remove bossbar
        if (this.importerBossBarUUID != null) {
            MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.remove(this.importerBossBarUUID);
            Taskbar.INSTANCE.setIsNone();
        }
    }

    public void stopImporter() {
        if (this.isImporterRunning()) {
            this.importer.shutdown();
            this.importer = null;
        }
    }

    public interface IImporterFactory {
        void setup(WorldImporter importer);
    }

    public boolean createWorldImporter(World mcWorld, IImporterFactory factory) {
        if (this.importer == null) {
            this.importer = new WorldImporter(this.world, mcWorld, this.pool, VoxyCommon.getInstance().getSavingService());
        }
        if (this.importer.isRunning()) {
            return false;
        }

        Taskbar.INSTANCE.setProgress(0,10000);
        Taskbar.INSTANCE.setIsProgression();

        this.importerBossBarUUID = MathHelper.randomUuid();
        var bossBar = new ClientBossBar(this.importerBossBarUUID, Text.of("Voxy world importer"), 0.0f, BossBar.Color.GREEN, BossBar.Style.PROGRESS, false, false, false);
        MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.put(bossBar.getUuid(), bossBar);

        factory.setup(this.importer);

        long start = System.currentTimeMillis();
        long[] ticker = new long[1];

        this.importer.runImport((a, b)-> {
                    if (System.currentTimeMillis() - ticker[0] > 50) {
                        ticker[0] = System.currentTimeMillis();
                        MinecraftClient.getInstance().executeSync(() -> {
                            Taskbar.INSTANCE.setProgress(a, Math.max(1, b));
                            bossBar.setPercent(((float) a) / ((float) Math.max(1, b)));
                            bossBar.setName(Text.of("Voxy import: " + a + "/" + b + " chunks"));
                        });
                    }
                },
                chunkCount -> {
                    MinecraftClient.getInstance().executeSync(()-> {
                        MinecraftClient.getInstance().inGameHud.getBossBarHud().bossBars.remove(this.importerBossBarUUID);
                        this.importerBossBarUUID = null;
                        long delta = Math.max(System.currentTimeMillis() - start, 1);

                        String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(chunkCount/(delta/1000f)) + " chunks per second";
                        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(msg));
                        Logger.info(msg);
                        Taskbar.INSTANCE.setIsNone();
                        if (this.importer != null) {
                            this.importer.shutdown();
                            this.importer = null;
                        }
                    });
                });

        return true;
    }

    public boolean isImporterRunning() {
        return this.importer != null;
    }
}
