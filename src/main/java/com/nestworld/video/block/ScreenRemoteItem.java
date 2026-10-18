package com.nestworld.video.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

public class ScreenRemoteItem extends Item {
    private static final String TAG_DIMENSION = "screen_dimension";
    private static final String TAG_POSITION = "screen_position";
    private static final String TAG_OWNER = "screen_owner";

    public ScreenRemoteItem() { super(new Item.Properties().stacksTo(1)); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof VideoScreenBlockEntity clicked))
            return InteractionResult.PASS;
        VideoScreenBlockEntity screen = clicked.controller();
        Player player = context.getPlayer();
        if (screen == null || player == null) return InteractionResult.PASS;
        if (!level.isClientSide) {
            var tag = context.getItemInHand().getOrCreateTag();
            tag.putString(TAG_DIMENSION, level.dimension().location().toString());
            tag.putLong(TAG_POSITION, screen.getBlockPos().asLong());
            tag.putUUID(TAG_OWNER, player.getUUID());
            player.displayClientMessage(Component.translatable("item.nestworldvideo.screen_remote.bound"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer serverPlayer) || !hasBinding(stack, player)) {
            player.displayClientMessage(Component.translatable("item.nestworldvideo.screen_remote.unbound"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.dimension().location().toString().equals(stack.getTag().getString(TAG_DIMENSION))) {
            player.displayClientMessage(Component.translatable("item.nestworldvideo.screen_remote.wrong_dimension"), true);
            return InteractionResultHolder.fail(stack);
        }
        BlockPos pos = BlockPos.of(stack.getTag().getLong(TAG_POSITION));
        if (!level.hasChunkAt(pos)) {
            player.displayClientMessage(Component.translatable("item.nestworldvideo.screen_remote.unloaded"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!(level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) || !screen.isMaster()) {
            player.displayClientMessage(Component.translatable("item.nestworldvideo.screen_remote.missing"), true);
            return InteractionResultHolder.fail(stack);
        }
        NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                (id, inventory, p) -> new VideoScreenMenu(id, inventory, screen, true),
                Component.translatable("item.nestworldvideo.screen_remote")
        ), buffer -> buffer.writeBlockPos(pos).writeBoolean(true));
        return InteractionResultHolder.consume(stack);
    }

    private static boolean hasBinding(ItemStack stack, Player player) {
        return stack.hasTag() && stack.getTag().hasUUID(TAG_OWNER)
                && stack.getTag().getUUID(TAG_OWNER).equals(player.getUUID())
                && stack.getTag().contains(TAG_DIMENSION) && stack.getTag().contains(TAG_POSITION);
    }

    public static boolean playerHasRemoteFor(Player player, BlockPos pos) {
        for (ItemStack stack : player.getInventory().items)
            if (stack.getItem() == ModBlocks.SCREEN_REMOTE.get() && isBoundTo(stack, player, pos)) return true;
        for (ItemStack stack : player.getInventory().offhand)
            if (stack.getItem() == ModBlocks.SCREEN_REMOTE.get() && isBoundTo(stack, player, pos)) return true;
        return false;
    }

    private static boolean isBoundTo(ItemStack stack, Player player, BlockPos pos) {
        return hasBinding(stack, player) && stack.getTag().getLong(TAG_POSITION) == pos.asLong()
                && stack.getTag().getString(TAG_DIMENSION).equals(player.level().dimension().location().toString());
    }
}
