package com.nestworld.video.block;

import com.nestworld.video.CinemaMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    
    public static final DeferredRegister<Block> BLOCKS = 
            DeferredRegister.create(ForgeRegistries.BLOCKS, CinemaMod.MOD_ID);
    
    public static final DeferredRegister<Item> ITEMS = 
            DeferredRegister.create(ForgeRegistries.ITEMS, CinemaMod.MOD_ID);
    
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = 
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CinemaMod.MOD_ID);
    
    public static final DeferredRegister<MenuType<?>> MENUS = 
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CinemaMod.MOD_ID);
    
    // Реєстрація блоку
    public static final RegistryObject<Block> VIDEO_SCREEN_BLOCK = BLOCKS.register("video_screen",
            VideoScreenBlock::new);
    
    // Реєстрація предмета блоку
    public static final RegistryObject<Item> VIDEO_SCREEN_ITEM = ITEMS.register("video_screen",
            () -> new BlockItem(VIDEO_SCREEN_BLOCK.get(), new Item.Properties()));
    
    // Реєстрація BlockEntity
    public static final RegistryObject<BlockEntityType<VideoScreenBlockEntity>> VIDEO_SCREEN_BLOCK_ENTITY = 
            BLOCK_ENTITIES.register("video_screen", () ->
                    BlockEntityType.Builder.of(VideoScreenBlockEntity::new, 
                            VIDEO_SCREEN_BLOCK.get()).build(null));
    
    // Реєстрація Menu
    public static final RegistryObject<MenuType<VideoScreenMenu>> VIDEO_SCREEN_MENU = 
            MENUS.register("video_screen", () ->
                    IForgeMenuType.create((windowId, inv, data) -> 
                            new VideoScreenMenu(windowId, inv, data)));
    
    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
        MENUS.register(eventBus);
    }
}