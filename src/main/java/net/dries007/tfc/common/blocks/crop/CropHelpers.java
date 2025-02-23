/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.blocks.crop;

import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import net.dries007.tfc.client.particle.TFCParticles;
import net.dries007.tfc.common.blockentities.CropBlockEntity;
import net.dries007.tfc.common.blockentities.FarmlandBlockEntity;
import net.dries007.tfc.common.blockentities.TFCBlockEntities;
import net.dries007.tfc.common.blocks.soil.FarmlandBlock;
import net.dries007.tfc.util.Fertilizer;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateRange;

/**
 * Common growth logic for crop blocks
 */
public final class CropHelpers
{
    public static final long UPDATE_INTERVAL = 2 * ICalendar.TICKS_IN_DAY;

    public static final float GROWTH_FACTOR = 1f / (24 * ICalendar.TICKS_IN_DAY);
    public static final float NUTRIENT_CONSUMPTION = 1f / (12 * ICalendar.TICKS_IN_DAY);
    public static final float NUTRIENT_GROWTH_FACTOR = 0.5f;
    public static final float GROWTH_LIMIT = 1f;
    public static final float EXPIRY_LIMIT = 2f;
    public static final float YIELD_MIN = 0.2f;
    public static final float YIELD_LIMIT = 1f;

    public static boolean lightValid(Level level, BlockPos pos)
    {
        return level.getRawBrightness(pos, 0) >= 12;
    }

    /**
     * @return {@code true} if the crop survived.
     */
    public static boolean growthTick(Level level, BlockPos pos, BlockState state, CropBlockEntity crop)
    {
        final long firstTick = crop.getLastUpdateTick(), thisTick = Calendars.SERVER.getTicks();
        long tick = firstTick + CropHelpers.UPDATE_INTERVAL, lastTick = firstTick;
        for (; tick < thisTick; tick += CropHelpers.UPDATE_INTERVAL)
        {
            if (!CropHelpers.growthTickStep(level, pos, state, level.getRandom(), lastTick, tick, crop))
            {
                return false;
            }
            lastTick = tick;
        }
        return lastTick >= thisTick || CropHelpers.growthTickStep(level, pos, state, level.getRandom(), lastTick, thisTick, crop);
    }

    public static boolean growthTickStep(Level level, BlockPos pos, BlockState state, Random random, long fromTick, long toTick, CropBlockEntity crop)
    {
        // Calculate invariants
        final ICalendar calendar = Calendars.get(level);
        final BlockPos sourcePos = pos.below();
        final int hydration = FarmlandBlock.getHydration(level, sourcePos);
        final float startTemperature = Climate.getTemperature(level, pos, calendar, Calendars.SERVER.ticksToCalendarTicks(fromTick));
        final float endTemperature = Climate.getTemperature(level, pos, calendar, Calendars.SERVER.ticksToCalendarTicks(toTick));
        final long tickDelta = toTick - fromTick;

        final ICropBlock cropBlock = (ICropBlock) state.getBlock();
        final ClimateRange range = cropBlock.getClimateRange();
        final boolean growing = checkClimate(range, hydration, startTemperature, endTemperature, false);
        final boolean healthy = growing || checkClimate(range, hydration, startTemperature, endTemperature, true);

        // Nutrients are consumed first, since they are independent of growth or health.
        // As long as the crop exists it consumes nutrients.
        final FarmlandBlockEntity farmland = level.getBlockEntity(sourcePos, TFCBlockEntities.FARMLAND.get()).orElse(null);
        final FarmlandBlockEntity.NutrientType primaryNutrient = cropBlock.getPrimaryNutrient();
        float nutrientsAvailable = 0, nutrientsRequired = NUTRIENT_CONSUMPTION * tickDelta, nutrientsConsumed = 0;
        if (farmland != null)
        {
            nutrientsAvailable = farmland.getNutrient(primaryNutrient);
            nutrientsConsumed = farmland.consumeNutrientAndResupplyOthers(primaryNutrient, nutrientsRequired);
        }

        // Total growth is based on the ticks and the nutrients consumed. It is then allocated to actual growth or expiry based on other factors.
        float totalGrowthDelta = Helpers.uniform(random, 0.9f, 1.1f) * tickDelta * GROWTH_FACTOR + nutrientsConsumed * NUTRIENT_GROWTH_FACTOR;
        final float initialGrowth = crop.getGrowth();
        float growth = initialGrowth, expiry = crop.getExpiry(), actualYield = crop.getYield();

        final float growthLimit = cropBlock.getGrowthLimit(level, pos, state);
        if (totalGrowthDelta > 0 && growing && growth < growthLimit)
        {
            // Allocate to growth
            final float delta = Math.min(totalGrowthDelta, growthLimit - growth);

            growth += delta;
            totalGrowthDelta -= delta;
        }
        if (totalGrowthDelta > 0)
        {
            // Allocate remaining growth to expiry
            final float delta = Math.min(totalGrowthDelta, EXPIRY_LIMIT - expiry);

            expiry += delta;
            totalGrowthDelta -= delta;
        }

        // Calculate yield, which depends both on a flat rate per growth, and on the nutrient satisfaction, which is a measure of nutrient consumption over the growth time.
        final float growthDelta = growth - initialGrowth;
        final float nutrientSatisfaction;
        if (growthDelta <= 0 || nutrientsRequired <= 0)
        {
            nutrientSatisfaction = 1; // Either condition causes the below formula to result in NaN
        }
        else
        {
            nutrientSatisfaction = Math.min(1, (totalGrowthDelta / growthDelta) * (nutrientsAvailable / nutrientsRequired));
        }

        actualYield += growthDelta * Helpers.lerp(nutrientSatisfaction, YIELD_MIN, YIELD_LIMIT);

        // Check if the crop should've expired.
        if (expiry >= EXPIRY_LIMIT || !healthy)
        {
            // Lenient here - instead of assuming it expired at the start of the duration, we assume at the end. Including growth during this period.
            cropBlock.die(level, pos, state, growth >= 1);
            return false;
        }

        crop.setGrowth(growth);
        crop.setYield(actualYield);
        crop.setExpiry(expiry);
        crop.setLastUpdateTick(calendar.getTicks());

        return true;
    }

