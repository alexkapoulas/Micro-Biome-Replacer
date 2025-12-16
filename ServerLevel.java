// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.LevelAttachmentsSavedData;
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.slf4j.Logger;

public class ServerLevel extends Level implements WorldGenLevel {
   public static final BlockPos END_SPAWN_POINT = new BlockPos(100, 50, 0);
   public static final IntProvider RAIN_DELAY = UniformInt.of(12000, 180000);
   public static final IntProvider RAIN_DURATION = UniformInt.of(12000, 24000);
   private static final IntProvider THUNDER_DELAY = UniformInt.of(12000, 180000);
   public static final IntProvider THUNDER_DURATION = UniformInt.of(3600, 15600);
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int EMPTY_TIME_NO_TICK = 300;
   private static final int MAX_SCHEDULED_TICKS_PER_TICK = 65536;
   final List<ServerPlayer> players;
   private final ServerChunkCache chunkSource;
   private final MinecraftServer server;
   private final ServerLevelData serverLevelData;
   private int lastSpawnChunkRadius;
   final EntityTickList entityTickList;
   private final PersistentEntitySectionManager<Entity> entityManager;
   private final GameEventDispatcher gameEventDispatcher;
   public boolean noSave;
   private final SleepStatus sleepStatus;
   private int emptyTime;
   private final PortalForcer portalForcer;
   private final LevelTicks<Block> blockTicks;
   private final LevelTicks<Fluid> fluidTicks;
   private final PathTypeCache pathTypesByPosCache;
   final Set<Mob> navigatingMobs;
   volatile boolean isUpdatingNavigations;
   protected final Raids raids;
   private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents;
   private final List<BlockEventData> blockEventsToReschedule;
   private boolean handlingTick;
   private final List<CustomSpawner> customSpawners;
   @Nullable
   private EndDragonFight dragonFight;
   final Int2ObjectMap<PartEntity<?>> dragonParts;
   private final StructureManager structureManager;
   private final StructureCheck structureCheck;
   private final boolean tickTime;
   private final RandomSequences randomSequences;
   private final CapabilityListenerHolder capListenerHolder;

   public ServerLevel(MinecraftServer server, Executor dispatcher, LevelStorageSource.LevelStorageAccess levelStorageAccess, ServerLevelData serverLevelData, ResourceKey<Level> dimension, LevelStem levelStem, ChunkProgressListener progressListener, boolean isDebug, long biomeZoomSeed, List<CustomSpawner> customSpawners, boolean tickTime, @Nullable RandomSequences randomSequences) {
      RegistryAccess.Frozen var10003 = server.registryAccess();
      Holder var10004 = levelStem.type();
      Objects.requireNonNull(server);
      super(serverLevelData, dimension, var10003, var10004, server::getProfiler, false, isDebug, biomeZoomSeed, server.getMaxChainedNeighborUpdates());
      this.players = Lists.newArrayList();
      this.entityTickList = new EntityTickList();
      this.blockTicks = new LevelTicks(this::isPositionTickingWithEntitiesLoaded, this.getProfilerSupplier());
      this.fluidTicks = new LevelTicks(this::isPositionTickingWithEntitiesLoaded, this.getProfilerSupplier());
      this.pathTypesByPosCache = new PathTypeCache();
      this.navigatingMobs = new ObjectOpenHashSet();
      this.blockEvents = new ObjectLinkedOpenHashSet();
      this.blockEventsToReschedule = new ArrayList(64);
      this.dragonParts = new Int2ObjectOpenHashMap();
      this.capListenerHolder = new CapabilityListenerHolder();
      this.tickTime = tickTime;
      this.server = server;
      this.serverLevelData = serverLevelData;
      ChunkGenerator chunkgenerator = levelStem.generator();
      boolean flag = server.forceSynchronousWrites();
      DataFixer datafixer = server.getFixerUpper();
      EntityPersistentStorage<Entity> entitypersistentstorage = new EntityStorage(new SimpleRegionStorage(new RegionStorageInfo(levelStorageAccess.getLevelId(), dimension, "entities"), levelStorageAccess.getDimensionPath(dimension).resolve("entities"), datafixer, flag, DataFixTypes.ENTITY_CHUNK), this, server);
      this.entityManager = new PersistentEntitySectionManager(Entity.class, new EntityCallbacks(), entitypersistentstorage);
      StructureTemplateManager var10006 = server.getStructureManager();
      int var10009 = server.getPlayerList().getViewDistance();
      int var10010 = server.getPlayerList().getSimulationDistance();
      PersistentEntitySectionManager var10013 = this.entityManager;
      Objects.requireNonNull(var10013);
      this.chunkSource = new ServerChunkCache(this, levelStorageAccess, datafixer, var10006, dispatcher, chunkgenerator, var10009, var10010, flag, progressListener, var10013::updateChunkStatus, () -> {
         return server.overworld().getDataStorage();
      });
      this.chunkSource.getGeneratorState().ensureStructuresGenerated();
      this.portalForcer = new PortalForcer(this);
      this.updateSkyBrightness();
      this.prepareWeather();
      this.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
      this.raids = (Raids)this.getDataStorage().computeIfAbsent(Raids.factory(this), Raids.getFileId(this.dimensionTypeRegistration()));
      if (!server.isSingleplayer()) {
         serverLevelData.setGameType(server.getDefaultGameType());
      }

      long i = server.getWorldData().worldGenOptions().seed();
      this.structureCheck = new StructureCheck(this.chunkSource.chunkScanner(), this.registryAccess(), server.getStructureManager(), dimension, chunkgenerator, this.chunkSource.randomState(), this, chunkgenerator.getBiomeSource(), i, datafixer);
      this.structureManager = new StructureManager(this, server.getWorldData().worldGenOptions(), this.structureCheck);
      if (this.dimension() == Level.END && this.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) {
         this.dragonFight = new EndDragonFight(this, i, server.getWorldData().endDragonFightData());
      } else {
         this.dragonFight = null;
      }

      this.sleepStatus = new SleepStatus();
      this.gameEventDispatcher = new GameEventDispatcher(this);
      this.randomSequences = (RandomSequences)Objects.requireNonNullElseGet(randomSequences, () -> {
         return (RandomSequences)this.getDataStorage().computeIfAbsent(RandomSequences.factory(i), "random_sequences");
      });
      LevelAttachmentsSavedData.init(this);
      this.customSpawners = EventHooks.getCustomSpawners(this, customSpawners);
   }

   /** @deprecated */
   @Deprecated
   @VisibleForTesting
   public void setDragonFight(@Nullable EndDragonFight dragonFight) {
      this.dragonFight = dragonFight;
   }

   public void setWeatherParameters(int clearTime, int weatherTime, boolean isRaining, boolean isThundering) {
      this.serverLevelData.setClearWeatherTime(clearTime);
      this.serverLevelData.setRainTime(weatherTime);
      this.serverLevelData.setThunderTime(weatherTime);
      this.serverLevelData.setRaining(isRaining);
      this.serverLevelData.setThundering(isThundering);
   }

