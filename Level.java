// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package net.minecraft.world.level;

import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Explosion.BlockInteraction;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunk.EntityCreationType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEvent.Context;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.common.extensions.ILevelExtension;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.jetbrains.annotations.ApiStatus.Internal;

public abstract class Level extends AttachmentHolder implements LevelAccessor, AutoCloseable, ILevelExtension {
   public static final Codec<ResourceKey<Level>> RESOURCE_KEY_CODEC;
   public static final ResourceKey<Level> OVERWORLD;
   public static final ResourceKey<Level> NETHER;
   public static final ResourceKey<Level> END;
   public static final int MAX_LEVEL_SIZE = 30000000;
   public static final int LONG_PARTICLE_CLIP_RANGE = 512;
   public static final int SHORT_PARTICLE_CLIP_RANGE = 32;
   public static final int MAX_BRIGHTNESS = 15;
   public static final int TICKS_PER_DAY = 24000;
   public static final int MAX_ENTITY_SPAWN_Y = 20000000;
   public static final int MIN_ENTITY_SPAWN_Y = -20000000;
   protected final List<TickingBlockEntity> blockEntityTickers = Lists.newArrayList();
   protected final NeighborUpdater neighborUpdater;
   private final List<TickingBlockEntity> pendingBlockEntityTickers = Lists.newArrayList();
   private boolean tickingBlockEntities;
   private final Thread thread;
   private final boolean isDebug;
   private int skyDarken;
   protected int randValue = RandomSource.create().nextInt();
   protected final int addend = 1013904223;
   public float oRainLevel;
   public float rainLevel;
   public float oThunderLevel;
   public float thunderLevel;
   public final RandomSource random = RandomSource.create();
   /** @deprecated */
   @Deprecated
   private final RandomSource threadSafeRandom = RandomSource.createThreadSafe();
   private final Holder<DimensionType> dimensionTypeRegistration;
   protected final WritableLevelData levelData;
   private final Supplier<ProfilerFiller> profiler;
   public final boolean isClientSide;
   private final WorldBorder worldBorder;
   private final BiomeManager biomeManager;
   private final ResourceKey<Level> dimension;
   private final RegistryAccess registryAccess;
   private final DamageSources damageSources;
   private long subTickCount;
   public boolean restoringBlockSnapshots = false;
   public boolean captureBlockSnapshots = false;
   public ArrayList<BlockSnapshot> capturedBlockSnapshots = new ArrayList();
   private final ArrayList<BlockEntity> freshBlockEntities = new ArrayList();
   private final ArrayList<BlockEntity> pendingFreshBlockEntities = new ArrayList();
   private double maxEntityRadius = 2.0;

   protected Level(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess, Holder<DimensionType> dimensionTypeRegistration, Supplier<ProfilerFiller> profiler, boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates) {
      this.profiler = profiler;
      this.levelData = levelData;
      this.dimensionTypeRegistration = dimensionTypeRegistration;
      DimensionType dimensiontype = (DimensionType)dimensionTypeRegistration.value();
      this.dimension = dimension;
      this.isClientSide = isClientSide;
      if (dimensiontype.coordinateScale() != 1.0) {
         this.worldBorder = new 1(this, dimensiontype);
      } else {
         this.worldBorder = new WorldBorder();
      }

      this.thread = Thread.currentThread();
      this.biomeManager = new BiomeManager(this, biomeZoomSeed);
      this.isDebug = isDebug;
      this.neighborUpdater = new CollectingNeighborUpdater(this, maxChainedNeighborUpdates);
      this.registryAccess = registryAccess;
      this.damageSources = new DamageSources(registryAccess);
   }

   public boolean isClientSide() {
      return this.isClientSide;
   }

   @Nullable
   public MinecraftServer getServer() {
      return null;
   }

   public boolean isInWorldBounds(BlockPos pos) {
      return !this.isOutsideBuildHeight(pos) && isInWorldBoundsHorizontal(pos);
   }

   public static boolean isInSpawnableBounds(BlockPos pos) {
      return !isOutsideSpawnableHeight(pos.getY()) && isInWorldBoundsHorizontal(pos);
   }