    private static boolean checkClimate(ClimateRange range, int hydration, float firstTemperature, float secondTemperature, boolean allowWiggle)
    {
        return range.checkBoth(hydration, firstTemperature, allowWiggle) && range.checkTemperature(secondTemperature, allowWiggle) == ClimateRange.Result.VALID;
    }

    public static boolean useFertilizer(Level level, Player player, InteractionHand hand, BlockPos farmlandPos)
    {
        if (!level.isClientSide())
        {
            ItemStack stack = player.getItemInHand(hand);
            Fertilizer fertilizer = Fertilizer.get(stack);
            if (fertilizer != null)
            {
                level.getBlockEntity(farmlandPos, TFCBlockEntities.FARMLAND.get()).ifPresent(farmland -> {
                    farmland.addNutrients(fertilizer);
                    if (!player.isCreative()) stack.shrink(1);
                    addNutrientParticles((ServerLevel) level, farmlandPos.above(), fertilizer);
                });
                return true;
            }
        }
        return false;
    }

    private static void addNutrientParticles(ServerLevel level, BlockPos pos, Fertilizer fertilizer)
    {
        final float n = fertilizer.getNitrogen(), p = fertilizer.getPhosphorus(), k = fertilizer.getPotassium();
        for (int i = 0; i < (int) (n > 0 ? Mth.clamp(n * 10, 1, 5) : 0); i++)
        {
            level.sendParticles(TFCParticles.NITROGEN.get(), pos.getX() + level.random.nextFloat(), pos.getY() + level.random.nextFloat() / 5D, pos.getZ() + level.random.nextFloat(), 0, 0D, 0D, 0D, 1D);
        }
        for (int i = 0; i < (int) (p > 0 ? Mth.clamp(p * 10, 1, 5) : 0); i++)
        {
            level.sendParticles(TFCParticles.PHOSPHORUS.get(), pos.getX() + level.random.nextFloat(), pos.getY() + level.random.nextFloat() / 5D, pos.getZ() + level.random.nextFloat(), 0, 0D, 0D, 0D, 1D);
        }
        for (int i = 0; i < (int) (k > 0 ? Mth.clamp(k * 10, 1, 5) : 0); i++)
        {
            level.sendParticles(TFCParticles.POTASSIUM.get(), pos.getX() + level.random.nextFloat(), pos.getY() + level.random.nextFloat() / 5D, pos.getZ() + level.random.nextFloat(), 0, 0D, 0D, 0D, 1D);
        }
    }
}
