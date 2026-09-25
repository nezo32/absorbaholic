package dev.absorbaholic.trait;

import dev.absorbaholic.core.LevelValue;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** A resolved attribute part of a trait or weakness: {@code amount.at(level)} with {@code operation} on {@code attribute}. */
public record AttributeEntry(Holder<Attribute> attribute, AttributeModifier.Operation operation, LevelValue amount) {}
