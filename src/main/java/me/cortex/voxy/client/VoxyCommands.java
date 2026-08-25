package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        return ClientCommandManager.literal("voxy").requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(ClientCommandManager.literal("import")
                        .then(ClientCommandManager.literal("world")
                                .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                        .suggests(VoxyCommands::importWorldSuggester)
                                        .executes(VoxyCommands::importWorld)))
                        .then(ClientCommandManager.literal("bobby")
                                .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                        .suggests(VoxyCommands::importBobbySuggester)
                                        .executes(VoxyCommands::importBobby)))
                        .then(ClientCommandManager.literal("raw")
                                .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                        .executes(VoxyCommands::importRaw)))
                        .then(ClientCommandManager.literal("zip")
                                .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip)
                                        .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                                .executes(VoxyCommands::importZip))))
                        .then(ClientCommandManager.literal("distant_horizons")
                                .then(ClientCommandManager.argument("sqlDbPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importDistantHorizons)))
                        .then(ClientCommandManager.literal("cancel")
                                .executes(VoxyCommands::cancelImport))
        );
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        var wr = MinecraftClient.getInstance().worldRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).shutdownRenderer();
        }
        var w = ((IVoxyWorld)MinecraftClient.getInstance().world);
        if (w != null) {
            if (w.getWorldEngine() != null) {
                instance.stopWorld(w.getWorldEngine());
            }
            w.setWorldEngine(null);
        }
        VoxyCommon.shutdownInstance();
        VoxyCommon.createInstance();
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).createRenderer();
        }
        return 0;
    }




    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = instance.getOrMakeRenderWorld(MinecraftClient.getInstance().player.clientWorld);
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, MinecraftClient.getInstance().player.clientWorld, instance.getThreadPool(), instance.getSavingService()))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }
        var engine = instance.getOrMakeRenderWorld(MinecraftClient.getInstance().player.clientWorld);
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, MinecraftClient.getInstance().player.clientWorld, instance.getThreadPool(), instance.getSavingService());
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(MinecraftClient.getInstance().runDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(MinecraftClient.getInstance().runDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (CommandSource.shouldSuggest(remaining, wn) || CommandSource.shouldSuggest(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (!(name.endsWith("region"))) {
            file = file.resolve("region");
        }
        return fileBasedImporter(file.toFile())?0:1;
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = instance.getOrMakeRenderWorld(MinecraftClient.getInstance().player.clientWorld);
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, MinecraftClient.getInstance().player.clientWorld, instance.getThreadPool(), instance.getSavingService());
            importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
            return importer;
        })?0:1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> fabricClientCommandSourceCommandContext) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        var world = instance.getOrMakeRenderWorld(MinecraftClient.getInstance().player.clientWorld);
        return instance.getImportManager().cancelImport(world)?0:1;
    }
}