   private static boolean isInWorldBoundsHorizontal(BlockPos pos) {
      return pos.getX() >= -30000000 && pos.getZ() >= -30000000 && pos.getX() < 30000000 && pos.getZ() < 30000000;
   }

   private static boolean isOutsideSpawnableHeight(int y) {
      return y < -20000000 || y >= 20000000;
   }

   public LevelChunk getChunkAt(BlockPos pos) {
      return this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
   }

   public LevelChunk getChunk(int chunkX, int chunkZ) {
      return (LevelChunk)this.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
   }

   @Nullable
   public ChunkAccess getChunk(int x, int z, ChunkStatus chunkStatus, boolean requireChunk) {
      ChunkAccess chunkaccess = this.getChunkSource().getChunk(x, z, chunkStatus, requireChunk);
      if (chunkaccess == null && requireChunk) {
         throw new IllegalStateException("Should always be able to create a chunk!");
      } else {
         return chunkaccess;
      }
   }

   public boolean setBlock(BlockPos pos, BlockState newState, int flags) {
      return this.setBlock(pos, newState, flags, 512);
   }

   public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
      if (this.isOutsideBuildHeight(pos)) {
         return false;
      } else if (!this.isClientSide && this.isDebug()) {
         return false;
      } else {
         LevelChunk levelchunk = this.getChunkAt(pos);
         Block block = state.getBlock();
         pos = pos.immutable();
         BlockSnapshot blockSnapshot = null;
         if (this.captureBlockSnapshots && !this.isClientSide) {
            blockSnapshot = BlockSnapshot.create(this.dimension, this, pos, flags);
            this.capturedBlockSnapshots.add(blockSnapshot);
         }

         BlockState old = this.getBlockState(pos);
         old.getLightEmission(this, pos);
         old.getLightBlock(this, pos);
         BlockState blockstate = levelchunk.setBlockState(pos, state, (flags & 64) != 0);
         if (blockstate == null) {
            if (blockSnapshot != null) {
               this.capturedBlockSnapshots.remove(blockSnapshot);
            }

            return false;
         } else {
            this.getBlockState(pos);
            if (blockSnapshot == null) {
               this.markAndNotifyBlock(pos, levelchunk, blockstate, state, flags, recursionLeft);
            }

            return true;
         }
      }
   }

   public void markAndNotifyBlock(BlockPos p_46605_, @Nullable LevelChunk levelchunk, BlockState blockstate, BlockState p_46606_, int p_46607_, int p_46608_) {
      Block block = p_46606_.getBlock();
      BlockState blockstate1 = this.getBlockState(p_46605_);
      if (blockstate1 == p_46606_) {
         if (blockstate != blockstate1) {
            this.setBlocksDirty(p_46605_, blockstate, blockstate1);
         }

         if ((p_46607_ & 2) != 0 && (!this.isClientSide || (p_46607_ & 4) == 0) && (this.isClientSide || levelchunk.getFullStatus() != null && levelchunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING))) {
            this.sendBlockUpdated(p_46605_, blockstate, p_46606_, p_46607_);
         }

         if ((p_46607_ & 1) != 0) {
            this.blockUpdated(p_46605_, blockstate.getBlock());
            if (!this.isClientSide && p_46606_.hasAnalogOutputSignal()) {
               this.updateNeighbourForOutputSignal(p_46605_, block);
            }
         }

         if ((p_46607_ & 16) == 0 && p_46608_ > 0) {
            int i = p_46607_ & -34;
            blockstate.updateIndirectNeighbourShapes(this, p_46605_, i, p_46608_ - 1);
            p_46606_.updateNeighbourShapes(this, p_46605_, i, p_46608_ - 1);
            p_46606_.updateIndirectNeighbourShapes(this, p_46605_, i, p_46608_ - 1);
         }

         this.onBlockStateChange(p_46605_, blockstate, blockstate1);
         p_46606_.onBlockStateChange(this, p_46605_, blockstate);
      }

   }

   public void onBlockStateChange(BlockPos pos, BlockState blockState, BlockState newState) {
   }

   public boolean removeBlock(BlockPos pos, boolean isMoving) {
      FluidState fluidstate = this.getFluidState(pos);
      return this.setBlock(pos, fluidstate.createLegacyBlock(), 3 | (isMoving ? 64 : 0));
   }

   public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
      BlockState blockstate = this.getBlockState(pos);
      if (blockstate.isAir()) {
         return false;
      } else {
         FluidState fluidstate = this.getFluidState(pos);
         if (!(blockstate.getBlock() instanceof BaseFireBlock)) {
            this.levelEvent(2001, pos, Block.getId(blockstate));
         }

         if (dropBlock) {
            BlockEntity blockentity = blockstate.hasBlockEntity() ? this.getBlockEntity(pos) : null;
            Block.dropResources(blockstate, this, pos, blockentity, entity, ItemStack.EMPTY);
         }

         boolean flag = this.setBlock(pos, fluidstate.createLegacyBlock(), 3, recursionLeft);
         if (flag) {
            this.gameEvent(GameEvent.BLOCK_DESTROY, pos, Context.of(entity, blockstate));
         }

         return flag;
      }
   }

   public void addDestroyBlockEffect(BlockPos pos, BlockState state) {
   }

   public boolean setBlockAndUpdate(BlockPos pos, BlockState state) {
      return this.setBlock(pos, state, 3);
   }

   public abstract void sendBlockUpdated(BlockPos var1, BlockState var2, BlockState var3, int var4);

   public void setBlocksDirty(BlockPos blockPos, BlockState oldState, BlockState newState) {
   }

   public void updateNeighborsAt(BlockPos pos, Block block) {
      EventHooks.onNeighborNotify(this, pos, this.getBlockState(pos), EnumSet.allOf(Direction.class), false).isCanceled();
   }

   public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block blockType, Direction skipSide) {
   }

   public void neighborChanged(BlockPos pos, Block block, BlockPos fromPos) {
   }

   public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
   }

   public void neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos, int flags, int recursionLevel) {
      this.neighborUpdater.shapeUpdate(direction, queried, pos, offsetPos, flags, recursionLevel);
   }

   public int getHeight(Heightmap.Types heightmapType, int x, int z) {
      int i;
      if (x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000) {
         if (this.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
            i = this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(heightmapType, x & 15, z & 15) + 1;
         } else {
            i = this.getMinBuildHeight();
         }
      } else {
         i = this.getSeaLevel() + 1;
      }

      return i;
   }

   public LevelLightEngine getLightEngine() {
      return this.getChunkSource().getLightEngine();
   }

   public BlockState getBlockState(BlockPos pos) {
      if (this.isOutsideBuildHeight(pos)) {
         return Blocks.VOID_AIR.defaultBlockState();
      } else {
         LevelChunk levelchunk = this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
         return levelchunk.getBlockState(pos);
      }
   }

   public FluidState getFluidState(BlockPos pos) {
      if (this.isOutsideBuildHeight(pos)) {
         return Fluids.EMPTY.defaultFluidState();
      } else {
         LevelChunk levelchunk = this.getChunkAt(pos);
         return levelchunk.getFluidState(pos);
      }
   }

   public boolean isDay() {
      return !this.dimensionType().hasFixedTime() && this.skyDarken < 4;
   }

   public boolean isNight() {
      return !this.dimensionType().hasFixedTime() && !this.isDay();
   }

   public void playSound(@Nullable Entity entity, BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch) {
      Player var10001;
      if (entity instanceof Player player) {
         var10001 = player;
      } else {
         var10001 = null;
      }

      this.playSound(var10001, pos, sound, category, volume, pitch);
   }

   public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch) {
      this.playSound(player, (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, sound, category, volume, pitch);
   }

   public abstract void playSeededSound(@Nullable Player var1, double var2, double var4, double var6, Holder<SoundEvent> var8, SoundSource var9, float var10, float var11, long var12);

   public void playSeededSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch, long seed) {
      this.playSeededSound(player, x, y, z, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), category, volume, pitch, seed);
   }

   public abstract void playSeededSound(@Nullable Player var1, Entity var2, Holder<SoundEvent> var3, SoundSource var4, float var5, float var6, long var7);

   public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource category) {
      this.playSound(player, x, y, z, sound, category, 1.0F, 1.0F);
   }

   public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch) {
      this.playSeededSound(player, x, y, z, sound, category, volume, pitch, this.threadSafeRandom.nextLong());
   }

   public void playSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch) {
      this.playSeededSound(player, x, y, z, sound, category, volume, pitch, this.threadSafeRandom.nextLong());
   }

   public void playSound(@Nullable Player player, Entity entity, SoundEvent event, SoundSource category, float volume, float pitch) {
      this.playSeededSound(player, entity, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), category, volume, pitch, this.threadSafeRandom.nextLong());
   }

   public void playLocalSound(BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch, boolean distanceDelay) {
      this.playLocalSound((double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, sound, category, volume, pitch, distanceDelay);
   }

   public void playLocalSound(Entity entity, SoundEvent sound, SoundSource category, float volume, float pitch) {
   }

   public void playLocalSound(double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch, boolean distanceDelay) {
   }

   public void addParticle(ParticleOptions particleData, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
   }

   public void addParticle(ParticleOptions particleData, boolean forceAlwaysRender, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
   }

   public void addAlwaysVisibleParticle(ParticleOptions particleData, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
   }

   public void addAlwaysVisibleParticle(ParticleOptions particleData, boolean ignoreRange, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
   }

   public float getSunAngle(float partialTicks) {
      float f = this.getTimeOfDay(partialTicks);
      return f * 6.2831855F;
   }

   public void addBlockEntityTicker(TickingBlockEntity ticker) {
      (this.tickingBlockEntities ? this.pendingBlockEntityTickers : this.blockEntityTickers).add(ticker);
   }

   public void addFreshBlockEntities(Collection<BlockEntity> beList) {
      if (this.tickingBlockEntities) {
         this.pendingFreshBlockEntities.addAll(beList);
      } else {
         this.freshBlockEntities.addAll(beList);
      }

   }

   protected void tickBlockEntities() {
      ProfilerFiller profilerfiller = this.getProfiler();
      profilerfiller.push("blockEntities");
      if (!this.pendingFreshBlockEntities.isEmpty()) {
         this.freshBlockEntities.addAll(this.pendingFreshBlockEntities);
         this.pendingFreshBlockEntities.clear();
      }

      this.tickingBlockEntities = true;
      if (!this.freshBlockEntities.isEmpty()) {
         this.freshBlockEntities.forEach((blockEntity) -> {
            if (!blockEntity.isRemoved() && blockEntity.hasLevel()) {
               blockEntity.onLoad();
            }

         });
         this.freshBlockEntities.clear();
      }

      if (!this.pendingBlockEntityTickers.isEmpty()) {
         this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
         this.pendingBlockEntityTickers.clear();
      }

      Iterator<TickingBlockEntity> iterator = this.blockEntityTickers.iterator();
      boolean flag = this.tickRateManager().runsNormally();

      while(iterator.hasNext()) {
         TickingBlockEntity tickingblockentity = (TickingBlockEntity)iterator.next();
         if (tickingblockentity.isRemoved()) {
            iterator.remove();
         } else if (flag && this.shouldTickBlocksAt(tickingblockentity.getPos())) {
            tickingblockentity.tick();
         }
      }

      this.tickingBlockEntities = false;
      profilerfiller.pop();
   }

   public <T extends Entity> void guardEntityTick(Consumer<T> consumerEntity, T entity) {
      try {
         TimeTracker.ENTITY_UPDATE.trackStart(entity);
         consumerEntity.accept(entity);
      } catch (Throwable var9) {
         CrashReport crashreport = CrashReport.forThrowable(var9, "Ticking entity");
         CrashReportCategory crashreportcategory = crashreport.addCategory("Entity being ticked");
         entity.fillCrashReportCategory(crashreportcategory);
         if (!(Boolean)NeoForgeConfig.SERVER.removeErroringEntities.get()) {
            throw new ReportedException(crashreport);
         }

         LogUtils.getLogger().error("{}", crashreport.getFriendlyReport(ReportType.CRASH));
         entity.discard();
      } finally {
         TimeTracker.ENTITY_UPDATE.trackEnd(entity);
      }

   }

   public boolean shouldTickDeath(Entity entity) {
      return true;
   }

   public boolean shouldTickBlocksAt(long chunkPos) {
      return true;
   }

   public boolean shouldTickBlocksAt(BlockPos pos) {
      return this.shouldTickBlocksAt(ChunkPos.asLong(pos));
   }

   public Explosion explode(@Nullable Entity source, double x, double y, double z, float radius, ExplosionInteraction explosionInteraction) {
      return this.explode(source, Explosion.getDefaultDamageSource(this, source), (ExplosionDamageCalculator)null, x, y, z, radius, false, explosionInteraction, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
   }

   public Explosion explode(@Nullable Entity source, double x, double y, double z, float radius, boolean fire, ExplosionInteraction explosionInteraction) {
      return this.explode(source, Explosion.getDefaultDamageSource(this, source), (ExplosionDamageCalculator)null, x, y, z, radius, fire, explosionInteraction, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
   }

   public Explosion explode(@Nullable Entity source, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator damageCalculator, Vec3 pos, float radius, boolean fire, ExplosionInteraction explosionInteraction) {
      return this.explode(source, damageSource, damageCalculator, pos.x(), pos.y(), pos.z(), radius, fire, explosionInteraction, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
   }

   public Explosion explode(@Nullable Entity source, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius, boolean fire, ExplosionInteraction explosionInteraction) {
      return this.explode(source, damageSource, damageCalculator, x, y, z, radius, fire, explosionInteraction, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
   }

   public Explosion explode(@Nullable Entity source, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius, boolean fire, ExplosionInteraction explosionInteraction, ParticleOptions smallExplosionParticles, ParticleOptions largeExplosionParticles, Holder<SoundEvent> explosionSound) {
      return this.explode(source, damageSource, damageCalculator, x, y, z, radius, fire, explosionInteraction, true, smallExplosionParticles, largeExplosionParticles, explosionSound);
   }

   public Explosion explode(@Nullable Entity source, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius, boolean fire, ExplosionInteraction explosionInteraction, boolean spawnParticles, ParticleOptions smallExplosionParticles, ParticleOptions largeExplosionParticles, Holder<SoundEvent> explosionSound) {
      Explosion.BlockInteraction var10000;
      switch (explosionInteraction.ordinal()) {
         case 0:
            var10000 = BlockInteraction.KEEP;
            break;
         case 1:
            var10000 = this.getDestroyType(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY);
            break;
         case 2:
            var10000 = EventHooks.canEntityGrief(this, source) ? this.getDestroyType(GameRules.RULE_MOB_EXPLOSION_DROP_DECAY) : BlockInteraction.KEEP;
            break;
         case 3:
            var10000 = this.getDestroyType(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY);
            break;
         case 4:
            var10000 = BlockInteraction.TRIGGER_BLOCK;
            break;
         default:
            throw new MatchException((String)null, (Throwable)null);
      }

      Explosion.BlockInteraction explosion$blockinteraction = var10000;
      Explosion explosion = new Explosion(this, source, damageSource, damageCalculator, x, y, z, radius, fire, explosion$blockinteraction, smallExplosionParticles, largeExplosionParticles, explosionSound);
      if (EventHooks.onExplosionStart(this, explosion)) {
         return explosion;
      } else {
         explosion.explode();
         explosion.finalizeExplosion(spawnParticles);
         return explosion;
      }
   }

   private Explosion.BlockInteraction getDestroyType(GameRules.Key<GameRules.BooleanValue> gameRule) {
      return this.getGameRules().getBoolean(gameRule) ? BlockInteraction.DESTROY_WITH_DECAY : BlockInteraction.DESTROY;
   }

   public abstract String gatherChunkSourceStats();

   @Nullable
   public BlockEntity getBlockEntity(BlockPos pos) {
      if (this.isOutsideBuildHeight(pos)) {
         return null;
      } else {
         return !this.isClientSide && Thread.currentThread() != this.thread ? null : this.getChunkAt(pos).getBlockEntity(pos, EntityCreationType.IMMEDIATE);
      }
   }

   public void setBlockEntity(BlockEntity blockEntity) {
      BlockPos blockpos = blockEntity.getBlockPos();
      if (!this.isOutsideBuildHeight(blockpos)) {
         this.getChunkAt(blockpos).addAndRegisterBlockEntity(blockEntity);
      }

   }

   public void removeBlockEntity(BlockPos pos) {
      if (!this.isOutsideBuildHeight(pos)) {
         this.getChunkAt(pos).removeBlockEntity(pos);
      }

      this.updateNeighbourForOutputSignal(pos, this.getBlockState(pos).getBlock());
   }

   public boolean isLoaded(BlockPos pos) {
      return this.isOutsideBuildHeight(pos) ? false : this.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
   }

   public boolean loadedAndEntityCanStandOnFace(BlockPos pos, Entity entity, Direction direction) {
      if (this.isOutsideBuildHeight(pos)) {
         return false;
      } else {
         ChunkAccess chunkaccess = this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
         return chunkaccess == null ? false : chunkaccess.getBlockState(pos).entityCanStandOnFace(this, pos, entity, direction);
      }
   }

   public boolean loadedAndEntityCanStandOn(BlockPos pos, Entity entity) {
      return this.loadedAndEntityCanStandOnFace(pos, entity, Direction.UP);
   }

   public void updateSkyBrightness() {
      double d0 = 1.0 - (double)(this.getRainLevel(1.0F) * 5.0F) / 16.0;
      double d1 = 1.0 - (double)(this.getThunderLevel(1.0F) * 5.0F) / 16.0;
      double d2 = 0.5 + 2.0 * Mth.clamp((double)Mth.cos(this.getTimeOfDay(1.0F) * 6.2831855F), -0.25, 0.25);
      this.skyDarken = (int)((1.0 - d2 * d0 * d1) * 11.0);
   }

   public void setSpawnSettings(boolean hostile, boolean peaceful) {
      this.getChunkSource().setSpawnSettings(hostile, peaceful);
   }

   public BlockPos getSharedSpawnPos() {
      BlockPos blockpos = this.levelData.getSpawnPos();
      if (!this.getWorldBorder().isWithinBounds(blockpos)) {
         blockpos = this.getHeightmapPos(Types.MOTION_BLOCKING, BlockPos.containing(this.getWorldBorder().getCenterX(), 0.0, this.getWorldBorder().getCenterZ()));
      }

      return blockpos;
   }

   public float getSharedSpawnAngle() {
      return this.levelData.getSpawnAngle();
   }

   protected void prepareWeather() {
      if (this.levelData.isRaining()) {
         this.rainLevel = 1.0F;
         if (this.levelData.isThundering()) {
            this.thunderLevel = 1.0F;
         }
      }

   }

   public void close() throws IOException {
      this.getChunkSource().close();
   }

   @Nullable
   public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
      return this.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
   }

   public List<Entity> getEntities(@Nullable Entity entity, AABB boundingBox, Predicate<? super Entity> predicate) {
      this.getProfiler().incrementCounter("getEntities");
      List<Entity> list = Lists.newArrayList();
      this.getEntities().get(boundingBox, (p_151522_) -> {
         if (p_151522_ != entity && predicate.test(p_151522_)) {
            list.add(p_151522_);
         }

      });
      Iterator var5 = this.getPartEntities().iterator();

      while(var5.hasNext()) {
         PartEntity<?> p = (PartEntity)var5.next();
         if (p != entity && p.getBoundingBox().intersects(boundingBox) && predicate.test(p)) {
            list.add(p);
         }
      }

      return list;
   }

   public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate) {
      List<T> list = Lists.newArrayList();
      this.getEntities(entityTypeTest, bounds, predicate, list);
      return list;
   }

   public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate, List<? super T> output) {
      this.getEntities(entityTypeTest, bounds, predicate, output, Integer.MAX_VALUE);
   }

   public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds, Predicate<? super T> predicate, List<? super T> output, int maxResults) {
      this.getProfiler().incrementCounter("getEntities");
      this.getEntities().get(entityTypeTest, bounds, (p_261454_) -> {
         if (predicate.test(p_261454_)) {
            output.add(p_261454_);
            if (output.size() >= maxResults) {
               return Continuation.ABORT;
            }
         }

         return Continuation.CONTINUE;
      });
      Iterator var6 = this.getPartEntities().iterator();

      while(var6.hasNext()) {
         PartEntity<?> p = (PartEntity)var6.next();
         T t = (Entity)entityTypeTest.tryCast(p);
         if (t != null && t.getBoundingBox().intersects(bounds) && predicate.test(t)) {
            output.add(t);
            if (output.size() >= maxResults) {
               break;
            }
         }
      }

   }

   @Nullable
   public abstract Entity getEntity(int var1);

   public void blockEntityChanged(BlockPos pos) {
      if (this.hasChunkAt(pos)) {
         this.getChunkAt(pos).setUnsaved(true);
      }

   }

   public int getSeaLevel() {
      return 63;
   }

   public void disconnect() {
   }

   public long getGameTime() {
      return this.levelData.getGameTime();
   }

   public long getDayTime() {
      return this.levelData.getDayTime();
   }

   public boolean mayInteract(Player player, BlockPos pos) {
      return true;
   }

   public void broadcastEntityEvent(Entity entity, byte state) {
   }

   public void broadcastDamageEvent(Entity entity, DamageSource damageSource) {
   }

   public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
      this.getBlockState(pos).triggerEvent(this, pos, eventID, eventParam);
   }

   public LevelData getLevelData() {
      return this.levelData;
   }

   public GameRules getGameRules() {
      return this.levelData.getGameRules();
   }

   public abstract TickRateManager tickRateManager();

   public float getThunderLevel(float delta) {
      return Mth.lerp(delta, this.oThunderLevel, this.thunderLevel) * this.getRainLevel(delta);
   }

   public void setThunderLevel(float strength) {
      float f = Mth.clamp(strength, 0.0F, 1.0F);
      this.oThunderLevel = f;
      this.thunderLevel = f;
   }

   public float getRainLevel(float delta) {
      return Mth.lerp(delta, this.oRainLevel, this.rainLevel);
   }

   public void setRainLevel(float strength) {
      float f = Mth.clamp(strength, 0.0F, 1.0F);
      this.oRainLevel = f;
      this.rainLevel = f;
   }

   public boolean isThundering() {
      return this.dimensionType().hasSkyLight() && !this.dimensionType().hasCeiling() ? (double)this.getThunderLevel(1.0F) > 0.9 : false;
   }

   public boolean isRaining() {
      return (double)this.getRainLevel(1.0F) > 0.2;
   }

   public boolean isRainingAt(BlockPos pos) {
      if (!this.isRaining()) {
         return false;
      } else if (!this.canSeeSky(pos)) {
         return false;
      } else if (this.getHeightmapPos(Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
         return false;
      } else {
         Biome biome = (Biome)this.getBiome(pos).value();
         return biome.getPrecipitationAt(pos) == Precipitation.RAIN;
      }
   }

   @Nullable
   public abstract MapItemSavedData getMapData(MapId var1);

   public abstract void setMapData(MapId var1, MapItemSavedData var2);

   public abstract MapId getFreeMapId();

   public void globalLevelEvent(int id, BlockPos pos, int data) {
   }

   public CrashReportCategory fillReportDetails(CrashReport report) {
      CrashReportCategory crashreportcategory = report.addCategory("Affected level", 1);
      crashreportcategory.setDetail("All players", () -> {
         int var10000 = this.players().size();
         return "" + var10000 + " total; " + String.valueOf(this.players());
      });
      ChunkSource var10002 = this.getChunkSource();
      Objects.requireNonNull(var10002);
      crashreportcategory.setDetail("Chunk stats", var10002::gatherStats);
      crashreportcategory.setDetail("Level dimension", () -> {
         return this.dimension().location().toString();
      });

      try {
         this.levelData.fillCrashReportCategory(crashreportcategory, this);
      } catch (Throwable var4) {
         crashreportcategory.setDetailError("Level Data Unobtainable", var4);
      }

      return crashreportcategory;
   }

   public abstract void destroyBlockProgress(int var1, BlockPos var2, int var3);

   public void createFireworks(double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, List<FireworkExplosion> explosions) {
   }

   public abstract Scoreboard getScoreboard();

   public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
      Direction[] var3 = Direction.values();
      int var4 = var3.length;

      for(int var5 = 0; var5 < var4; ++var5) {
         Direction direction = var3[var5];
         BlockPos blockpos = pos.relative(direction);
         if (this.hasChunkAt(blockpos)) {
            BlockState blockstate = this.getBlockState(blockpos);
            blockstate.onNeighborChange(this, blockpos, pos);
            if (blockstate.isRedstoneConductor(this, blockpos)) {
               blockpos = blockpos.relative(direction);
               blockstate = this.getBlockState(blockpos);
               if (blockstate.getWeakChanges(this, blockpos)) {
                  this.neighborChanged(blockstate, blockpos, block, pos, false);
               }
            }
         }
      }

   }

   public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
      long i = 0L;
      float f = 0.0F;
      if (this.hasChunkAt(pos)) {
         f = this.getMoonBrightness();
         i = this.getChunkAt(pos).getInhabitedTime();
      }

      return new DifficultyInstance(this.getDifficulty(), this.getDayTime(), i, f);
   }

   public int getSkyDarken() {
      return this.skyDarken;
   }

   public void setSkyFlashTime(int timeFlash) {
   }

   public WorldBorder getWorldBorder() {
      return this.worldBorder;
   }

   public void sendPacketToServer(Packet<?> packet) {
      throw new UnsupportedOperationException("Can't send packets to server unless you're on the client.");
   }

   public DimensionType dimensionType() {
      return (DimensionType)this.dimensionTypeRegistration.value();
   }

   public Holder<DimensionType> dimensionTypeRegistration() {
      return this.dimensionTypeRegistration;
   }

   public ResourceKey<Level> dimension() {
      return this.dimension;
   }

   public RandomSource getRandom() {
      return this.random;
   }

   public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> state) {
      return state.test(this.getBlockState(pos));
   }

   public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
      return predicate.test(this.getFluidState(pos));
   }

   public abstract RecipeManager getRecipeManager();

   public BlockPos getBlockRandomPos(int x, int y, int z, int yMask) {
      this.randValue = this.randValue * 3 + 1013904223;
      int i = this.randValue >> 2;
      return new BlockPos(x + (i & 15), y + (i >> 16 & yMask), z + (i >> 8 & 15));
   }

   public boolean noSave() {
      return false;
   }

   public ProfilerFiller getProfiler() {
      return (ProfilerFiller)this.profiler.get();
   }

   public Supplier<ProfilerFiller> getProfilerSupplier() {
      return this.profiler;
   }

   public BiomeManager getBiomeManager() {
      return this.biomeManager;
   }

   public double getMaxEntityRadius() {
      return this.maxEntityRadius;
   }

   public double increaseMaxEntityRadius(double value) {
      if (value > this.maxEntityRadius) {
         this.maxEntityRadius = value;
      }

      return this.maxEntityRadius;
   }

   public final boolean isDebug() {
      return this.isDebug;
   }

   protected abstract LevelEntityGetter<Entity> getEntities();

   public long nextSubTickCount() {
      return (long)(this.subTickCount++);
   }

   public RegistryAccess registryAccess() {
      return this.registryAccess;
   }

   public DamageSources damageSources() {
      return this.damageSources;
   }

   public abstract PotionBrewing potionBrewing();

   @Internal
   public abstract void setDayTimeFraction(float var1);

   @Internal
   public abstract float getDayTimeFraction();

   public abstract float getDayTimePerTick();

   public abstract void setDayTimePerTick(float var1);

   @Internal
   protected long advanceDaytime() {
      if (this.getDayTimePerTick() < 0.0F) {
         return 1L;
      } else {
         float dayTimeStep = this.getDayTimeFraction() + this.getDayTimePerTick();
         long result = (long)dayTimeStep;
         this.setDayTimeFraction(dayTimeStep - (float)result);
         return result;
      }
   }

   static {
      RESOURCE_KEY_CODEC = ResourceKey.codec(Registries.DIMENSION);
      OVERWORLD = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
      NETHER = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));
      END = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_end"));
   }

   public static enum ExplosionInteraction implements StringRepresentable {
      NONE("none"),
      BLOCK("block"),
      MOB("mob"),
      TNT("tnt"),
      TRIGGER("trigger");

      public static final Codec<ExplosionInteraction> CODEC = StringRepresentable.fromEnum(ExplosionInteraction::values);
      private final String id;

      private ExplosionInteraction(String id) {
         this.id = id;
      }

      public String getSerializedName() {
         return this.id;
      }
   }
}
