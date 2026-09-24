package dev.absorbaholic.trait;

/** One configured behavior of a source's trait or weakness: its type and the params decoded from the source JSON. */
public record BehaviorEntry<P>(BehaviorType<P> type, P params) {}
