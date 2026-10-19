package com.nestworld.video.client.acoustics;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a block treats sound. Two independent numbers: how much it blocks (occlusion; 1 = a normal wall) and how well its
 * surface reflects (reflectivity, 0..1). Values come from the block's sound type, so blocks of other mods behave sensibly
 * too; the numbers are this mod's own estimates and are meant to be tuned by ear.
 */
final class AcousticMaterials {
    private AcousticMaterials() {}

    record Material(double occlusion, double reflectivity) {}

    private static final Material HARD = new Material(1.0, 0.92), METAL = new Material(1.0, 0.95), WOOD = new Material(0.85, 0.45),
            GLASS = new Material(0.15, 0.7), SOFT = new Material(1.4, 0.08), EARTH = new Material(0.95, 0.3),
            FOLIAGE = new Material(0.3, 0.12), DEFAULT = new Material(0.9, 0.5);

    static Material of(BlockState state) {
        if (state.is(BlockTags.LEAVES)) return FOLIAGE;
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) return SOFT;
        SoundType type = state.getSoundType();
        if (type == SoundType.STONE || type == SoundType.DEEPSLATE || type == SoundType.DEEPSLATE_BRICKS || type == SoundType.DEEPSLATE_TILES
                || type == SoundType.TUFF || type == SoundType.CALCITE || type == SoundType.BASALT || type == SoundType.NETHER_BRICKS
                || type == SoundType.NETHERRACK || type == SoundType.BONE_BLOCK || type == SoundType.POLISHED_DEEPSLATE || type == SoundType.COPPER
                || type == SoundType.AMETHYST || type == SoundType.NETHER_GOLD_ORE || type == SoundType.NETHER_ORE) return HARD;
        if (type == SoundType.METAL || type == SoundType.NETHERITE_BLOCK || type == SoundType.ANVIL || type == SoundType.CHAIN) return METAL;
        if (type == SoundType.WOOD || type == SoundType.BAMBOO_WOOD || type == SoundType.NETHER_WOOD || type == SoundType.CHERRY_WOOD
                || type == SoundType.LADDER || type == SoundType.SCAFFOLDING) return WOOD;
        if (type == SoundType.GLASS) return GLASS;
        if (type == SoundType.WOOL || type == SoundType.MOSS || type == SoundType.MOSS_CARPET || type == SoundType.SNOW
                || type == SoundType.POWDER_SNOW || type == SoundType.SLIME_BLOCK || type == SoundType.HONEY_BLOCK) return SOFT;
        if (type == SoundType.GRAVEL || type == SoundType.SAND || type == SoundType.GRASS || type == SoundType.ROOTED_DIRT
                || type == SoundType.SOUL_SAND || type == SoundType.SOUL_SOIL || type == SoundType.MUD || type == SoundType.PACKED_MUD) return EARTH;
        if (type == SoundType.AZALEA_LEAVES || type == SoundType.VINE || type == SoundType.CROP || type == SoundType.HANGING_ROOTS) return FOLIAGE;
        return DEFAULT;
    }
}
