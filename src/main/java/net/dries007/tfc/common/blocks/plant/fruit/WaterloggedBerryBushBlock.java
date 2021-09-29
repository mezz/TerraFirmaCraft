/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.blocks.plant.fruit;

import java.util.Random;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blockentities.BerryBushBlockEntity;
import net.dries007.tfc.common.blocks.ExtendedProperties;
import net.dries007.tfc.common.blocks.TFCBlockStateProperties;
import net.dries007.tfc.common.fluids.FluidProperty;
import net.dries007.tfc.common.fluids.IFluidLoggable;
import net.dries007.tfc.util.ClimateRange;
import net.dries007.tfc.util.Helpers;

public class WaterloggedBerryBushBlock extends StationaryBerryBushBlock implements IFluidLoggable
{
    public static final FluidProperty FLUID = TFCBlockStateProperties.FRESH_WATER;
    public static final BooleanProperty WILD = TFCBlockStateProperties.WILD;

    public WaterloggedBerryBushBlock(ExtendedProperties properties, Supplier<? extends Item> productItem, Lifecycle[] stages, int deathChance, Supplier<ClimateRange> climateRange)
    {
        super(properties, productItem, stages, climateRange);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (state.getValue(LIFECYCLE) == Lifecycle.FRUITING)
        {
            return InteractionResult.FAIL; // pick berries by flooding
        }
        return super.use(state, level, pos, player, hand, hit);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, Random random)
    {
        super.randomTick(state, level, pos, random);
        BerryBushBlockEntity te = Helpers.getBlockEntity(level, pos, BerryBushBlockEntity.class);
        if (te == null) return;

        Lifecycle lifecycle = state.getValue(LIFECYCLE);
        Fluid fluid = state.getFluidState().getType();
        if (lifecycle == Lifecycle.DORMANT && !fluid.is(FluidTags.WATER))
        {
            te.setGrowing(false); // need to be waterlogged over the winter
        }
        else if (lifecycle == Lifecycle.FLOWERING && fluid.is(FluidTags.WATER))
        {
            te.setGrowing(false); // if we're flowering and STILL waterlogged, just kill it!
        }
        else if (lifecycle == Lifecycle.FRUITING && fluid.is(FluidTags.WATER))
        {
            Helpers.spawnItem(level, pos, getProductItem());
            te.setHarvested(true);
            level.setBlockAndUpdate(pos, state.setValue(LIFECYCLE, Lifecycle.DORMANT));
        }
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos)
    {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        return belowState.is(TFCTags.Blocks.BUSH_PLANTABLE_ON) || belowState.is(TFCTags.Blocks.SEA_BUSH_PLANTABLE_ON) || this.mayPlaceOn(level.getBlockState(belowPos), level, belowPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(LIFECYCLE, STAGE, getFluidProperty(), WILD);
    }

    @Override
    public BlockState updateShape(BlockState stateIn, Direction facing, BlockState facingState, LevelAccessor worldIn, BlockPos currentPos, BlockPos facingPos)
    {
        if (!canSurvive(stateIn, worldIn, currentPos))
        {
            return Blocks.AIR.defaultBlockState();
        }
        else
        {
            final Fluid containedFluid = stateIn.getValue(getFluidProperty()).getFluid();
            if (containedFluid != Fluids.EMPTY)
            {
                worldIn.getLiquidTicks().scheduleTick(currentPos, containedFluid, containedFluid.getTickDelay(worldIn));
            }
            return super.updateShape(stateIn, facing, facingState, worldIn, currentPos, facingPos);
        }
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        FluidState fluidstate = context.getLevel().getFluidState(context.getClickedPos());
        boolean flag = fluidstate.getType() == Fluids.WATER.getSource();
        return defaultBlockState().setValue(getFluidProperty(), flag ? getFluidProperty().keyFor(Fluids.WATER.getSource()) : getFluidProperty().keyFor(Fluids.EMPTY));
    }

    @Override
    @SuppressWarnings("deprecation")
    public FluidState getFluidState(BlockState state)
    {
        return IFluidLoggable.super.getFluidState(state);
    }

    @Override
    public FluidProperty getFluidProperty()
    {
        return FLUID;
    }
}
