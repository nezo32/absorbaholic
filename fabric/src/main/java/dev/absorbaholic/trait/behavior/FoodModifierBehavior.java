package dev.absorbaholic.trait.behavior;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * {@code absorbaholic:food_modifier}: changes what eating a matching food gives. {@code items} (ids / #item tags;
 * omitted = every food) minus {@code exclude}; new nutrition = {@code max(0, round(n × nutrition))}, new saturation
 * modifier = {@code modifier × saturation} (so the saturation gained scales with both factors); vanilla then caps food
 * at 20 and saturation at the food level. Several matching entries multiply. The optional {@code effect} (+
 * {@code amplifier}, {@code duration}) is applied after eating a matching food. Cake is eaten from the block, not as
 * an item, and is never changed.
 *
 * <pre>{"type": "absorbaholic:food_modifier", "items": ["#minecraft:parrot_poisonous_food"], "nutrition": [1.0],
 *  "saturation": [1.0], "effect": "minecraft:poison", "amplifier": [0, 0, 1], "duration": [100, 160, 200]}</pre>
 */
public final class FoodModifierBehavior implements Behavior<FoodModifierBehavior.Params> {
	public record Params(Optional<ItemFilter> items, ItemFilter exclude, LevelValue nutrition, LevelValue saturation, Optional<Holder<MobEffect>> effect,
			Optional<LevelValue> amplifier, Optional<LevelValue> duration, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				ItemFilter.CODEC.optionalFieldOf("items").forGetter(Params::items),
				ItemFilter.CODEC.optionalFieldOf("exclude", ItemFilter.NONE).forGetter(Params::exclude),
				LevelValue.CODEC.optionalFieldOf("nutrition", LevelValue.constant(1.0)).forGetter(Params::nutrition),
				LevelValue.CODEC.optionalFieldOf("saturation", LevelValue.constant(1.0)).forGetter(Params::saturation),
				MobEffect.CODEC.optionalFieldOf("effect").forGetter(Params::effect),
				LevelValue.CODEC.optionalFieldOf("amplifier").forGetter(Params::amplifier),
				LevelValue.CODEC.optionalFieldOf("duration").forGetter(Params::duration),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (effect.isPresent() != amplifier.isPresent() || effect.isPresent() != duration.isPresent()) {
				return DataResult.error(() -> "\"effect\", \"amplifier\" and \"duration\" go together");
			}
			return DataResult.success(this);
		}

		boolean matches(ItemStack stack) {
			return items.map(f -> f.test(stack)).orElse(true) && !exclude.test(stack);
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("food_modifier", Params.CODEC, new FoodModifierBehavior());

	@Override
	public FoodProperties modifyFood(ActiveBehavior<Params> self, ServerPlayer player, ItemStack stack, FoodProperties food) {
		Params p = self.params();
		if (!p.matches(stack) || !EntryStates.condition(player, self, p.condition())) return food;
		return scale(food, p.nutrition().at(self.level()), p.saturation().at(self.level()));
	}

	@Override
	public void onItemConsumed(ActiveBehavior<Params> self, ServerPlayer player, ItemStack stack) {
		Params p = self.params();
		if (p.effect().isEmpty() || !stack.has(DataComponents.FOOD) || !p.matches(stack)) return;
		int amplifier = p.amplifier().orElseThrow().atInt(self.level());
		int duration = p.duration().orElseThrow().atInt(self.level());
		if (amplifier < 0 || duration <= 0 || !EntryStates.condition(player, self, p.condition())) return;
		player.addEffect(new MobEffectInstance(p.effect().get(), duration, amplifier));
	}

	/** Nutrition × {@code nutrition} (rounded, &gt;= 0); the saturation modifier × {@code saturation}. */
	static FoodProperties scale(FoodProperties food, double nutrition, double saturation) {
		int oldNutrition = food.nutrition();
		int newNutrition = (int) Math.max(0L, Math.round(oldNutrition * nutrition));
		double perPoint = oldNutrition > 0 ? food.saturation() / (double) oldNutrition : 0.0;
		double newSaturation = oldNutrition > 0 ? perPoint * newNutrition * saturation : food.saturation() * saturation;
		if (!Double.isFinite(newSaturation) || newSaturation < 0.0) newSaturation = 0.0;
		return new FoodProperties(newNutrition, (float) newSaturation, food.canAlwaysEat());
	}
}