   public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
      return this.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x, y, z, this.getChunkSource().randomState().sampler());
   }

   public StructureManager structureManager() {
      return this.structureManager;
   }

   public void tick(BooleanSupplier hasTimeLeft) {
      ProfilerFiller profilerfiller = this.getProfiler();
      this.handlingTick = true;
      TickRateManager tickratemanager = this.tickRateManager();
      boolean flag = tickratemanager.runsNormally();
      if (flag) {
         profilerfiller.push("world border");
         this.getWorldBorder().tick();
         profilerfiller.popPush("weather");
         this.advanceWeatherCycle();
      }

      int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
      long k;
      if (this.sleepStatus.areEnoughSleeping(i) && this.sleepStatus.areEnoughDeepSleeping(i, this.players)) {
         if (this.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            k = this.levelData.getDayTime() + 24000L;
            this.setDayTime(EventHooks.onSleepFinished(this, k - k % 24000L, this.getDayTime()));
         }

         this.wakeUpAllPlayers();
         if (this.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) && this.isRaining()) {
            this.resetWeatherCycle();
         }
      }

      this.updateSkyBrightness();
      if (flag) {
         this.tickTime();
      }

      profilerfiller.popPush("tickPending");
      if (!this.isDebug() && flag) {
         k = this.getGameTime();
         profilerfiller.push("blockTicks");
         this.blockTicks.tick(k, 65536, this::tickBlock);
         profilerfiller.popPush("fluidTicks");
         this.fluidTicks.tick(k, 65536, this::tickFluid);
         profilerfiller.pop();
      }

      profilerfiller.popPush("raid");
      if (flag) {
         this.raids.tick();
      }

      profilerfiller.popPush("chunkSource");
      this.getChunkSource().tick(hasTimeLeft, true);
      profilerfiller.popPush("blockEvents");
      if (flag) {
         this.runBlockEvents();
      }

      this.handlingTick = false;
      profilerfiller.pop();
      boolean flag1 = !this.players.isEmpty() || ForcedChunkManager.hasForcedChunks(this);
      if (flag1) {
         this.resetEmptyTime();
      }

      if (flag1 || this.emptyTime++ < 300) {
         profilerfiller.push("entities");
         if (this.dragonFight != null && flag) {
            profilerfiller.push("dragonFight");
            this.dragonFight.tick();
            profilerfiller.pop();
         }

         this.entityTickList.forEach((p_308566_) -> {
            if (!p_308566_.isRemoved()) {
               if (this.shouldDiscardEntity(p_308566_)) {
                  p_308566_.discard();
               } else if (!tickratemanager.isEntityFrozen(p_308566_)) {
                  profilerfiller.push("checkDespawn");
                  p_308566_.checkDespawn();
                  profilerfiller.pop();
                  if (this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(p_308566_.chunkPosition().toLong())) {
                     Entity entity = p_308566_.getVehicle();
                     if (entity != null) {
                        if (!entity.isRemoved() && entity.hasPassenger(p_308566_)) {
                           return;
                        }

                        p_308566_.stopRiding();
                     }

                     profilerfiller.push("tick");
                     if (!p_308566_.isRemoved() && !(p_308566_ instanceof PartEntity)) {
                        this.guardEntityTick(this::tickNonPassenger, p_308566_);
                     }

                     profilerfiller.pop();
                  }
               }
            }

         });
         profilerfiller.pop();
         this.tickBlockEntities();
      }

      profilerfiller.push("entityManagement");
      this.entityManager.tick();
      profilerfiller.pop();
   }

   public boolean shouldTickBlocksAt(long chunkPos) {
      return this.chunkSource.chunkMap.getDistanceManager().inBlockTickingRange(chunkPos);
   }

   protected void tickTime() {
      if (this.tickTime) {
         long i = this.levelData.getGameTime() + 1L;
         this.serverLevelData.setGameTime(i);
         this.serverLevelData.getScheduledEvents().tick(this.server, i);
         if (this.levelData.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            this.setDayTime(this.levelData.getDayTime() + this.advanceDaytime());
         }
      }

   }

   public void setDayTime(long time) {
      this.serverLevelData.setDayTime(time);
   }

   public void tickCustomSpawners(boolean spawnEnemies, boolean spawnFriendlies) {
      Iterator var3 = this.customSpawners.iterator();

      while(var3.hasNext()) {
         CustomSpawner customspawner = (CustomSpawner)var3.next();
         customspawner.tick(this, spawnEnemies, spawnFriendlies);
      }

   }

   private boolean shouldDiscardEntity(Entity entity) {
      return !this.server.isSpawningAnimals() && (entity instanceof Animal || entity instanceof WaterAnimal) ? true : !this.server.areNpcsEnabled() && entity instanceof Npc;
   }

   private void wakeUpAllPlayers() {
      this.sleepStatus.removeAllSleepers();
      ((List)this.players.stream().filter(LivingEntity::isSleeping).collect(Collectors.toList())).forEach((p_184116_) -> {
         p_184116_.stopSleepInBed(false, false);
      });
   }

   public void tickChunk(LevelChunk chunk, int randomTickSpeed) {
      ChunkPos chunkpos = chunk.getPos();
      boolean flag = this.isRaining();
      int i = chunkpos.getMinBlockX();
      int j = chunkpos.getMinBlockZ();
      ProfilerFiller profilerfiller = this.getProfiler();
      profilerfiller.push("thunder");
      if (flag && this.isThundering() && this.random.nextInt(100000) == 0) {
         BlockPos blockpos = this.findLightningTargetAround(this.getBlockRandomPos(i, 0, j, 15));
         if (this.isRainingAt(blockpos)) {
            DifficultyInstance difficultyinstance = this.getCurrentDifficultyAt(blockpos);
            boolean flag1 = this.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && this.random.nextDouble() < (double)difficultyinstance.getEffectiveDifficulty() * 0.01 && !(this.getBlockState(blockpos.below()).getBlock() instanceof LightningRodBlock);
            if (flag1) {
               SkeletonHorse skeletonhorse = (SkeletonHorse)EntityType.SKELETON_HORSE.create(this);
               if (skeletonhorse != null) {
                  skeletonhorse.setTrap(true);
                  skeletonhorse.setAge(0);
                  skeletonhorse.setPos((double)blockpos.getX(), (double)blockpos.getY(), (double)blockpos.getZ());
                  this.addFreshEntity(skeletonhorse);
               }
            }

            LightningBolt lightningbolt = (LightningBolt)EntityType.LIGHTNING_BOLT.create(this);
            if (lightningbolt != null) {
               lightningbolt.moveTo(Vec3.atBottomCenterOf(blockpos));
               lightningbolt.setVisualOnly(flag1);
               this.addFreshEntity(lightningbolt);
            }
         }
      }

      profilerfiller.popPush("iceandsnow");

      for(int i1 = 0; i1 < randomTickSpeed; ++i1) {
         if (this.random.nextInt(48) == 0) {
            this.tickPrecipitation(this.getBlockRandomPos(i, 0, j, 15));
         }
      }

      profilerfiller.popPush("tickBlocks");
      if (randomTickSpeed > 0) {
         LevelChunkSection[] alevelchunksection = chunk.getSections();

         for(int j1 = 0; j1 < alevelchunksection.length; ++j1) {
            LevelChunkSection levelchunksection = alevelchunksection[j1];
            if (levelchunksection.isRandomlyTicking()) {
               int k1 = chunk.getSectionYFromSectionIndex(j1);
               int k = SectionPos.sectionToBlockCoord(k1);

               for(int l = 0; l < randomTickSpeed; ++l) {
                  BlockPos blockpos1 = this.getBlockRandomPos(i, k, j, 15);
                  profilerfiller.push("randomTick");
                  BlockState blockstate = levelchunksection.getBlockState(blockpos1.getX() - i, blockpos1.getY() - k, blockpos1.getZ() - j);
                  if (blockstate.isRandomlyTicking()) {
                     blockstate.randomTick(this, blockpos1, this.random);
                  }

                  FluidState fluidstate = blockstate.getFluidState();
                  if (fluidstate.isRandomlyTicking()) {
                     fluidstate.randomTick(this, blockpos1, this.random);
                  }

                  profilerfiller.pop();
               }
            }
         }
      }

      profilerfiller.pop();
   }

   @VisibleForTesting
   public void tickPrecipitation(BlockPos blockPos) {
      BlockPos blockpos = this.getHeightmapPos(Types.MOTION_BLOCKING, blockPos);
      BlockPos blockpos1 = blockpos.below();
      Biome biome = (Biome)this.getBiome(blockpos).value();
      if (this.isAreaLoaded(blockpos1, 1) && biome.shouldFreeze(this, blockpos1)) {
         this.setBlockAndUpdate(blockpos1, Blocks.ICE.defaultBlockState());
      }

      if (this.isRaining()) {
         int i = this.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT);
         if (i > 0 && biome.shouldSnow(this, blockpos)) {
            BlockState blockstate = this.getBlockState(blockpos);
            if (blockstate.is(Blocks.SNOW)) {
               int j = (Integer)blockstate.getValue(SnowLayerBlock.LAYERS);
               if (j < Math.min(i, 8)) {
                  BlockState blockstate1 = (BlockState)blockstate.setValue(SnowLayerBlock.LAYERS, j + 1);
                  Block.pushEntitiesUp(blockstate, blockstate1, this, blockpos);
                  this.setBlockAndUpdate(blockpos, blockstate1);
               }
            } else {
               this.setBlockAndUpdate(blockpos, Blocks.SNOW.defaultBlockState());
            }
         }

         Biome.Precipitation biome$precipitation = biome.getPrecipitationAt(blockpos1);
         if (biome$precipitation != Precipitation.NONE) {
            BlockState blockstate2 = this.getBlockState(blockpos1);
            blockstate2.getBlock().handlePrecipitation(blockstate2, this, blockpos1, biome$precipitation);
         }
      }

   }

   private Optional<BlockPos> findLightningRod(BlockPos pos) {
      Optional<BlockPos> optional = this.getPoiManager().findClosest((p_215059_) -> {
         return p_215059_.is(PoiTypes.LIGHTNING_ROD);
      }, (p_184055_) -> {
         return p_184055_.getY() == this.getHeight(Types.WORLD_SURFACE, p_184055_.getX(), p_184055_.getZ()) - 1;
      }, pos, 128, Occupancy.ANY);
      return optional.map((p_184053_) -> {
         return p_184053_.above(1);
      });
   }

   protected BlockPos findLightningTargetAround(BlockPos pos) {
      BlockPos blockpos = this.getHeightmapPos(Types.MOTION_BLOCKING, pos);
      Optional<BlockPos> optional = this.findLightningRod(blockpos);
      if (optional.isPresent()) {
         return (BlockPos)optional.get();
      } else {
         AABB aabb = AABB.encapsulatingFullBlocks(blockpos, new BlockPos(blockpos.atY(this.getMaxBuildHeight()))).inflate(3.0);
         List<LivingEntity> list = this.getEntitiesOfClass(LivingEntity.class, aabb, (p_352698_) -> {
            return p_352698_ != null && p_352698_.isAlive() && this.canSeeSky(p_352698_.blockPosition());
         });
         if (!list.isEmpty()) {
            return ((LivingEntity)list.get(this.random.nextInt(list.size()))).blockPosition();
         } else {
            if (blockpos.getY() == this.getMinBuildHeight() - 1) {
               blockpos = blockpos.above(2);
            }

            return blockpos;
         }
      }
   }

   public boolean isHandlingTick() {
      return this.handlingTick;
   }

   public boolean canSleepThroughNights() {
      return this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE) <= 100;
   }

   private void announceSleepStatus() {
      if (this.canSleepThroughNights() && (!this.getServer().isSingleplayer() || this.getServer().isPublished())) {
         int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
         MutableComponent component;
         if (this.sleepStatus.areEnoughSleeping(i)) {
            component = Component.translatable("sleep.skipping_night");
         } else {
            component = Component.translatable("sleep.players_sleeping", new Object[]{this.sleepStatus.amountSleeping(), this.sleepStatus.sleepersNeeded(i)});
         }

         Iterator var3 = this.players.iterator();

         while(var3.hasNext()) {
            ServerPlayer serverplayer = (ServerPlayer)var3.next();
            serverplayer.displayClientMessage(component, true);
         }
      }

   }

   public void updateSleepingPlayerList() {
      if (!this.players.isEmpty() && this.sleepStatus.update(this.players)) {
         this.announceSleepStatus();
      }

   }

   public ServerScoreboard getScoreboard() {
      return this.server.getScoreboard();
   }

   private void advanceWeatherCycle() {
      boolean flag = this.isRaining();
      if (this.dimensionType().hasSkyLight()) {
         if (this.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
            int i = this.serverLevelData.getClearWeatherTime();
            int j = this.serverLevelData.getThunderTime();
            int k = this.serverLevelData.getRainTime();
            boolean flag1 = this.levelData.isThundering();
            boolean flag2 = this.levelData.isRaining();
            if (i > 0) {
               --i;
               j = flag1 ? 0 : 1;
               k = flag2 ? 0 : 1;
               flag1 = false;
               flag2 = false;
            } else {
               if (j > 0) {
                  --j;
                  if (j == 0) {
                     flag1 = !flag1;
                  }
               } else if (flag1) {
                  j = THUNDER_DURATION.sample(this.random);
               } else {
                  j = THUNDER_DELAY.sample(this.random);
               }

               if (k > 0) {
                  --k;
                  if (k == 0) {
                     flag2 = !flag2;
                  }
               } else if (flag2) {
                  k = RAIN_DURATION.sample(this.random);
               } else {
                  k = RAIN_DELAY.sample(this.random);
               }
            }

            this.serverLevelData.setThunderTime(j);
            this.serverLevelData.setRainTime(k);
            this.serverLevelData.setClearWeatherTime(i);
            this.serverLevelData.setThundering(flag1);
            this.serverLevelData.setRaining(flag2);
         }

         this.oThunderLevel = this.thunderLevel;
         if (this.levelData.isThundering()) {
            this.thunderLevel += 0.01F;
         } else {
            this.thunderLevel -= 0.01F;
         }

         this.thunderLevel = Mth.clamp(this.thunderLevel, 0.0F, 1.0F);
         this.oRainLevel = this.rainLevel;
         if (this.levelData.isRaining()) {
            this.rainLevel += 0.01F;
         } else {
            this.rainLevel -= 0.01F;
         }

         this.rainLevel = Mth.clamp(this.rainLevel, 0.0F, 1.0F);
      }

      if (this.oRainLevel != this.rainLevel) {
         this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.rainLevel), this.dimension());
      }

      if (this.oThunderLevel != this.thunderLevel) {
         this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.thunderLevel), this.dimension());
      }

      if (flag != this.isRaining()) {
         if (flag) {
            this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F), this.dimension());
         } else {
            this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F), this.dimension());
         }

         this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.rainLevel), this.dimension());
         this.server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.thunderLevel), this.dimension());
      }

   }

   @VisibleForTesting
   public void resetWeatherCycle() {
      this.serverLevelData.setRainTime(0);
      this.serverLevelData.setRaining(false);
      this.serverLevelData.setThunderTime(0);
      this.serverLevelData.setThundering(false);
   }

   public void resetEmptyTime() {
      this.emptyTime = 0;
   }

   private void tickFluid(BlockPos pos, Fluid fluid) {
      FluidState fluidstate = this.getFluidState(pos);
      if (fluidstate.is(fluid)) {
         fluidstate.tick(this, pos);
      }

   }

   private void tickBlock(BlockPos pos, Block block) {
      BlockState blockstate = this.getBlockState(pos);
      if (blockstate.is(block)) {
         blockstate.tick(this, pos, this.random);
      }

   }

   public void tickNonPassenger(Entity p_entity) {
      p_entity.setOldPosAndRot();
      ProfilerFiller profilerfiller = this.getProfiler();
      ++p_entity.tickCount;
      this.getProfiler().push(() -> {
         return BuiltInRegistries.ENTITY_TYPE.getKey(p_entity.getType()).toString();
      });
      profilerfiller.incrementCounter("tickNonPassenger");
      if (!EventHooks.fireEntityTickPre(p_entity).isCanceled()) {
         p_entity.tick();
         EventHooks.fireEntityTickPost(p_entity);
      }

      this.getProfiler().pop();
      Iterator var3 = p_entity.getPassengers().iterator();

      while(var3.hasNext()) {
         Entity entity = (Entity)var3.next();
         this.tickPassenger(p_entity, entity);
      }

   }

   private void tickPassenger(Entity ridingEntity, Entity passengerEntity) {
      if (!passengerEntity.isRemoved() && passengerEntity.getVehicle() == ridingEntity) {
         if (passengerEntity instanceof Player || this.entityTickList.contains(passengerEntity)) {
            passengerEntity.setOldPosAndRot();
            ++passengerEntity.tickCount;
            ProfilerFiller profilerfiller = this.getProfiler();
            profilerfiller.push(() -> {
               return BuiltInRegistries.ENTITY_TYPE.getKey(passengerEntity.getType()).toString();
            });
            profilerfiller.incrementCounter("tickPassenger");
            passengerEntity.rideTick();
            profilerfiller.pop();
            Iterator var4 = passengerEntity.getPassengers().iterator();

            while(var4.hasNext()) {
               Entity entity = (Entity)var4.next();
               this.tickPassenger(passengerEntity, entity);
            }
         }
      } else {
         passengerEntity.stopRiding();
      }

   }

   public boolean mayInteract(Player player, BlockPos pos) {
      return !this.server.isUnderSpawnProtection(this, pos, player) && this.getWorldBorder().isWithinBounds(pos);
   }

   public void save(@Nullable ProgressListener progress, boolean flush, boolean skipSave) {
      ServerChunkCache serverchunkcache = this.getChunkSource();
      if (!skipSave) {
         if (progress != null) {
            progress.progressStartNoAbort(Component.translatable("menu.savingLevel"));
         }

         this.saveLevelData();
         if (progress != null) {
            progress.progressStage(Component.translatable("menu.savingChunks"));
         }

         serverchunkcache.save(flush);
         if (flush) {
            this.entityManager.saveAll();
         } else {
            this.entityManager.autoSave();
         }

         NeoForge.EVENT_BUS.post(new LevelEvent.Save(this));
         if (flush) {
            IOUtilities.waitUntilIOWorkerComplete();
         }
      }

   }

   private void saveLevelData() {
      if (this.dragonFight != null) {
         this.server.getWorldData().setEndDragonFightData(this.dragonFight.saveData());
      }

      this.getChunkSource().getDataStorage().save();
   }

   public <T extends Entity> List<? extends T> getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate) {
      List<T> list = Lists.newArrayList();
      this.getEntities(typeTest, predicate, list);
      return list;
   }

   public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate, List<? super T> output) {
      this.getEntities(typeTest, predicate, output, Integer.MAX_VALUE);
   }

   public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> typeTest, Predicate<? super T> predicate, List<? super T> output, int maxResults) {
      this.getEntities().get(typeTest, (p_261428_) -> {
         if (predicate.test(p_261428_)) {
            output.add(p_261428_);
            if (output.size() >= maxResults) {
               return Continuation.ABORT;
            }
         }

         return Continuation.CONTINUE;
      });
   }

   public List<? extends EnderDragon> getDragons() {
      return this.getEntities(EntityType.ENDER_DRAGON, LivingEntity::isAlive);
   }

   public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate) {
      return this.getPlayers(predicate, Integer.MAX_VALUE);
   }

   public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate, int maxResults) {
      List<ServerPlayer> list = Lists.newArrayList();
      Iterator var4 = this.players.iterator();

      while(var4.hasNext()) {
         ServerPlayer serverplayer = (ServerPlayer)var4.next();
         if (predicate.test(serverplayer)) {
            list.add(serverplayer);
            if (list.size() >= maxResults) {
               return list;
            }
         }
      }

      return list;
   }

   @Nullable
   public ServerPlayer getRandomPlayer() {
      List<ServerPlayer> list = this.getPlayers(LivingEntity::isAlive);
      return list.isEmpty() ? null : (ServerPlayer)list.get(this.random.nextInt(list.size()));
   }

   public boolean addFreshEntity(Entity entity) {
      return this.addEntity(entity);
   }

   public boolean addWithUUID(Entity entity) {
      return this.addEntity(entity);
   }

   public void addDuringTeleport(Entity entity) {
      if (entity instanceof ServerPlayer serverplayer) {
         this.addPlayer(serverplayer);
      } else {
         this.addEntity(entity);
      }

   }

   public void addNewPlayer(ServerPlayer player) {
      this.addPlayer(player);
   }

   public void addRespawnedPlayer(ServerPlayer player) {
      this.addPlayer(player);
   }

   private void addPlayer(ServerPlayer player) {
      if (!((EntityJoinLevelEvent)NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(player, this))).isCanceled()) {
         Entity entity = (Entity)this.getEntities().get(player.getUUID());
         if (entity != null) {
            LOGGER.warn("Force-added player with duplicate UUID {}", player.getUUID());
            entity.unRide();
            this.removePlayerImmediately((ServerPlayer)entity, RemovalReason.DISCARDED);
         }

         this.entityManager.addNewEntityWithoutEvent(player);
         player.onAddedToLevel();
      }
   }

   private boolean addEntity(Entity entity) {
      if (entity.isRemoved()) {
         LOGGER.warn("Tried to add entity {} but it was marked as removed already", EntityType.getKey(entity.getType()));
         return false;
      } else if (this.entityManager.addNewEntity(entity)) {
         entity.onAddedToLevel();
         return true;
      } else {
         return false;
      }
   }

   public boolean tryAddFreshEntityWithPassengers(Entity entity) {
      Stream var10000 = entity.getSelfAndPassengers().map(Entity::getUUID);
      PersistentEntitySectionManager var10001 = this.entityManager;
      Objects.requireNonNull(var10001);
      if (var10000.anyMatch(var10001::isLoaded)) {
         return false;
      } else {
         this.addFreshEntityWithPassengers(entity);
         return true;
      }
   }

   public void unload(LevelChunk chunk) {
      chunk.clearAllBlockEntities();
      chunk.unregisterTickContainerFromLevel(this);
   }

   public void removePlayerImmediately(ServerPlayer player, Entity.RemovalReason reason) {
      player.remove(reason);
   }

   public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
      Iterator var4 = this.server.getPlayerList().getPlayers().iterator();

      while(var4.hasNext()) {
         ServerPlayer serverplayer = (ServerPlayer)var4.next();
         if (serverplayer != null && serverplayer.level() == this && serverplayer.getId() != breakerId) {
            double d0 = (double)pos.getX() - serverplayer.getX();
            double d1 = (double)pos.getY() - serverplayer.getY();
            double d2 = (double)pos.getZ() - serverplayer.getZ();
            if (d0 * d0 + d1 * d1 + d2 * d2 < 1024.0) {
               serverplayer.connection.send(new ClientboundBlockDestructionPacket(breakerId, pos, progress));
            }
         }
      }

   }

   public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
      PlayLevelSoundEvent.AtPosition event = EventHooks.onPlaySoundAtPosition(this, x, y, z, sound, category, volume, pitch);
      if (!event.isCanceled() && event.getSound() != null) {
         sound = event.getSound();
         category = event.getSource();
         volume = event.getNewVolume();
         pitch = event.getNewPitch();
         this.server.getPlayerList().broadcast(player, x, y, z, (double)((SoundEvent)sound.value()).getRange(volume), this.dimension(), new ClientboundSoundPacket(sound, category, x, y, z, volume, pitch, seed));
      }
   }

   public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
      PlayLevelSoundEvent.AtEntity event = EventHooks.onPlaySoundAtEntity(entity, sound, category, volume, pitch);
      if (!event.isCanceled() && event.getSound() != null) {
         sound = event.getSound();
         category = event.getSource();
         volume = event.getNewVolume();
         pitch = event.getNewPitch();
         this.server.getPlayerList().broadcast(player, entity.getX(), entity.getY(), entity.getZ(), (double)((SoundEvent)sound.value()).getRange(volume), this.dimension(), new ClientboundSoundEntityPacket(sound, category, entity, volume, pitch, seed));
      }
   }

   public void globalLevelEvent(int id, BlockPos pos, int data) {
      if (this.getGameRules().getBoolean(GameRules.RULE_GLOBAL_SOUND_EVENTS)) {
         this.server.getPlayerList().broadcastAll(new ClientboundLevelEventPacket(id, pos, data, true));
      } else {
         this.levelEvent((Player)null, id, pos, data);
      }

   }

   public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
      this.server.getPlayerList().broadcast(player, (double)pos.getX(), (double)pos.getY(), (double)pos.getZ(), 64.0, this.dimension(), new ClientboundLevelEventPacket(type, pos, data, false));
   }

   public int getLogicalHeight() {
      return this.dimensionType().logicalHeight();
   }

   public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
      if (CommonHooks.onVanillaGameEvent(this, gameEvent, pos, context)) {
         this.gameEventDispatcher.post(gameEvent, pos, context);
      }
   }

   public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
      if (this.isUpdatingNavigations) {
         String s = "recursive call to sendBlockUpdated";
         Util.logAndPauseIfInIde("recursive call to sendBlockUpdated", new IllegalStateException("recursive call to sendBlockUpdated"));
      }

      this.getChunkSource().blockChanged(pos);
      this.pathTypesByPosCache.invalidate(pos);
      VoxelShape voxelshape1 = oldState.getCollisionShape(this, pos);
      VoxelShape voxelshape = newState.getCollisionShape(this, pos);
      if (Shapes.joinIsNotEmpty(voxelshape1, voxelshape, BooleanOp.NOT_SAME)) {
         List<PathNavigation> list = new ObjectArrayList();
         Iterator var8 = this.navigatingMobs.iterator();

         while(var8.hasNext()) {
            Mob mob = (Mob)var8.next();
            PathNavigation pathnavigation = mob.getNavigation();
            if (pathnavigation.shouldRecomputePath(pos)) {
               list.add(pathnavigation);
            }
         }

         try {
            this.isUpdatingNavigations = true;
            var8 = list.iterator();

            while(var8.hasNext()) {
               PathNavigation pathnavigation1 = (PathNavigation)var8.next();
               pathnavigation1.recomputePath();
            }
         } finally {
            this.isUpdatingNavigations = false;
         }
      }

   }

   public void updateNeighborsAt(BlockPos pos, Block block) {
      EventHooks.onNeighborNotify(this, pos, this.getBlockState(pos), EnumSet.allOf(Direction.class), false).isCanceled();
      this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, (Direction)null);
   }

   public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block blockType, Direction skipSide) {
      EnumSet<Direction> directions = EnumSet.allOf(Direction.class);
      directions.remove(skipSide);
      if (!EventHooks.onNeighborNotify(this, pos, this.getBlockState(pos), directions, false).isCanceled()) {
         this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, blockType, skipSide);
      }
   }

   public void neighborChanged(BlockPos pos, Block block, BlockPos fromPos) {
      this.neighborUpdater.neighborChanged(pos, block, fromPos);
   }

   public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
      this.neighborUpdater.neighborChanged(state, pos, block, fromPos, isMoving);
   }

   public void broadcastEntityEvent(Entity entity, byte state) {
      this.getChunkSource().broadcastAndSend(entity, new ClientboundEntityEventPacket(entity, state));
   }

   public void broadcastDamageEvent(Entity entity, DamageSource damageSource) {
      this.getChunkSource().broadcastAndSend(entity, new ClientboundDamageEventPacket(entity, damageSource));
   }

   public ServerChunkCache getChunkSource() {
      return this.chunkSource;
   }

   public Explosion explode(@Nullable Entity source, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction explosionInteraction, ParticleOptions smallExplosionParticles, ParticleOptions largeExplosionParticles, Holder<SoundEvent> explosionSound) {
      Explosion explosion = this.explode(source, damageSource, damageCalculator, x, y, z, radius, fire, explosionInteraction, false, smallExplosionParticles, largeExplosionParticles, explosionSound);
      if (!explosion.interactsWithBlocks()) {
         explosion.clearToBlow();
      }

      Iterator var17 = this.players.iterator();

      while(var17.hasNext()) {
         ServerPlayer serverplayer = (ServerPlayer)var17.next();
         if (serverplayer.distanceToSqr(x, y, z) < 4096.0) {
            serverplayer.connection.send(new ClientboundExplodePacket(x, y, z, radius, explosion.getToBlow(), (Vec3)explosion.getHitPlayers().get(serverplayer), explosion.getBlockInteraction(), explosion.getSmallExplosionParticles(), explosion.getLargeExplosionParticles(), explosion.getExplosionSound()));
         }
      }

      return explosion;
   }

   public void blockEvent(BlockPos pos, Block block, int eventID, int eventParam) {
      this.blockEvents.add(new BlockEventData(pos, block, eventID, eventParam));
   }

   private void runBlockEvents() {
      this.blockEventsToReschedule.clear();

      while(!this.blockEvents.isEmpty()) {
         BlockEventData blockeventdata = (BlockEventData)this.blockEvents.removeFirst();
         if (this.shouldTickBlocksAt(blockeventdata.pos())) {
            if (this.doBlockEvent(blockeventdata)) {
               this.server.getPlayerList().broadcast((Player)null, (double)blockeventdata.pos().getX(), (double)blockeventdata.pos().getY(), (double)blockeventdata.pos().getZ(), 64.0, this.dimension(), new ClientboundBlockEventPacket(blockeventdata.pos(), blockeventdata.block(), blockeventdata.paramA(), blockeventdata.paramB()));
            }
         } else {
            this.blockEventsToReschedule.add(blockeventdata);
         }
      }

      this.blockEvents.addAll(this.blockEventsToReschedule);
   }

   private boolean doBlockEvent(BlockEventData event) {
      BlockState blockstate = this.getBlockState(event.pos());
      return blockstate.is(event.block()) ? blockstate.triggerEvent(this, event.pos(), event.paramA(), event.paramB()) : false;
   }

   public LevelTicks<Block> getBlockTicks() {
      return this.blockTicks;
   }

   public LevelTicks<Fluid> getFluidTicks() {
      return this.fluidTicks;
   }

   @Nonnull
   public MinecraftServer getServer() {
      return this.server;
   }

   public PortalForcer getPortalForcer() {
      return this.portalForcer;
   }

   public StructureTemplateManager getStructureManager() {
      return this.server.getStructureManager();
   }

   public <T extends ParticleOptions> int sendParticles(T type, double posX, double posY, double posZ, int particleCount, double xOffset, double yOffset, double zOffset, double speed) {
      ClientboundLevelParticlesPacket clientboundlevelparticlespacket = new ClientboundLevelParticlesPacket(type, false, posX, posY, posZ, (float)xOffset, (float)yOffset, (float)zOffset, (float)speed, particleCount);
      int i = 0;

      for(int j = 0; j < this.players.size(); ++j) {
         ServerPlayer serverplayer = (ServerPlayer)this.players.get(j);
         if (this.sendParticles(serverplayer, false, posX, posY, posZ, clientboundlevelparticlespacket)) {
            ++i;
         }
      }

      return i;
   }

   public <T extends ParticleOptions> boolean sendParticles(ServerPlayer player, T type, boolean longDistance, double posX, double posY, double posZ, int particleCount, double xOffset, double yOffset, double zOffset, double speed) {
      Packet<?> packet = new ClientboundLevelParticlesPacket(type, longDistance, posX, posY, posZ, (float)xOffset, (float)yOffset, (float)zOffset, (float)speed, particleCount);
      return this.sendParticles(player, longDistance, posX, posY, posZ, packet);
   }

   private boolean sendParticles(ServerPlayer player, boolean longDistance, double posX, double posY, double posZ, Packet<?> packet) {
      if (player.level() != this) {
         return false;
      } else {
         BlockPos blockpos = player.blockPosition();
         if (blockpos.closerToCenterThan(new Vec3(posX, posY, posZ), longDistance ? 512.0 : 32.0)) {
            player.connection.send(packet);
            return true;
         } else {
            return false;
         }
      }
   }

   @Nullable
   public Entity getEntity(int id) {
      return (Entity)this.getEntities().get(id);
   }

   /** @deprecated */
   @Deprecated
   @Nullable
   public Entity getEntityOrPart(int id) {
      Entity entity = (Entity)this.getEntities().get(id);
      return entity != null ? entity : (Entity)this.dragonParts.get(id);
   }

   @Nullable
   public Entity getEntity(UUID uniqueId) {
      return (Entity)this.getEntities().get(uniqueId);
   }

   @Nullable
   public BlockPos findNearestMapStructure(TagKey<Structure> structureTag, BlockPos pos, int radius, boolean skipExistingChunks) {
      if (!this.server.getWorldData().worldGenOptions().generateStructures()) {
         return null;
      } else {
         Optional<HolderSet.Named<Structure>> optional = this.registryAccess().registryOrThrow(Registries.STRUCTURE).getTag(structureTag);
         if (optional.isEmpty()) {
            return null;
         } else {
            Pair<BlockPos, Holder<Structure>> pair = this.getChunkSource().getGenerator().findNearestMapStructure(this, (HolderSet)optional.get(), pos, radius, skipExistingChunks);
            return pair != null ? (BlockPos)pair.getFirst() : null;
         }
      }
   }

   @Nullable
   public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(Predicate<Holder<Biome>> biomePredicate, BlockPos pos, int radius, int horizontalStep, int verticalStep) {
      return this.getChunkSource().getGenerator().getBiomeSource().findClosestBiome3d(pos, radius, horizontalStep, verticalStep, biomePredicate, this.getChunkSource().randomState().sampler(), this);
   }

   public RecipeManager getRecipeManager() {
      return this.server.getRecipeManager();
   }

   public TickRateManager tickRateManager() {
      return this.server.tickRateManager();
   }

   public boolean noSave() {
      return this.noSave;
   }

   public DimensionDataStorage getDataStorage() {
      return this.getChunkSource().getDataStorage();
   }

   @Nullable
   public MapItemSavedData getMapData(MapId mapId) {
      return (MapItemSavedData)this.getServer().overworld().getDataStorage().get(MapItemSavedData.factory(), mapId.key());
   }

   public void setMapData(MapId mapId, MapItemSavedData mapData) {
      this.getServer().overworld().getDataStorage().set(mapId.key(), mapData);
   }

   public MapId getFreeMapId() {
      return ((MapIndex)this.getServer().overworld().getDataStorage().computeIfAbsent(MapIndex.factory(), "idcounts")).getFreeAuxValueForMap();
   }

   public void setDefaultSpawnPos(BlockPos pos, float angle) {
      BlockPos blockpos = this.levelData.getSpawnPos();
      float f = this.levelData.getSpawnAngle();
      if (!blockpos.equals(pos) || f != angle) {
         this.levelData.setSpawn(pos, angle);
         this.getServer().getPlayerList().broadcastAll(new ClientboundSetDefaultSpawnPositionPacket(pos, angle));
      }

      if (this.lastSpawnChunkRadius > 1) {
         this.getChunkSource().removeRegionTicket(TicketType.START, new ChunkPos(blockpos), this.lastSpawnChunkRadius, Unit.INSTANCE);
      }

      int i = this.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) + 1;
      if (i > 1) {
         this.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(pos), i, Unit.INSTANCE);
      }

      this.lastSpawnChunkRadius = i;
   }

   public LongSet getForcedChunks() {
      ForcedChunksSavedData forcedchunkssaveddata = (ForcedChunksSavedData)this.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
      return (LongSet)(forcedchunkssaveddata != null ? LongSets.unmodifiable(forcedchunkssaveddata.getChunks()) : LongSets.EMPTY_SET);
   }

   public boolean setChunkForced(int chunkX, int chunkZ, boolean add) {
      ForcedChunksSavedData forcedchunkssaveddata = (ForcedChunksSavedData)this.getDataStorage().computeIfAbsent(ForcedChunksSavedData.factory(), "chunks");
      ChunkPos chunkpos = new ChunkPos(chunkX, chunkZ);
      long i = chunkpos.toLong();
      boolean flag;
      if (add) {
         flag = forcedchunkssaveddata.getChunks().add(i);
         if (flag) {
            this.getChunk(chunkX, chunkZ);
         }
      } else {
         flag = forcedchunkssaveddata.getChunks().remove(i);
      }

      forcedchunkssaveddata.setDirty(flag);
      if (flag) {
         this.getChunkSource().updateChunkForced(chunkpos, add);
      }

      return flag;
   }

   public List<ServerPlayer> players() {
      return this.players;
   }

   public void onBlockStateChange(BlockPos pos, BlockState blockState, BlockState newState) {
      Optional<Holder<PoiType>> optional = PoiTypes.forState(blockState);
      Optional<Holder<PoiType>> optional1 = PoiTypes.forState(newState);
      if (!Objects.equals(optional, optional1)) {
         BlockPos blockpos = pos.immutable();
         optional.ifPresent((p_215081_) -> {
            this.getServer().execute(() -> {
               this.getPoiManager().remove(blockpos);
               DebugPackets.sendPoiRemovedPacket(this, blockpos);
            });
         });
         optional1.ifPresent((p_215057_) -> {
            this.getServer().execute(() -> {
               this.getPoiManager().add(blockpos, p_215057_);
               DebugPackets.sendPoiAddedPacket(this, blockpos);
            });
         });
      }

   }

   public PoiManager getPoiManager() {
      return this.getChunkSource().getPoiManager();
   }

   public boolean isVillage(BlockPos pos) {
      return this.isCloseToVillage(pos, 1);
   }

   public boolean isVillage(SectionPos pos) {
      return this.isVillage(pos.center());
   }

   public boolean isCloseToVillage(BlockPos pos, int sections) {
      return sections > 6 ? false : this.sectionsToVillage(SectionPos.of(pos)) <= sections;
   }

   public int sectionsToVillage(SectionPos pos) {
      return this.getPoiManager().sectionsToVillage(pos);
   }

   public Raids getRaids() {
      return this.raids;
   }

   @Nullable
   public Raid getRaidAt(BlockPos pos) {
      return this.raids.getNearbyRaid(pos, 9216);
   }

   public boolean isRaided(BlockPos pos) {
      return this.getRaidAt(pos) != null;
   }

   public void onReputationEvent(ReputationEventType type, Entity target, ReputationEventHandler host) {
      host.onReputationEventFrom(type, target);
   }

   public void saveDebugReport(Path p_path) throws IOException {
      ChunkMap chunkmap = this.getChunkSource().chunkMap;
      Writer writer = Files.newBufferedWriter(p_path.resolve("stats.txt"));

      try {
         writer.write(String.format(Locale.ROOT, "spawning_chunks: %d\n", chunkmap.getDistanceManager().getNaturalSpawnChunkCount()));
         NaturalSpawner.SpawnState naturalspawner$spawnstate = this.getChunkSource().getLastSpawnState();
         if (naturalspawner$spawnstate != null) {
            ObjectIterator var5 = naturalspawner$spawnstate.getMobCategoryCounts().object2IntEntrySet().iterator();

            while(var5.hasNext()) {
               Object2IntMap.Entry<MobCategory> entry = (Object2IntMap.Entry)var5.next();
               writer.write(String.format(Locale.ROOT, "spawn_count.%s: %d\n", ((MobCategory)entry.getKey()).getName(), entry.getIntValue()));
            }
         }

         writer.write(String.format(Locale.ROOT, "entities: %s\n", this.entityManager.gatherStats()));
         writer.write(String.format(Locale.ROOT, "block_entity_tickers: %d\n", this.blockEntityTickers.size()));
         writer.write(String.format(Locale.ROOT, "block_ticks: %d\n", this.getBlockTicks().count()));
         writer.write(String.format(Locale.ROOT, "fluid_ticks: %d\n", this.getFluidTicks().count()));
         writer.write("distance_manager: " + chunkmap.getDistanceManager().getDebugStatus() + "\n");
         writer.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getChunkSource().getPendingTasksCount()));
      } catch (Throwable var22) {
         if (writer != null) {
            try {
               writer.close();
            } catch (Throwable var11) {
               var22.addSuppressed(var11);
            }
         }

         throw var22;
      }

      if (writer != null) {
         writer.close();
      }

      CrashReport crashreport = new CrashReport("Level dump", new Exception("dummy"));
      this.fillReportDetails(crashreport);
      Writer writer3 = Files.newBufferedWriter(p_path.resolve("example_crash.txt"));

      try {
         writer3.write(crashreport.getFriendlyReport(ReportType.TEST));
      } catch (Throwable var18) {
         if (writer3 != null) {
            try {
               writer3.close();
            } catch (Throwable var16) {
               var18.addSuppressed(var16);
            }
         }

         throw var18;
      }

      if (writer3 != null) {
         writer3.close();
      }

      Path path = p_path.resolve("chunks.csv");
      Writer writer4 = Files.newBufferedWriter(path);

      try {
         chunkmap.dumpChunks(writer4);
      } catch (Throwable var17) {
         if (writer4 != null) {
            try {
               writer4.close();
            } catch (Throwable var12) {
               var17.addSuppressed(var12);
            }
         }

         throw var17;
      }

      if (writer4 != null) {
         writer4.close();
      }

      Path path1 = p_path.resolve("entity_chunks.csv");
      Writer writer5 = Files.newBufferedWriter(path1);

      try {
         this.entityManager.dumpSections(writer5);
      } catch (Throwable var19) {
         if (writer5 != null) {
            try {
               writer5.close();
            } catch (Throwable var13) {
               var19.addSuppressed(var13);
            }
         }

         throw var19;
      }

      if (writer5 != null) {
         writer5.close();
      }

      Path path2 = p_path.resolve("entities.csv");
      Writer writer1 = Files.newBufferedWriter(path2);

      try {
         dumpEntities(writer1, this.getEntities().getAll());
      } catch (Throwable var21) {
         if (writer1 != null) {
            try {
               writer1.close();
            } catch (Throwable var15) {
               var21.addSuppressed(var15);
            }
         }

         throw var21;
      }

      if (writer1 != null) {
         writer1.close();
      }

      Path path3 = p_path.resolve("block_entities.csv");
      Writer writer2 = Files.newBufferedWriter(path3);

      try {
         this.dumpBlockEntityTickers(writer2);
      } catch (Throwable var20) {
         if (writer2 != null) {
            try {
               writer2.close();
            } catch (Throwable var14) {
               var20.addSuppressed(var14);
            }
         }

         throw var20;
      }

      if (writer2 != null) {
         writer2.close();
      }

   }

   private static void dumpEntities(Writer writer, Iterable<Entity> entities) throws IOException {
      CsvOutput csvoutput = CsvOutput.builder().addColumn("x").addColumn("y").addColumn("z").addColumn("uuid").addColumn("type").addColumn("alive").addColumn("display_name").addColumn("custom_name").build(writer);
      Iterator var3 = entities.iterator();

      while(var3.hasNext()) {
         Entity entity = (Entity)var3.next();
         Component component = entity.getCustomName();
         Component component1 = entity.getDisplayName();
         csvoutput.writeRow(new Object[]{entity.getX(), entity.getY(), entity.getZ(), entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.isAlive(), component1.getString(), component != null ? component.getString() : null});
      }

   }

   private void dumpBlockEntityTickers(Writer output) throws IOException {
      CsvOutput csvoutput = CsvOutput.builder().addColumn("x").addColumn("y").addColumn("z").addColumn("type").build(output);
      Iterator var3 = this.blockEntityTickers.iterator();

      while(var3.hasNext()) {
         TickingBlockEntity tickingblockentity = (TickingBlockEntity)var3.next();
         BlockPos blockpos = tickingblockentity.getPos();
         csvoutput.writeRow(new Object[]{blockpos.getX(), blockpos.getY(), blockpos.getZ(), tickingblockentity.getType()});
      }

   }

   @VisibleForTesting
   public void clearBlockEvents(BoundingBox boundingBox) {
      this.blockEvents.removeIf((p_207568_) -> {
         return boundingBox.isInside(p_207568_.pos());
      });
   }

   public void blockUpdated(BlockPos pos, Block block) {
      if (!this.isDebug()) {
         this.updateNeighborsAt(pos, block);
      }

   }

   public float getShade(Direction direction, boolean shade) {
      return 1.0F;
   }

   public Iterable<Entity> getAllEntities() {
      return this.getEntities().getAll();
   }

   public String toString() {
      return "ServerLevel[" + this.serverLevelData.getLevelName() + "]";
   }

   public boolean isFlat() {
      return this.server.getWorldData().isFlatWorld();
   }

   public long getSeed() {
      return this.server.getWorldData().worldGenOptions().seed();
   }

   @Nullable
   public EndDragonFight getDragonFight() {
      return this.dragonFight;
   }

   public ServerLevel getLevel() {
      return this;
   }

   @VisibleForTesting
   public String getWatchdogStats() {
      return String.format(Locale.ROOT, "players: %s, entities: %s [%s], block_entities: %d [%s], block_ticks: %d, fluid_ticks: %d, chunk_source: %s", this.players.size(), this.entityManager.gatherStats(), getTypeCount(this.entityManager.getEntityGetter().getAll(), (p_258244_) -> {
         return BuiltInRegistries.ENTITY_TYPE.getKey(p_258244_.getType()).toString();
      }), this.blockEntityTickers.size(), getTypeCount(this.blockEntityTickers, TickingBlockEntity::getType), this.getBlockTicks().count(), this.getFluidTicks().count(), this.gatherChunkSourceStats());
   }

   private static <T> String getTypeCount(Iterable<T> objects, Function<T, String> typeGetter) {
      try {
         Object2IntOpenHashMap<String> object2intopenhashmap = new Object2IntOpenHashMap();
         Iterator var3 = objects.iterator();

         while(var3.hasNext()) {
            T t = var3.next();
            String s = (String)typeGetter.apply(t);
            object2intopenhashmap.addTo(s, 1);
         }

         return (String)object2intopenhashmap.object2IntEntrySet().stream().sorted(Comparator.comparing(Object2IntMap.Entry::getIntValue).reversed()).limit(5L).map((p_207570_) -> {
            String var10000 = (String)p_207570_.getKey();
            return var10000 + ":" + p_207570_.getIntValue();
         }).collect(Collectors.joining(","));
      } catch (Exception var6) {
         return "";
      }
   }

   public LevelEntityGetter<Entity> getEntities() {
      return this.entityManager.getEntityGetter();
   }

   public void addLegacyChunkEntities(Stream<Entity> entities) {
      this.entityManager.addLegacyChunkEntities(entities);
   }

   public void addWorldGenChunkEntities(Stream<Entity> entities) {
      this.entityManager.addWorldGenChunkEntities(entities);
   }

   public void startTickingChunk(LevelChunk chunk) {
      chunk.unpackTicks(this.getLevelData().getGameTime());
   }

   public void onStructureStartsAvailable(ChunkAccess chunk) {
      this.server.execute(() -> {
         this.structureCheck.onStructureLoad(chunk.getPos(), chunk.getAllStarts());
      });
   }

   public PathTypeCache getPathTypeCache() {
      return this.pathTypesByPosCache;
   }

   public void close() throws IOException {
      super.close();
      this.entityManager.close();
   }

   public String gatherChunkSourceStats() {
      String var10000 = this.chunkSource.gatherStats();
      return "Chunks[S] W: " + var10000 + " E: " + this.entityManager.gatherStats();
   }

   public boolean areEntitiesLoaded(long chunkPos) {
      return this.entityManager.areEntitiesLoaded(chunkPos);
   }

   private boolean isPositionTickingWithEntitiesLoaded(long chunkPos) {
      return this.areEntitiesLoaded(chunkPos) && this.chunkSource.isPositionTicking(chunkPos);
   }

   public boolean isPositionEntityTicking(BlockPos pos) {
      return this.entityManager.canPositionTick(pos) && this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(ChunkPos.asLong(pos));
   }

   public boolean isNaturalSpawningAllowed(BlockPos pos) {
      return this.entityManager.canPositionTick(pos);
   }

   public boolean isNaturalSpawningAllowed(ChunkPos chunkPos) {
      return this.entityManager.canPositionTick(chunkPos);
   }

   public FeatureFlagSet enabledFeatures() {
      return this.server.getWorldData().enabledFeatures();
   }

   public PotionBrewing potionBrewing() {
      return this.server.potionBrewing();
   }

   public RandomSource getRandomSequence(ResourceLocation location) {
      return this.randomSequences.get(location);
   }

   public RandomSequences getRandomSequences() {
      return this.randomSequences;
   }

   public CrashReportCategory fillReportDetails(CrashReport report) {
      CrashReportCategory crashreportcategory = super.fillReportDetails(report);
      crashreportcategory.setDetail("Loaded entity count", () -> {
         return String.valueOf(this.entityManager.count());
      });
      return crashreportcategory;
   }

   public Collection<PartEntity<?>> getPartEntities() {
      return this.dragonParts.values();
   }

   public final void syncData(AttachmentType<?> type) {
      AttachmentSync.syncLevelUpdate(this, type);
   }

   public void invalidateCapabilities(BlockPos pos) {
      this.capListenerHolder.invalidatePos(pos);
   }

   public void invalidateCapabilities(ChunkPos pos) {
      this.capListenerHolder.invalidateChunk(pos);
   }

   public void registerCapabilityListener(BlockPos pos, ICapabilityInvalidationListener listener) {
      this.capListenerHolder.addListener(pos, listener);
   }

   @Internal
   public void cleanCapabilityListenerReferences() {
      this.capListenerHolder.clean();
   }

   @Internal
   public void setDayTimeFraction(float dayTimeFraction) {
      this.serverLevelData.setDayTimeFraction(dayTimeFraction);
   }

   @Internal
   public float getDayTimeFraction() {
      return this.serverLevelData.getDayTimeFraction();
   }

   public float getDayTimePerTick() {
      return this.serverLevelData.getDayTimePerTick();
   }

   public void setDayTimePerTick(float dayTimePerTick) {
      if (dayTimePerTick != this.getDayTimePerTick() && dayTimePerTick != 0.0F) {
         this.serverLevelData.setDayTimePerTick(dayTimePerTick);
         this.server.forceTimeSynchronization();
      }

   }

   final class EntityCallbacks implements LevelCallback<Entity> {
      EntityCallbacks() {
      }

      public void onCreated(Entity p_143355_) {
      }

      public void onDestroyed(Entity p_143359_) {
         ServerLevel.this.getScoreboard().entityRemoved(p_143359_);
      }

      public void onTickingStart(Entity p_143363_) {
         ServerLevel.this.entityTickList.add(p_143363_);
      }

      public void onTickingEnd(Entity p_143367_) {
         ServerLevel.this.entityTickList.remove(p_143367_);
      }

      public void onTrackingStart(Entity p_143371_) {
         ServerLevel.this.getChunkSource().addEntity(p_143371_);
         if (p_143371_ instanceof ServerPlayer serverplayer) {
            ServerLevel.this.players.add(serverplayer);
            ServerLevel.this.updateSleepingPlayerList();
         }

         if (p_143371_ instanceof Mob mob) {
            if (ServerLevel.this.isUpdatingNavigations) {
               String s = "onTrackingStart called during navigation iteration";
               Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration"));
            }

            ServerLevel.this.navigatingMobs.add(mob);
         }

         if (p_143371_.isMultipartEntity()) {
            PartEntity[] var7 = p_143371_.getParts();
            int var8 = var7.length;

            for(int var4 = 0; var4 < var8; ++var4) {
               PartEntity<?> enderdragonpart = var7[var4];
               ServerLevel.this.dragonParts.put(enderdragonpart.getId(), enderdragonpart);
            }
         }

         p_143371_.updateDynamicGameEventListener(DynamicGameEventListener::add);
      }

      public void onTrackingEnd(Entity p_143375_) {
         ServerLevel.this.getChunkSource().removeEntity(p_143375_);
         if (p_143375_ instanceof ServerPlayer serverplayer) {
            ServerLevel.this.players.remove(serverplayer);
            ServerLevel.this.updateSleepingPlayerList();
         }

         if (p_143375_ instanceof Mob mob) {
            if (ServerLevel.this.isUpdatingNavigations) {
               String s = "onTrackingStart called during navigation iteration";
               Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration"));
            }

            ServerLevel.this.navigatingMobs.remove(mob);
         }

         if (p_143375_.isMultipartEntity()) {
            PartEntity[] var7 = p_143375_.getParts();
            int var8 = var7.length;

            for(int var4 = 0; var4 < var8; ++var4) {
               PartEntity<?> enderdragonpart = var7[var4];
               ServerLevel.this.dragonParts.remove(enderdragonpart.getId());
            }
         }

         p_143375_.updateDynamicGameEventListener(DynamicGameEventListener::remove);
         p_143375_.onRemovedFromLevel();
         NeoForge.EVENT_BUS.post(new EntityLeaveLevelEvent(p_143375_, ServerLevel.this));
      }

      public void onSectionChange(Entity p_215086_) {
         p_215086_.updateDynamicGameEventListener(DynamicGameEventListener::move);
      }
   }
}
