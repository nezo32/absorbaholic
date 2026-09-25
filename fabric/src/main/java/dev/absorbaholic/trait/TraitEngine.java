package dev.absorbaholic.trait;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.ColorMix;
import dev.absorbaholic.core.FactorMath;
import dev.absorbaholic.net.AuraPayload;
import dev.absorbaholic.net.MovementPayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.player.TraitsChangedCallback;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.world.AbsorbWorldSettings;
import dev.absorbaholic.world.WorldSettingsEvents;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The trait engine: keeps each player's {@link ActiveSet} and attribute modifiers in sync with their traits and the
 * world / game-mode state, and dispatches behavior hooks with the caps of {@code AbsorbCaps}. Every public method is
 * server-thread only and never throws into vanilla code: a failing behavior is logged (ERROR the first time per
 * behavior type, DEBUG afterwards) and skipped, like the reference's CustomEffects.safely.
 *
 * <p>Wiring (done in {@link #register}): TraitsChangedCallback / JOIN / AFTER_PLAYER_CHANGE_LEVEL /
 * END_DATA_PACK_RELOAD → {@link #markDirty}; AFTER_RESPAWN (late phase, after Fabric's attachment transfer) → reset
 * the new entity's engine state and mark dirty; WorldSettingsEvents.CHANGED → dirty when a player's activity flips;
 * END_SERVER_TICK → per player: active-state flip check, rebuild when dirty (diff: deactivate removed entries,
 * {@link AttributeApplier#apply}, activate added entries, sync MovementPayload and aura), input edges from
 * {@code getLastClientInput()} (jump on ground → onJump; sneak+jump on ground → sneak_jump trigger; jump while
 * airborne → air_jump trigger; two sneak edges within SNEAK_DOUBLE_TAP_TICKS on ground → sneak_double_tap trigger),
 * due ticks, periodic re-clamp; ServerLivingEntityEvents.ALLOW_DAMAGE → immunity; AFTER_DAMAGE → dealt / attacked;
 * AFTER_DEATH → death hooks; ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY → kill; ServerMobEffectEvents.ALLOW_ADD
 * → allowEffect (skips owned effects); AttackEntityCallback (server, sneaking) → sneak attack;
 * PlayerBlockBreakEvents.AFTER → block break; EntityElytraEvents.CUSTOM → GLIDE (both sides);
 * ServerPlayConnectionEvents.DISCONNECT → deactivate; EntityTrackingEvents.START_TRACKING (player) → send that
 * player's aura. Mixins call the modify* / on* methods below. Triggers go through {@link #fireTrigger}.
 *
 * <p>A dead player keeps its cached set (its entries are deactivated once) until the respawned entity replaces it, so
 * {@link #wipesOnDeath} still sees the dragon egg from ServerPlayerEvents.COPY_FROM.
 */
public final class TraitEngine {
	private static final Identifier STATE_KEY = Absorbaholic.id("engine");
	private static final Identifier RESPAWN_PHASE = Absorbaholic.id("engine_after_respawn");
	private static final ResourceKey<DamageType> WEAKNESS = WeaknessDamage.TYPE;
	private static final List<ResourceKey<DamageType>> NEVER_IMMUNE = AbsorbCaps.NEVER_IMMUNE_DAMAGE_TYPES.stream()
			.map(id -> ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(id)))
			.toList();
	/** Behavior types (and engine sections) whose failure was already logged at ERROR level. */
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

	/** True while {@link #addOwnedEffect} adds an effect (skips allowEffect / modifyEffect). Server thread only. */
	private static boolean addingOwnedEffect;
	/** Depth of FoodData#tick (natural regeneration) on the server thread. */
	private static int naturalRegenDepth;

	private TraitEngine() {}

	public static void register() {
		TraitsChangedCallback.EVENT.register((player, before, after) -> markDirty(player));
		WorldSettingsEvents.CHANGED.register((server, settings) -> {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (isActive(p) != PlayerData.runtime(p).wasActive) markDirty(p);
			}
		});
		// late phase: on an End exit Fabric transfers the old entity's attachments (our runtime included) in the default one
		ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(Event.DEFAULT_PHASE, RESPAWN_PHASE);
		ServerPlayerEvents.AFTER_RESPAWN.register(RESPAWN_PHASE, (oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));
		ServerPlayerEvents.JOIN.register(TraitEngine::markDirty);
		ServerPlayerEvents.COPY_FROM.register(TraitEngine::carryHealth);
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> markDirty(player));
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) markDirty(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> deactivate(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(TraitEngine::endServerTick);

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer p && isImmune(p, source)));
		ServerLivingEntityEvents.AFTER_DAMAGE.register(TraitEngine::afterDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register(TraitEngine::afterDeath);
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, victim, source) -> {
			if (killer instanceof ServerPlayer p && isActive(p)) {
				for (ActiveBehavior<?> a : active(p).forHook(Hook.KILL)) {
					try {
						a.onKill(p, victim);
					} catch (Throwable t) {
						failed(a, Hook.KILL, t);
					}
				}
			}
		});
		ServerMobEffectEvents.ALLOW_ADD.register((effect, entity, context) -> !(entity instanceof ServerPlayer p) || allowEffect(p, effect));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer p && p.isShiftKeyDown() && isActive(p)) {
				for (ActiveBehavior<?> a : active(p).forHook(Hook.SNEAK_ATTACK)) {
					try {
						a.onSneakAttack(p, entity);
					} catch (Throwable t) {
						failed(a, Hook.SNEAK_ATTACK, t);
					}
				}
			}
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer p && isActive(p)) {
				for (ActiveBehavior<?> a : active(p).forHook(Hook.BLOCK_BREAK)) {
					try {
						a.onBlockBreak(p, pos, state);
					} catch (Throwable t) {
						failed(a, Hook.BLOCK_BREAK, t);
					}
				}
			}
		});
		// both sides: the client glides with the synced flag, the server keeps the fall-flying state
		EntityElytraEvents.CUSTOM.register((entity, tickElytra) ->
				entity instanceof Player p && ((MovementFlagsHolder) p).absorbaholic$movement().has(MovementFlags.GLIDE));
		EntityTrackingEvents.START_TRACKING.register((tracked, watcher) -> {
			if (tracked instanceof ServerPlayer p) {
				PlayerRuntime rt = PlayerData.runtime(p);
				if (rt.sentAuraStrength > 0.0F && ServerPlayNetworking.canSend(watcher, AuraPayload.TYPE)) {
					ServerPlayNetworking.send(watcher, new AuraPayload(p.getId(), rt.sentAuraColor, rt.sentAuraStrength));
				}
			}
		});
	}

	/**
	 * Traits and weaknesses do anything only when this is true: world mode ON, survival / adventure (server-side game
	 * mode, not {@code isCreative()}: gametest mock players fake creative), alive, not a FakePlayer.
	 */
	public static boolean isActive(ServerPlayer player) {
		return player.isAlive() && eligible(player, AbsorbWorldSettings.isEnabled(player.level().getServer()));
	}

	/** Everything of {@link #isActive} except being alive. */
	private static boolean eligible(ServerPlayer player, boolean mode) {
		return mode && !(player instanceof FakePlayer) && !player.hasDisconnected()
				&& player.gameMode.getGameModeForPlayer().isSurvival();
	}

	/** Rebuild the player's active set and attributes on the next tick. */
	public static void markDirty(ServerPlayer player) {
		if (player instanceof FakePlayer) return;
		PlayerData.runtime(player).dirty = true;
	}

	/** Rebuild now (commands and gametests that need the result synchronously). */
	public static void recompute(ServerPlayer player) {
		if (player instanceof FakePlayer) return;
		try {
			PlayerRuntime rt = PlayerData.runtime(player);
			boolean eligible = eligible(player, AbsorbWorldSettings.isEnabled(player.level().getServer()));
			if (!player.isAlive()) {
				onDead(player, rt, eligible);
			} else {
				rebuild(player, rt, eligible);
			}
		} catch (Throwable t) {
			failedEngine("recompute", t);
		}
	}

	/** The cached active set ({@link ActiveSet#EMPTY} while dormant). */
	public static ActiveSet active(ServerPlayer player) {
		return PlayerData.runtime(player).active;
	}

	// ---- recompute --------------------------------------------------------------------------------------------

	private static void endServerTick(MinecraftServer server) {
		long now = server.getTickCount();
		boolean mode = AbsorbWorldSettings.isEnabled(server);
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) return;
		for (ServerPlayer p : List.copyOf(players)) {
			if (p instanceof FakePlayer) continue;
			try {
				tickPlayer(p, now, mode);
			} catch (Throwable t) {
				failedEngine("tick", t);
			}
		}
	}

	private static void tickPlayer(ServerPlayer p, long now, boolean mode) {
		PlayerRuntime rt = PlayerData.runtime(p);
		EngineState st = state(rt);
		Input input = p.getLastClientInput();
		Input prev = rt.lastInput;
		rt.lastInput = input;
		boolean grounded = p.onGround();
		boolean groundedBefore = st.groundedLastTick;
		st.groundedLastTick = grounded;

		boolean eligible = eligible(p, mode);
		if (!p.isAlive()) {
			onDead(p, rt, eligible);
			return;
		}
		if (eligible != rt.wasActive) rt.dirty = true;
		if (rt.dirty) rebuild(p, rt, eligible);
		ActiveSet set = rt.active;
		if (!eligible || set.isEmpty()) return;
		rt.damageGate.observe(now, p.getHealth(), p.getMaxHealth());
		if (rt.weaknessFireUntil > now && p.getRemainingFireTicks() <= 0) rt.weaknessFireUntil = Long.MIN_VALUE / 2; // put out

		if (input.jump() && !prev.jump()) {
			boolean airborne = Condition.PREDICATES.get("airborne").test(p, Condition.ALWAYS);
			Hook jump = InputEdges.jump(grounded, groundedBefore, airborne, input.shift());
			if (jump == Hook.JUMP) {
				for (ActiveBehavior<?> a : set.forHook(Hook.JUMP)) {
					try {
						a.onJump(p);
					} catch (Throwable t) {
						failed(a, Hook.JUMP, t);
					}
				}
			} else if (jump != null) {
				fireTrigger(p, jump, null);
			}
		}
		if (input.shift() && !prev.shift()) {
			if (grounded && InputEdges.doubleTap(rt.lastSneakEdge, now)) {
				rt.lastSneakEdge = Long.MIN_VALUE / 2; // a third tap starts a new pair
				fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null);
			} else {
				rt.lastSneakEdge = now;
			}
		}
		for (ActiveBehavior<?> a : set.forHook(Hook.TICK)) {
			if (!a.dueAt(now)) continue;
			try {
				a.tick(p);
			} catch (Throwable t) {
				failed(a, Hook.TICK, t);
			}
		}
		if (!st.attributes.isEmpty() && (now + p.getId()) % AbsorbCaps.ATTRIBUTE_RECLAMP_INTERVAL_TICKS == 0) {
			AttributeApplier.reclamp(p);
		}
	}

	/**
	 * Dead player: its entries are deactivated once, but the set stays cached for {@link #wipesOnDeath} (COPY_FROM runs
	 * on respawn, after this). Dropped if the player became ineligible meanwhile (a dormant egg never wipes).
	 */
	private static void onDead(ServerPlayer p, PlayerRuntime rt, boolean eligible) {
		EngineState st = state(rt);
		if (!st.deathHandled) {
			st.deathHandled = true;
			ActiveBehavior<?>[] entries = rt.active.all().toArray(new ActiveBehavior<?>[0]);
			deactivateAll(p, entries);
		}
		if (!eligible) rt.active = ActiveSet.EMPTY;
		rt.wasActive = false;
		rt.dirty = false;
	}

	/** Diff rebuild: deactivate entries that went away, apply attributes, activate new entries, sync. */
	private static void rebuild(ServerPlayer p, PlayerRuntime rt, boolean active) {
		rt.dirty = false; // before hooks run: a hook that changes traits marks dirty again
		EngineState st = state(rt);
		st.deathHandled = false;
		ActiveSet old = rt.active;
		PlayerTraits traits = PlayerData.traits(p);
		List<ActiveBehavior<?>> built = active ? build(p, traits) : List.of();
		ActiveSet set = newSet(built);

		List<ActiveBehavior<?>> removed = new ArrayList<>(old.all());
		List<ActiveBehavior<?>> added = new ArrayList<>();
		for (ActiveBehavior<?> a : set.all()) {
			if (!removed.remove(a)) added.add(a);
		}
		deactivateAll(p, removed.toArray(new ActiveBehavior<?>[0]));

		float maxBefore = p.getMaxHealth();
		AttributeApplier.apply(p, traits, active);
		restoreHealth(p, rt, maxBefore);
		rt.active = set;
		rt.wasActive = active;
		for (ActiveBehavior<?> a : added) {
			if (!a.has(Hook.ACTIVATE)) continue;
			try {
				a.onActivate(p);
			} catch (Throwable t) {
				failed(a, Hook.ACTIVATE, t);
			}
		}
		syncMovement(p, rt, set.movement());
		syncAura(p, rt, active ? traits : PlayerTraits.EMPTY);
	}

	private static List<ActiveBehavior<?>> build(ServerPlayer p, PlayerTraits traits) {
		List<ActiveBehavior<?>> list = new ArrayList<>();
		for (TraitEntry e : traits.entries()) {
			Optional<SourceDefinition> def = SourceRegistry.byId(e.source());
			if (def.isEmpty()) continue; // removed by a datapack: stored but inactive
			SourceDefinition d = def.get();
			addSide(list, d, d.trait(), Math.min(e.traitLevel(), d.maxLevel()), false, p.getId());
			addSide(list, d, d.weakness(), Math.min(e.weaknessLevel(), d.maxLevel()), true, p.getId());
		}
		return list;
	}

	private static void addSide(List<ActiveBehavior<?>> list, SourceDefinition d, SourceDefinition.Side side, int level, boolean weakness, int phase) {
		if (level <= 0) return;
		for (BehaviorEntry<?> b : side.behaviors()) {
			try {
				list.add(ActiveBehavior.of(b, level, d.id(), weakness, phase));
			} catch (Throwable t) {
				failed(b.type().id().toString(), "tickInterval", t);
			}
		}
	}

	/** Builds the set; entries whose {@code movement()} throws or returns null are dropped (logged). */
	private static ActiveSet newSet(List<ActiveBehavior<?>> entries) {
		if (entries.isEmpty()) return ActiveSet.EMPTY;
		List<ActiveBehavior<?>> ok = entries;
		for (ActiveBehavior<?> a : entries) {
			if (!a.has(Hook.MOVEMENT)) continue;
			boolean valid;
			try {
				valid = a.movement() != null;
				if (!valid) failed(a, Hook.MOVEMENT, new NullPointerException("movement() returned null"));
			} catch (Throwable t) {
				failed(a, Hook.MOVEMENT, t);
				valid = false;
			}
			if (!valid) {
				if (ok == entries) ok = new ArrayList<>(entries);
				ok.remove(a);
			}
		}
		return new ActiveSet(ok);
	}

	private static void deactivateAll(ServerPlayer p, ActiveBehavior<?>[] entries) {
		for (int i = entries.length - 1; i >= 0; i--) {
			ActiveBehavior<?> a = entries[i];
			if (!a.has(Hook.DEACTIVATE)) continue;
			try {
				a.onDeactivate(p);
			} catch (Throwable t) {
				failed(a, Hook.DEACTIVATE, t);
			}
		}
	}

	/**
	 * The respawned (or End-exited) entity starts from scratch: none of its entries ran onActivate and it carries none of
	 * our transient modifiers, even when Fabric handed it the old entity's runtime object (End exit). Cooldowns, the
	 * damage gate and effect ownership (effects are copied on an End exit) are kept.
	 */
	private static void onRespawn(ServerPlayer p) {
		if (p instanceof FakePlayer) return;
		try {
			PlayerRuntime rt = PlayerData.runtime(p);
			EngineState st = state(rt);
			rt.active = ActiveSet.EMPTY;
			rt.wasActive = false;
			rt.sentMovement = null;
			rt.sentAuraColor = -1;
			rt.sentAuraStrength = -1.0F;
			rt.lastInput = Input.EMPTY;
			st.attributes.clear();
			st.deathHandled = false;
			st.groundedLastTick = false;
			rt.dirty = true;
		} catch (Throwable t) {
			failedEngine("respawn", t);
		}
	}

	/**
	 * Review m3: health above the vanilla max (max_health traits) survives a relog and an End exit. Vanilla clamps the
	 * loaded / copied health to the max WITHOUT our transient modifiers; the raw value was kept in
	 * {@code PlayerRuntime.pendingHealth} (LivingEntityMixin reads the saved {@code Health} on load, {@link #carryHealth}
	 * on an End exit) and is restored, clamped to the new max, right after the first rebuild applied our modifiers, but
	 * only while the player still has the clamped health (it was not hurt in between). Consumed either way. A death
	 * respawn asks for full health ({@link #RESPAWN_FULL_HEALTH}): vanilla respawns at the max without our modifiers.
	 */
	private static void restoreHealth(ServerPlayer p, PlayerRuntime rt, float maxBefore) {
		float pending = rt.pendingHealth;
		rt.pendingHealth = Float.NaN;
		if (!Float.isFinite(pending) || !(pending > p.getHealth()) || p.getHealth() < maxBefore - 1.0E-4F) return;
		float restored = Math.min(pending, p.getMaxHealth());
		if (restored > p.getHealth()) p.setHealth(restored);
	}

	/** {@code pendingHealth} of a death respawn: full health at the max with our modifiers (clamped by restoreHealth). */
	private static final float RESPAWN_FULL_HEALTH = Float.MAX_VALUE;

	/**
	 * COPY_FROM: an End exit ({@code alive}) keeps the old entity's health for {@link #restoreHealth}; a death respawn
	 * starts at full health once the max-health modifiers are back.
	 */
	private static void carryHealth(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (newPlayer instanceof FakePlayer) return;
		try {
			float health = alive ? oldPlayer.getHealth() : RESPAWN_FULL_HEALTH;
			// Fabric may hand the new entity the old runtime object afterwards (AFTER_RESPAWN): set both
			PlayerData.runtime(oldPlayer).pendingHealth = health;
			PlayerData.runtime(newPlayer).pendingHealth = health;
		} catch (Throwable t) {
			failedEngine("copyHealth", t);
		}
	}

	/** LivingEntityMixin: the raw {@code Health} a loading player was saved with (before vanilla clamps it). */
	public static void onHealthLoaded(ServerPlayer player, float savedHealth) {
		if (player instanceof FakePlayer || !Float.isFinite(savedHealth)) return;
		try {
			PlayerData.runtime(player).pendingHealth = savedHealth;
		} catch (Throwable t) {
			failedEngine("loadHealth", t);
		}
	}

	/** Logout: undo every entry's side effects now (the player is saved right after). */
	private static void deactivate(@Nullable ServerPlayer p) {
		if (p == null || p instanceof FakePlayer) return;
		try {
			PlayerRuntime rt = PlayerData.runtime(p);
			ActiveSet old = rt.active;
			rt.active = ActiveSet.EMPTY;
			rt.wasActive = false;
			rt.dirty = true;
			if (!state(rt).deathHandled) deactivateAll(p, old.all().toArray(new ActiveBehavior<?>[0]));
			((MovementFlagsHolder) p).absorbaholic$setMovement(MovementState.NONE);
		} catch (Throwable t) {
			failedEngine("disconnect", t);
		}
	}

	/**
	 * Stores the movement state on the server entity and sends it to a modded client. A vanilla client can't simulate
	 * fluid walking, so its server entity doesn't get those flags either: otherwise the server's movement check would
	 * collide with the fluid surface and keep teleporting the sinking client back on top.
	 */
	private static void syncMovement(ServerPlayer p, PlayerRuntime rt, MovementState state) {
		boolean modded = ServerPlayNetworking.canSend(p, MovementPayload.TYPE);
		((MovementFlagsHolder) p).absorbaholic$setMovement(modded ? state : state.without(MovementFlags.WALK_ON_WATER | MovementFlags.WALK_ON_LAVA));
		if (state.equals(rt.sentMovement)) return;
		rt.sentMovement = state;
		if (modded) ServerPlayNetworking.send(p, new MovementPayload(state));
	}

	private static void syncAura(ServerPlayer p, PlayerRuntime rt, PlayerTraits traits) {
		int n = traits.size();
		int[] colors = new int[n];
		double[] weights = new double[n];
		int total = 0;
		for (int i = 0; i < n; i++) {
			TraitEntry e = traits.entries().get(i);
			Optional<SourceDefinition> def = SourceRegistry.byId(e.source());
			if (def.isEmpty() || e.traitLevel() <= 0) continue;
			colors[i] = def.get().color();
			weights[i] = e.traitLevel();
			total += e.traitLevel();
		}
		float strength = Math.min(1.0F, total / (float) AbsorbCaps.AURA_FULL_STRENGTH_LEVELS);
		int color = total > 0 ? ColorMix.mix(colors, weights) : ColorMix.DEFAULT;
		if (color == rt.sentAuraColor && strength == rt.sentAuraStrength) return;
		rt.sentAuraColor = color;
		rt.sentAuraStrength = strength;
		AuraPayload payload = new AuraPayload(p.getId(), color, strength);
		if (ServerPlayNetworking.canSend(p, AuraPayload.TYPE)) ServerPlayNetworking.send(p, payload);
		for (ServerPlayer watcher : PlayerLookup.tracking(p)) {
			if (watcher != p && ServerPlayNetworking.canSend(watcher, AuraPayload.TYPE)) ServerPlayNetworking.send(watcher, payload);
		}
	}

	// ---- damage -----------------------------------------------------------------------------------------------

	/** True if an active entry makes the player immune; never for /kill, the void, generic or weakness damage. */
	private static boolean isImmune(ServerPlayer p, DamageSource source) {
		ActiveBehavior<?>[] entries = active(p).forHook(Hook.IMMUNITY);
		if (entries.length == 0 || neverImmune(source) || !isActive(p)) return false;
		for (ActiveBehavior<?> a : entries) {
			try {
				if (a.isImmuneTo(p, source)) return true;
			} catch (Throwable t) {
				failed(a, Hook.IMMUNITY, t);
			}
		}
		return false;
	}

	private static boolean neverImmune(DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.is(WEAKNESS)) return true;
		for (ResourceKey<DamageType> key : NEVER_IMMUNE) {
			if (source.is(key)) return true;
		}
		return false;
	}

	/**
	 * LivingEntityMixin (victim is the player): trait floor, weakness extra through the damage gate. Vanilla damage a
	 * weakness caused ({@link #weaknessCaused}: burning from a weakness ignition, starvation while a weakness drains
	 * hunger) goes through the gate as a whole, like direct weakness damage.
	 */
	public static float modifyIncomingDamage(ServerPlayer player, DamageSource source, float amount) {
		if (!(amount > 0.0F)) return amount;
		PlayerRuntime rt = PlayerData.runtime(player);
		ActiveBehavior<?>[] entries = rt.active.forHook(Hook.INCOMING_DAMAGE);
		boolean caused = weaknessCaused(player, rt, source);
		if (entries.length == 0 && !caused || !isActive(player)) return amount;
		// never touched: our own weakness damage (gated already), /kill and the void
		if (source.is(WEAKNESS) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return amount;
		// damage vanilla is about to drop anyway must not spend the gate budget
		if (isImmune(player, source) || source.is(DamageTypeTags.IS_FIRE) && player.hasEffect(MobEffects.FIRE_RESISTANCE)) return amount;
		float traits = 1.0F;
		float weaknesses = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				float f = a.incomingDamageFactor(player, source, amount);
				if (a.weakness()) weaknesses *= f;
				else traits *= f;
			} catch (Throwable t) {
				failed(a, Hook.INCOMING_DAMAGE, t);
			}
		}
		FactorMath.Incoming in = FactorMath.incoming(amount, traits, weaknesses);
		if (caused) return rt.damageGate.allowDirect(now(player), in.base() + in.extra(), player.getHealth(), player.getMaxHealth());
		float extra = in.extra() > 0.0F
				? rt.damageGate.allowExtra(now(player), in.extra(), in.base(), player.getHealth(), player.getMaxHealth())
				: 0.0F;
		return in.base() + extra;
	}

	/**
	 * Review m2: vanilla damage that a weakness caused and that is therefore charged to the weakness damage gate:
	 * <ul>
	 * <li>{@code minecraft:on_fire} while a weakness ignition is burning ({@link #noteWeaknessIgnition}, e.g. sunburn);</li>
	 * <li>{@code minecraft:starve} while an active weakness drains hunger ({@code hunger_drain}) or cuts food
	 *     ({@code food_modifier}), i.e. any weakness entry with the exhaustion or food hook.</li>
	 * </ul>
	 */
	private static boolean weaknessCaused(ServerPlayer player, PlayerRuntime rt, DamageSource source) {
		if (source.is(DamageTypes.ON_FIRE)) return now(player) <= rt.weaknessFireUntil;
		if (!source.is(DamageTypes.STARVE)) return false;
		for (ActiveBehavior<?> a : rt.active.forHook(Hook.EXHAUSTION)) {
			if (a.weakness()) return true;
		}
		for (ActiveBehavior<?> a : rt.active.forHook(Hook.MODIFY_FOOD)) {
			if (a.weakness()) return true;
		}
		return false;
	}

	/**
	 * A weakness behavior set the player on fire for {@code ticks}: the burning until then counts as weakness damage
	 * (see {@link #weaknessCaused}).
	 */
	public static void noteWeaknessIgnition(ServerPlayer player, int ticks) {
		if (ticks <= 0) return;
		PlayerRuntime rt = PlayerData.runtime(player);
		rt.weaknessFireUntil = Math.max(rt.weaknessFireUntil, now(player) + ticks);
	}

	/** LivingEntityMixin: {@code LivingEntity#knockback} strength the player is about to take. */
	public static double modifyKnockback(ServerPlayer player, @Nullable DamageSource source, double strength) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.KNOCKBACK);
		if (entries.length == 0 || !(strength > 0.0) || !isActive(player)) return strength;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.knockbackFactor(player, source, strength);
			} catch (Throwable t) {
				failed(a, Hook.KNOCKBACK, t);
			}
		}
		return strength * FactorMath.knockback(product);
	}

	/** LivingEntityMixin (attacker is the player). */
	public static float modifyOutgoingDamage(ServerPlayer attacker, LivingEntity target, DamageSource source, float amount) {
		ActiveBehavior<?>[] entries = active(attacker).forHook(Hook.OUTGOING_DAMAGE);
		if (entries.length == 0 || !(amount > 0.0F) || attacker == target || !isActive(attacker)) return amount;
		// review m1: weakness damage credited to a player was gated already; /kill and the void are never scaled
		if (source.is(WEAKNESS) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return amount;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.outgoingDamageFactor(attacker, target, source, amount);
			} catch (Throwable t) {
				failed(a, Hook.OUTGOING_DAMAGE, t);
			}
		}
		return amount * FactorMath.outgoing(product);
	}

	private static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (source.getEntity() instanceof ServerPlayer attacker && attacker != entity && isActive(attacker)) {
			for (ActiveBehavior<?> a : active(attacker).forHook(Hook.DEALT_DAMAGE)) {
				try {
					a.onDealtDamage(attacker, entity, source, damageTaken);
				} catch (Throwable t) {
					failed(a, Hook.DEALT_DAMAGE, t);
				}
			}
		}
		if (entity instanceof ServerPlayer p && isActive(p)) {
			for (ActiveBehavior<?> a : active(p).forHook(Hook.ATTACKED)) {
				try {
					a.onAttacked(p, source, damageTaken);
				} catch (Throwable t) {
					failed(a, Hook.ATTACKED, t);
				}
			}
		}
	}

	/** The player died: its entries were active up to now (health is already 0, so {@link #isActive} is false). */
	private static void afterDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer p) || p instanceof FakePlayer) return;
		PlayerRuntime rt = PlayerData.runtime(p);
		if (!rt.wasActive || !eligible(p, AbsorbWorldSettings.isEnabled(p.level().getServer()))) return;
		for (ActiveBehavior<?> a : rt.active.forHook(Hook.DEATH)) {
			try {
				a.onDeath(p, source);
			} catch (Throwable t) {
				failed(a, Hook.DEATH, t);
			}
		}
	}

	// ---- called by mixins / events ----------------------------------------------------------------------------

	/** {@code natural}: inside FoodData#tick (FoodDataMixin thread-local). */
	public static float modifyHeal(ServerPlayer player, float amount, boolean natural) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.HEAL);
		if (entries.length == 0 || !(amount > 0.0F) || !isActive(player)) return amount;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.healFactor(player, amount, natural);
			} catch (Throwable t) {
				failed(a, Hook.HEAL, t);
			}
		}
		return amount * FactorMath.heal(product);
	}

	/** FoodDataMixin: FoodData#tick(ServerPlayer) HEAD. */
	public static void beginNaturalRegen() {
		naturalRegenDepth++;
	}

	/** FoodDataMixin: FoodData#tick(ServerPlayer) RETURN. */
	public static void endNaturalRegen() {
		if (naturalRegenDepth > 0) naturalRegenDepth--;
	}

	/** True while the server is inside FoodData#tick, i.e. a heal now is natural regeneration. */
	public static boolean isNaturalRegen() {
		return naturalRegenDepth > 0;
	}

	/** FoodPropertiesMixin: the food value the player is about to receive from {@code stack}. */
	public static FoodProperties modifyFood(ServerPlayer player, ItemStack stack, FoodProperties food) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.MODIFY_FOOD);
		if (entries.length == 0 || !isActive(player)) return food;
		FoodProperties result = food;
		for (ActiveBehavior<?> a : entries) {
			try {
				FoodProperties next = a.modifyFood(player, stack, result);
				if (next != null) result = next;
			} catch (Throwable t) {
				failed(a, Hook.MODIFY_FOOD, t);
			}
		}
		if (result.nutrition() < 0 || !(result.saturation() >= 0.0F) || Float.isInfinite(result.saturation())) {
			float saturation = Float.isFinite(result.saturation()) ? Math.max(0.0F, result.saturation()) : 0.0F;
			result = new FoodProperties(Math.max(0, result.nutrition()), saturation, result.canAlwaysEat());
		}
		return result;
	}

	/** ProjectileMixin: {@code projectile} hit the player (before damage; also zero-damage projectiles). */
	public static void onHitByProjectile(ServerPlayer player, Projectile projectile) {
		if (!isActive(player)) return;
		for (ActiveBehavior<?> a : active(player).forHook(Hook.HIT_BY_PROJECTILE)) {
			try {
				a.onHitByProjectile(player, projectile);
			} catch (Throwable t) {
				failed(a, Hook.HIT_BY_PROJECTILE, t);
			}
		}
	}

	/**
	 * ServerGamePacketListenerImplMixin: the player swung (26.2 swing packet / 26.3 punch packet). A sneak_swing trigger
	 * when sneaking with no block within reach (so never while mining), at most once per {@link AbsorbCaps#SNEAK_SWING_MIN_INTERVAL_TICKS}
	 * ticks; the target is the entity under the crosshair within reach, if any.
	 */
	public static void onSwing(ServerPlayer player) {
		try {
			if (!player.isShiftKeyDown() || !isActive(player) || !active(player).any(Hook.SNEAK_SWING)) return;
			EngineState st = state(PlayerData.runtime(player));
			long now = now(player);
			if (now - st.lastSwingTick < AbsorbCaps.SNEAK_SWING_MIN_INTERVAL_TICKS) return;
			if (player.pick(player.blockInteractionRange(), 1.0F, false).getType() == HitResult.Type.BLOCK) return;
			st.lastSwingTick = now;
			fireTrigger(player, Hook.SNEAK_SWING, pickEntity(player));
		} catch (Throwable t) {
			failedEngine("swing", t);
		}
	}

	private static @Nullable Entity pickEntity(ServerPlayer player) {
		double range = player.entityInteractionRange();
		Vec3 eye = player.getEyePosition();
		Vec3 reach = player.getViewVector(1.0F).scale(range);
		AABB box = player.getBoundingBox().expandTowards(reach).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, eye.add(reach), box,
				e -> !e.isSpectator() && e.isPickable(), range * range);
		return hit == null ? null : hit.getEntity();
	}

	/**
	 * Offers an ability trigger to the active entries overriding {@code hook}, in acquisition order; stops at the first
	 * that fires and charges the exhaustion cost. Returns true if one fired.
	 */
	public static boolean fireTrigger(ServerPlayer player, Hook hook, @Nullable Entity target) {
		if (hook != Hook.SNEAK_JUMP && hook != Hook.AIR_JUMP && hook != Hook.SNEAK_DOUBLE_TAP && hook != Hook.SNEAK_SWING) {
			failedEngine("fireTrigger", new IllegalArgumentException(hook + " is not an ability trigger"));
			return false;
		}
		if (!isActive(player)) return false;
		for (ActiveBehavior<?> a : active(player).forHook(hook)) {
			boolean fired;
			try {
				fired = switch (hook) {
					case SNEAK_JUMP -> a.onSneakJump(player);
					case AIR_JUMP -> a.onAirJump(player);
					case SNEAK_DOUBLE_TAP -> a.onSneakDoubleTap(player);
					default -> a.onSneakSwing(player, target);
				};
			} catch (Throwable t) {
				failed(a, hook, t);
				continue;
			}
			if (fired) {
				player.causeFoodExhaustion(hook == Hook.AIR_JUMP ? AbsorbCaps.AIR_JUMP_EXHAUSTION : AbsorbCaps.ABILITY_EXHAUSTION);
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds an effect applied by one of our behaviors ({@code status_effect}) and records it as owned by
	 * {@code source}, so effect_modifier never touches it and status_effect only ever removes its own.
	 */
	public static void addOwnedEffect(ServerPlayer player, MobEffectInstance effect, Identifier source) {
		boolean outer = addingOwnedEffect;
		addingOwnedEffect = true;
		try {
			player.addEffect(effect);
		} finally {
			addingOwnedEffect = outer;
		}
		Identifier id = effectId(effect.getEffect());
		if (id == null || !player.hasEffect(effect.getEffect())) return;
		PlayerRuntime rt = PlayerData.runtime(player);
		rt.ownedEffects.put(id, source);
		state(rt).ownedSignatures.put(id, EffectSignature.of(effect));
	}

	/** True if {@code effect}'s current instance on the player was applied by our behaviors. */
	public static boolean isOwnedEffect(ServerPlayer player, MobEffectInstance effect) {
		if (effect == null) return false;
		Identifier id = effectId(effect.getEffect());
		if (id == null) return false;
		PlayerRuntime rt = PlayerData.runtime(player);
		return rt.ownedEffects.containsKey(id) && EffectSignature.of(effect).equals(state(rt).ownedSignatures.get(id));
	}

	private static boolean allowEffect(ServerPlayer player, MobEffectInstance effect) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.ALLOW_EFFECT);
		if (entries.length == 0 || addingOwnedEffect || !isActive(player)) return true;
		for (ActiveBehavior<?> a : entries) {
			try {
				if (!a.allowEffect(player, effect)) return false;
			} catch (Throwable t) {
				failed(a, Hook.ALLOW_EFFECT, t);
			}
		}
		return true;
	}

	public static float modifyExhaustion(ServerPlayer player, float amount) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.EXHAUSTION);
		if (entries.length == 0 || !(amount > 0.0F) || !isActive(player)) return amount;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.exhaustionFactor(player, amount);
			} catch (Throwable t) {
				failed(a, Hook.EXHAUSTION, t);
			}
		}
		return amount * FactorMath.exhaustion(product);
	}

	/** Chains modifyEffect over the active entries; our amplification never exceeds EFFECT_AMPLIFIER_MAX. */
	public static MobEffectInstance modifyEffect(ServerPlayer player, MobEffectInstance effect) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.MODIFY_EFFECT);
		if (entries.length == 0 || addingOwnedEffect || effect == null || !isActive(player)) return effect;
		MobEffectInstance result = effect;
		for (ActiveBehavior<?> a : entries) {
			try {
				MobEffectInstance next = a.modifyEffect(player, result);
				if (next != null) result = next;
			} catch (Throwable t) {
				failed(a, Hook.MODIFY_EFFECT, t);
			}
		}
		int cap = Math.max(effect.getAmplifier(), AbsorbCaps.EFFECT_AMPLIFIER_MAX);
		if (result.getAmplifier() > cap) {
			result = new MobEffectInstance(result.getEffect(), result.getDuration(), cap, result.isAmbient(), result.isVisible(), result.showIcon());
		}
		return result;
	}

	public static boolean preventsTargeting(Mob mob, ServerPlayer target) {
		ActiveBehavior<?>[] entries = active(target).forHook(Hook.PREVENT_TARGETING);
		if (entries.length == 0 || mob.getLastHurtByMob() == target || !isActive(target)) return false;
		for (ActiveBehavior<?> a : entries) {
			try {
				if (a.preventsTargeting(target, mob)) return true;
			} catch (Throwable t) {
				failed(a, Hook.PREVENT_TARGETING, t);
			}
		}
		return false;
	}

	public static double visibilityFactor(ServerPlayer player, @Nullable Entity looker) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.VISIBILITY);
		if (entries.length == 0 || !isActive(player)) return 1.0;
		double product = 1.0;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.visibilityFactor(player, looker);
			} catch (Throwable t) {
				failed(a, Hook.VISIBILITY, t);
			}
		}
		return FactorMath.visibility(product);
	}

	public static void onLand(ServerPlayer player, double fallDistance) {
		if (!isActive(player)) return;
		for (ActiveBehavior<?> a : active(player).forHook(Hook.LAND)) {
			try {
				a.onLand(player, fallDistance);
			} catch (Throwable t) {
				failed(a, Hook.LAND, t);
			}
		}
	}

	/** ExperienceOrbMixin: XP from an orb only (stochastic rounding per behaviors.md). */
	public static int modifyExperience(ServerPlayer player, int amount) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.EXPERIENCE);
		if (entries.length == 0 || amount <= 0 || !isActive(player)) return amount;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.experienceFactor(player, amount);
			} catch (Throwable t) {
				failed(a, Hook.EXPERIENCE, t);
			}
		}
		return InputEdges.roundStochastic(amount * (double) FactorMath.experience(product), player.getRandom());
	}

	/** ItemStack durability damage caused while the player holds / wears it. */
	public static int modifyDurabilityDamage(ServerPlayer player, ItemStack stack, int amount) {
		ActiveBehavior<?>[] entries = active(player).forHook(Hook.DURABILITY);
		if (entries.length == 0 || amount <= 0 || !isActive(player)) return amount;
		float product = 1.0F;
		for (ActiveBehavior<?> a : entries) {
			try {
				product *= a.durabilityFactor(player, stack, amount);
			} catch (Throwable t) {
				failed(a, Hook.DURABILITY, t);
			}
		}
		return InputEdges.roundStochastic(amount * (double) FactorMath.durability(product), player.getRandom());
	}

	/** After the player finished using a consumable item ({@code stack} = copy before consumption). */
	public static void onItemConsumed(ServerPlayer player, ItemStack stack) {
		if (!isActive(player)) return;
		for (ActiveBehavior<?> a : active(player).forHook(Hook.ITEM_CONSUMED)) {
			try {
				a.onItemConsumed(player, stack);
			} catch (Throwable t) {
				failed(a, Hook.ITEM_CONSUMED, t);
			}
		}
	}

	/** Dragon egg: true if any active entry wipes all traits on death. Uses the cached set (call before discard). */
	public static boolean wipesOnDeath(ServerPlayer player) {
		for (ActiveBehavior<?> a : active(player).forHook(Hook.WIPES_ON_DEATH)) {
			if (a.wipesOnDeath()) return true;
		}
		return false;
	}

	// ---- helpers ----------------------------------------------------------------------------------------------

	private static long now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}

	private static @Nullable Identifier effectId(Holder<MobEffect> effect) {
		return effect.unwrapKey().map(ResourceKey::identifier).orElse(null);
	}

	/** The engine's private per-player scratch state, kept in the transient runtime. */
	static EngineState state(PlayerRuntime rt) {
		return (EngineState) rt.behaviorState.computeIfAbsent(STATE_KEY, k -> new EngineState());
	}

	static EngineState state(ServerPlayer player) {
		return state(PlayerData.runtime(player));
	}

	private static void failed(ActiveBehavior<?> a, Hook hook, Throwable t) {
		failed(a.type().id().toString(), hook.methodName, t);
	}

	private static void failed(String type, String what, Throwable t) {
		if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
		if (FAILED.add(type)) {
			Absorbaholic.LOGGER.error("Absorbaholic behavior {} failed in {} (further failures of this type are logged at DEBUG)", type, what, t);
		} else {
			Absorbaholic.LOGGER.debug("Absorbaholic behavior {} failed in {}", type, what, t);
		}
	}

	private static void failedEngine(String what, Throwable t) {
		failed("engine/" + what, what, t);
	}

	/** Per-player engine scratch state (transient, in {@code PlayerRuntime.behaviorState}). */
	static final class EngineState {
		/** Attributes that currently carry our modifiers. */
		final Set<Holder<Attribute>> attributes = new HashSet<>();
		/** Owned effect id → the exact instance flags we applied (a potion of the same effect is not ours). */
		final Map<Identifier, EffectSignature> ownedSignatures = new HashMap<>();
		long lastSwingTick = Long.MIN_VALUE / 2;
		boolean groundedLastTick;
		/** The dead player's entries were deactivated. */
		boolean deathHandled;
	}

	/** Distinguishes our effect instance from someone else's of the same effect. */
	record EffectSignature(int amplifier, boolean ambient, boolean visible, boolean showIcon) {
		static EffectSignature of(MobEffectInstance e) {
			return new EffectSignature(e.getAmplifier(), e.isAmbient(), e.isVisible(), e.showIcon());
		}
	}

	/** Pure input-edge classification and rounding (unit-tested). */
	static final class InputEdges {
		private InputEdges() {}

		/**
		 * Classifies a jump rising edge. The client sends its input before the move that leaves the ground, so a jump
		 * counts as grounded when the player stood on the ground at the end of this or the previous tick.
		 * Returns JUMP, SNEAK_JUMP, AIR_JUMP or null (e.g. swimming or climbing).
		 */
		static @Nullable Hook jump(boolean grounded, boolean groundedBefore, boolean airborne, boolean shift) {
			if (grounded || groundedBefore) return shift ? Hook.SNEAK_JUMP : Hook.JUMP;
			return airborne ? Hook.AIR_JUMP : null;
		}

		/** A sneak rising edge at {@code now} completes a double tap when the previous edge is recent enough. */
		static boolean doubleTap(long previousEdge, long now) {
			long gap = now - previousEdge;
			return gap > 0 && gap <= AbsorbCaps.SNEAK_DOUBLE_TAP_TICKS;
		}

		/** floor(v) + 1 with probability frac(v); never negative. */
		static int roundStochastic(double value, RandomSource random) {
			return roundStochastic(value, random.nextDouble());
		}

		static int roundStochastic(double value, double roll) {
			if (!(value > 0.0)) return 0;
			if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
			double floor = Math.floor(value);
			return (int) floor + (roll < value - floor ? 1 : 0);
		}
	}
}
