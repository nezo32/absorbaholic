package dev.absorbaholic.world;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-world Absorbaholic settings, saved as {@code <world>/data/absorbaholic/settings.dat} in the server-wide data
 * storage (MinecraftServer#getDataStorage). Absent file = defaults with the mode OFF (dedicated servers, worlds made
 * without our Create World button). Every setter marks dirty and fires {@link WorldSettingsEvents}.
 * Server thread only.
 */
public final class AbsorbWorldSettings extends SavedData {
	public static final Codec<AbsorbWorldSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("enabled", false).forGetter(AbsorbWorldSettings::enabled),
			Codec.BOOL.optionalFieldOf("keepOnDeath", true).forGetter(AbsorbWorldSettings::keepOnDeath),
			Codec.BOOL.optionalFieldOf("hints", true).forGetter(AbsorbWorldSettings::hints),
			Codec.intRange(AbsorbCaps.MIN_MAX_TRAITS, AbsorbCaps.MAX_MAX_TRAITS)
					.optionalFieldOf("maxTraits", AbsorbCaps.DEFAULT_MAX_TRAITS).forGetter(AbsorbWorldSettings::maxTraits),
			Identifier.CODEC.listOf().optionalFieldOf("discovered", List.of()).forGetter(s -> List.copyOf(s.discovered))
	).apply(i, AbsorbWorldSettings::new));

	/** null DataFixTypes: no vanilla fixer applies; Fabric's SavedDataStorageMixin skips datafixing for null. */
	public static final SavedDataType<AbsorbWorldSettings> TYPE = new SavedDataType<>(
			Absorbaholic.id("settings"), AbsorbWorldSettings::new, CODEC, null);

	private boolean enabled;
	private boolean keepOnDeath;
	private boolean hints;
	private int maxTraits;
	private final Set<Identifier> discovered;

	public AbsorbWorldSettings() {
		this(false, true, true, AbsorbCaps.DEFAULT_MAX_TRAITS, List.of());
	}

	private AbsorbWorldSettings(boolean enabled, boolean keepOnDeath, boolean hints, int maxTraits, List<Identifier> discovered) {
		this.enabled = enabled;
		this.keepOnDeath = keepOnDeath;
		this.hints = hints;
		this.maxTraits = maxTraits;
		this.discovered = new LinkedHashSet<>(discovered);
	}

	public static AbsorbWorldSettings get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean enabled() {
		return enabled;
	}

	public boolean keepOnDeath() {
		return keepOnDeath;
	}

	public boolean hints() {
		return hints;
	}

	public int maxTraits() {
		return maxTraits;
	}

	/** Sources absorbed by anyone in this world (read-only view). */
	public Set<Identifier> discovered() {
		return Collections.unmodifiableSet(discovered);
	}

	public boolean isDiscovered(Identifier source) {
		return discovered.contains(source);
	}

	// ---- static convenience: read ----

	public static boolean isEnabled(MinecraftServer server) {
		return get(server).enabled();
	}

	// ---- static convenience: write (mark dirty even when unchanged, so the file exists; fire events on change) ----

	public static void setEnabled(MinecraftServer server, boolean value) {
		AbsorbWorldSettings s = get(server);
		boolean changed = s.enabled != value;
		s.enabled = value;
		s.setDirty();
		if (changed) WorldSettingsEvents.CHANGED.invoker().onChanged(server, s);
	}

	public static void setKeepOnDeath(MinecraftServer server, boolean value) {
		AbsorbWorldSettings s = get(server);
		boolean changed = s.keepOnDeath != value;
		s.keepOnDeath = value;
		s.setDirty();
		if (changed) WorldSettingsEvents.CHANGED.invoker().onChanged(server, s);
	}

	public static void setHints(MinecraftServer server, boolean value) {
		AbsorbWorldSettings s = get(server);
		boolean changed = s.hints != value;
		s.hints = value;
		s.setDirty();
		if (changed) WorldSettingsEvents.CHANGED.invoker().onChanged(server, s);
	}

	/** Clamped to [MIN_MAX_TRAITS, MAX_MAX_TRAITS]; returns the stored value. Existing players are not trimmed. */
	public static int setMaxTraits(MinecraftServer server, int value) {
		AbsorbWorldSettings s = get(server);
		int clamped = Math.clamp(value, AbsorbCaps.MIN_MAX_TRAITS, AbsorbCaps.MAX_MAX_TRAITS);
		boolean changed = s.maxTraits != clamped;
		s.maxTraits = clamped;
		s.setDirty();
		if (changed) WorldSettingsEvents.CHANGED.invoker().onChanged(server, s);
		return clamped;
	}

	/** Marks {@code source} discovered; true (and fires DISCOVERED) if it was new. */
	public static boolean discover(MinecraftServer server, Identifier source) {
		AbsorbWorldSettings s = get(server);
		if (!s.discovered.add(source)) return false;
		s.setDirty();
		WorldSettingsEvents.DISCOVERED.invoker().onDiscovered(server, source);
		return true;
	}
}
