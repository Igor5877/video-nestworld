package com.nestworld.video.client.shader;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.nestworld.video.CinemaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** {@code /nestworldvideo shaderpatch [pack.zip]}: writes the patched copy of BSL next to the original. */
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public final class PatchCommand {
    private PatchCommand() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("nestworldvideo").then(literal("shaderpatch")
                .executes(ctx -> run(null))
                .then(argument("pack", StringArgumentType.greedyString()).executes(ctx -> run(StringArgumentType.getString(ctx, "pack"))))));
    }

    private static int run(String name) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
        Path source;
        if (name != null) source = dir.resolve(name);
        else {
            try (Stream<Path> list = Files.list(dir)) {
                source = list.filter(p -> p.getFileName().toString().toLowerCase().startsWith("bsl") && p.toString().endsWith(".zip")
                        && !p.getFileName().toString().contains(BslPatch.SUFFIX)).sorted().findFirst().orElse(null);
            } catch (java.io.IOException e) { source = null; }
        }
        if (source == null) { say("Не знайшов BSL у " + dir + " (вкажіть файл: /nestworldvideo shaderpatch <файл.zip>)"); return 0; }
        try {
            Path out = BslPatch.apply(source);
            say("Готово: " + out.getFileName() + ". Оберіть цей пак у налаштуваннях шейдерів; \"Video Screen Brightness/Bloom\" - у розділі Camera.");
            return 1;
        } catch (BslPatch.PatchException | java.io.IOException e) {
            say("Патч не застосовано: " + e.getMessage());
            return 0;
        }
    }

    private static void say(String message) {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Nestworld Video] " + message)));
    }
}
