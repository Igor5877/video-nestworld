package com.nestworld.video.command;

import com.google.gson.Gson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.VideoConfig;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketSyncVideos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Files;

public class ReloadVideosCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reloadvideo")
                .requires(source -> source.hasPermission(2)) // Потрібні права оператора
                .executes(ReloadVideosCommand::execute));
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        try {
            // Завантажуємо videos.json заново
            File configFile = new File(source.getServer().getServerDirectory(), "config/videos.json");
            
            if (!configFile.exists()) {
                source.sendFailure(Component.literal("videos.json not found in config folder!"));
                return 0;
            }
            
            String jsonContent = new String(Files.readAllBytes(configFile.toPath()));
            VideoConfig newConfig = new Gson().fromJson(jsonContent, VideoConfig.class);
            
            // Оновлюємо глобальну конфігурацію
            CinemaMod.videoConfig = newConfig;
            
            // Відправляємо оновлену конфігурацію всім гравцям
            String json = new Gson().toJson(newConfig);
            PacketSyncVideos packet = new PacketSyncVideos(json);
            
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
            
            source.sendSuccess(() -> Component.literal("Successfully reloaded videos.json with " + 
                    newConfig.getCategories().size() + " categories"), true);
            
            return 1;
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to reload videos.json: " + e.getMessage()));
            return 0;
        }
    }
}