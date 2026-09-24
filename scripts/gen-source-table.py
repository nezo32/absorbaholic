#!/usr/bin/env python3
"""Generate the Absorbaholic source table and safety-caps table for the docs.

Reads every source JSON under fabric/src/main/resources/data/*/absorbaholic/source/, the en_us lang file and
core/AbsorbCaps.java, and rewrites the blocks between these markers:

  <!-- source-table:start --> ... <!-- source-table:end -->   README.md (with source ids) and
                                                              docs/branding/branding.md (CurseForge, no ids)
  <!-- caps-table:start --> ... <!-- caps-table:end -->       same two files (optional in each)

Usage:
  python3 scripts/gen-source-table.py            rewrite the files in place
  python3 scripts/gen-source-table.py --check    exit 1 and print a diff if a file is out of date

Stdlib only. New sources (datapack style, other namespaces) are picked up automatically. A behavior type this
script doesn't know falls back to the trait/weakness description from the lang file.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from pathlib import Path

DATA_GLOB = "fabric/src/main/resources/data/*/absorbaholic/source"
LANG = "fabric/src/main/resources/assets/absorbaholic/lang/en_us.json"
CAPS = "fabric/src/main/java/dev/absorbaholic/core/AbsorbCaps.java"
TARGETS = (("README.md", True), ("docs/branding/branding.md", False))  # (file, show source ids)

MINUS = "\u2212"
TIERS = ["common", "uncommon", "rare", "epic", "legendary"]
ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]

# ---- names -------------------------------------------------------------------------------------------------

# Vanilla names that plain title case gets wrong (checked against the 26.3 en_us for every shipped target).
NAME_OVERRIDES = {
    "tnt": "TNT",
    "dolphins_grace": "Dolphin's Grace",
    "hero_of_the_village": "Hero of the Village",
    "breath_of_the_nautilus": "Breath of the Nautilus",
    "instant_health": "Instant Health",
}
SMALL_WORDS = {"of", "the", "and", "a"}

ATTRIBUTE_NAMES = {
    "max_health": "Max health",
    "block_interaction_range": "Block reach",
    "entity_interaction_range": "Entity reach",
    "block_break_speed": "Mining speed",
    "submerged_mining_speed": "Underwater mining speed",
    "fall_damage_multiplier": "Fall damage",
    "friction_modifier": "Friction",
    "scale": "Size",
}
# add_value amounts shown as a percentage (the attribute is a 0..1 fraction)
FRACTION_ATTRIBUTES = {
    "knockback_resistance", "explosion_knockback_resistance", "movement_efficiency", "water_movement_efficiency",
}
ATTRIBUTE_UNITS = {
    "max_health": " HP",
    "safe_fall_distance": " blocks",
    "block_interaction_range": " blocks",
    "entity_interaction_range": " blocks",
    "step_height": " blocks",
}
DAMAGE_NAMES = {
    "is_explosion": "Explosion", "is_fire": "Fire", "is_drowning": "Drowning", "is_freezing": "Freezing",
    "is_fall": "Fall", "is_projectile": "Projectile", "hot_floor": "Magma floor", "sweet_berry_bush": "berry bush",
    "lightning_bolt": "Lightning", "ender_pearl": "Ender pearl", "indirect_magic": None,  # folded into "magic"
}
TAG_NAMES = {
    "zombies": "zombies", "arthropod": "arthropods", "skeletons": "skeletons", "undead": "undead",
    "meat": "meat", "parrot_poisonous_food": "cookies", "fears_golem": "zombies, skeletons, spiders & raiders",
    "snow": "snow", "soul_speed_blocks": "soul sand/soil",
}
PROJECTILE_NAMES = {
    "small_fireball": "small fireball", "fireball": "fireball", "wind_charge": "wind charge",
    "snowball": "snowball", "llama_spit": "llama spit", "shulker_bullet": "shulker bullet",
    "dragon_fireball": "dragon fireball", "wither_skull": "wither skull", "evoker_fangs": "evoker fangs",
    "arrow": "arrow",
}
CONDITIONS = {
    "always": "", "in_water": "in water", "underwater": "underwater", "wet": "when wet", "dry": "when dry",
    "in_rain": "in rain", "thundering": "in thunderstorms", "in_sunlight": "in sunlight",
    "in_darkness": "in darkness", "open_sky": "under open sky", "day": "by day", "night": "at night",
    "in_lava": "in lava", "on_fire": "while burning", "cold": "in the cold", "hot": "in hot places",
    "in_overworld": "in the Overworld", "in_nether": "in the Nether", "in_end": "in the End",
    "sneaking": "while sneaking", "sprinting": "while sprinting", "airborne": "in mid-air",
    "low_health": "at low health", "starving": "when starving",
}
NEGATED = {
    "in_water": "out of water", "in_nether": "outside the Nether", "in_end": "outside the End",
    "open_sky": "under a roof", "wet": "when dry", "dry": "when wet",
}


def path_of(ident: str) -> str:
    return ident.lstrip("#").split(":", 1)[-1]


def title(ident: str) -> str:
    p = path_of(ident)
    if p in NAME_OVERRIDES:
        return NAME_OVERRIDES[p]
    words = p.replace("/", " ").split("_")
    return " ".join(w if (i and w in SMALL_WORDS) else w.capitalize() for i, w in enumerate(words))


def thing(ident: str) -> str:
    """A target / entity / item id or #tag as a short readable noun."""
    if ident.startswith("#"):
        p = path_of(ident)
        return TAG_NAMES.get(p, p.replace("_", " "))
    return title(ident)


def things(ids, limit: int = 3) -> str:
    if isinstance(ids, str):
        ids = [ids]
    names = [thing(i) for i in ids]
    if len(names) > limit:
        return ", ".join(names[:2]) + f" +{len(names) - 2} more"
    if len(names) > 1:
        return ", ".join(names[:-1]) + " & " + names[-1]
    return names[0] if names else ""


def condition(b: dict) -> str:
    parts = []
    conds = b.get("condition", [])
    for c in [conds] if isinstance(conds, str) else conds:
        neg = c.startswith("!")
        c = c.lstrip("!")
        if c == "near_entity":
            txt = f"near {things(b.get('condition_entities', []))}"
            if "condition_radius" in b:
                txt += f" ({num(b['condition_radius'])} blocks)"
        elif c == "on_block":
            txt = f"on {things(b.get('condition_blocks', []))}"
        else:
            txt = CONDITIONS.get(c, c.replace("_", " "))
        if neg:
            txt = NEGATED.get(c, "not " + txt)
        if txt:
            parts.append(txt)
    text = " and ".join(parts)
    anys = b.get("condition_any")
    if anys:
        alt = " or ".join(CONDITIONS.get(c, c.replace("_", " ")) for c in anys)
        text = f"{text} and ({alt})" if text else alt
    return text


def with_cond(text: str, b: dict) -> str:
    c = condition(b)
    return f"{text} {c}" if c else text


# ---- numbers -----------------------------------------------------------------------------------------------

def num(x: float) -> str:
    s = f"{float(x):.3f}".rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s.replace("-", MINUS)


def signed(x: float) -> str:
    return ("+" if x >= 0 else "") + num(x)


def levels(value, max_level: int) -> list:
    """A LevelValue: a number is multiplied by the level, an array is indexed by level - 1 (last entry repeats)."""
    if isinstance(value, list):
        if not value:
            return [0] * max_level
        return [value[min(i, len(value) - 1)] for i in range(max_level)]
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return [value * (i + 1) for i in range(max_level)]
    return [0] * max_level


def series(vals, fmt=num, suffix: str = "") -> str:
    out = [fmt(v) for v in vals]
    if len(set(out)) == 1:
        out = out[:1]
    return "/".join(out) + suffix


def pct(vals) -> str:
    """Signed percentages of multipliers: 0.6 → −40 %."""
    return series([(v - 1) * 100 for v in vals], signed, " %")


def seconds(vals) -> str:
    return series([v / 20 for v in vals], num, " s")


def roman(n: int) -> str:
    return ROMAN[n] if 0 <= n < len(ROMAN) else str(n)


def effect(eid: str, amps) -> str:
    """Effect name with its per-level amplifier: Slowness I/II/III, Speed —/I/II."""
    name = title(eid)
    shown = ["—" if a < 0 else roman(int(a) + 1) for a in amps]
    if len(set(shown)) == 1:
        return name if shown[0] == "I" else f"{name} {shown[0]}"
    return f"{name} {'/'.join(shown)}"


# ---- attributes --------------------------------------------------------------------------------------------

def attribute(a: dict, n: int) -> str:
    aid = path_of(a.get("attribute", "?"))
    name = ATTRIBUTE_NAMES.get(aid, aid.replace("_", " ").capitalize())
    vals = levels(a.get("amount"), n)
    if a.get("operation") in ("add_multiplied_base", "add_multiplied_total"):
        return f"{name} {series([v * 100 for v in vals], signed, ' %')}"
    if aid in FRACTION_ATTRIBUTES:
        return f"{name} {series([v * 100 for v in vals], signed, ' %')}"
    return f"{name} {series(vals, signed, ATTRIBUTE_UNITS.get(aid, ''))}"


# ---- behaviors ---------------------------------------------------------------------------------------------

def damage_scope(b: dict) -> str:
    names = []
    if b.get("damage_tag"):
        names.append(DAMAGE_NAMES.get(path_of(b["damage_tag"]), title(b["damage_tag"])))
    for t in b.get("damage_types", []):
        p = path_of(t)
        if p in ("magic", "indirect_magic"):
            if "Magic" not in names:
                names.append("Magic")
            continue
        names.append(DAMAGE_NAMES.get(p) or p.replace("_", " "))
    if not names:
        return ""
    text = " & ".join(names)
    return text[0].upper() + text[1:]


def f_damage_multiplier(b, n):
    vals = levels(b.get("multiplier"), n)
    scope = damage_scope(b)
    if b.get("attacker"):
        subject = f"Damage from {things(b['attacker'])}"
    elif scope:
        subject = f"{scope} damage"
    else:
        subject = "Damage taken"
    if all(v == 0 for v in vals):
        return with_cond(f"Immune to {subject[0].lower() + subject[1:]}", b)
    return with_cond(f"{subject} {pct(vals)}", b)


def f_damage_dealt_multiplier(b, n):
    scope = damage_scope(b)
    subject = f"{scope} damage dealt" if scope else "Damage dealt"
    if b.get("target"):
        subject += f" to {things(b['target'])}"
    return with_cond(f"{subject} {pct(levels(b.get('multiplier'), n))}", b)


def f_heal_multiplier(b, n):
    subject = "Natural regeneration" if b.get("source") == "natural" else "Healing"
    return with_cond(f"{subject} {pct(levels(b.get('multiplier'), n))}", b)


def f_heal_over_time(b, n):
    every = num(b.get("interval", 20) / 20)
    return with_cond(f"Heals {series(levels(b.get('amount'), n))} HP every {every} s", b)


def payload(b, n) -> str:
    """The effect / ignite / damage payload shared by retaliate, attack_effect, aura and kill_reward."""
    parts = []
    if b.get("effect"):
        txt = effect(b["effect"], levels(b["amplifier"], n) if "amplifier" in b else [0] * n)
        if "duration" in b:
            txt += f" for {seconds(levels(b['duration'], n))}"
        parts.append(txt)
    if b.get("ignite_seconds") is not None:
        parts.append(f"fire for {series(levels(b['ignite_seconds'], n), num, ' s')}")
    return " + ".join(parts)


def chance(b, n) -> str:
    vals = levels(b.get("chance", [1.0]), n)
    if all(v >= 1 for v in vals):
        return ""
    return f" ({series([v * 100 for v in vals], num, ' %')} chance)"


def f_retaliate(b, n):
    parts = []
    if b.get("damage") is not None:
        parts.append(f"Melee attackers take {series(levels(b['damage'], n))} damage")
    p = payload(b, n)
    if p:
        parts.append(f"Melee attackers get {p}")
    return with_cond("; ".join(parts) + chance(b, n), b)


def f_attack_effect(b, n):
    who = "Projectile hits" if b.get("damage_tag") else "Melee hits"
    return with_cond(f"{who} inflict {payload(b, n)}{chance(b, n)}", b)


def f_kill_reward(b, n):
    txt = f"Kills heal {series(levels(b.get('heal'), n))} HP"
    p = payload(b, n)
    return with_cond(txt + (f" + {p}" if p else ""), b)


def f_status_effect(b, n):
    amps = levels(b.get("amplifier", 0), n) if "amplifier" in b else [0] * n
    txt = effect(b.get("effect", "?"), amps)
    if "interval" in b:
        txt += f" for {seconds(levels(b.get('duration', 0), n))} every {seconds(levels(b['interval'], n))}"
    return with_cond(txt, b)


def f_effect_modifier(b, n):
    if b.get("effects"):
        subject = " & ".join(title(e) for e in b["effects"])
    else:
        subject = f"{str(b.get('category', 'all')).capitalize()} effects"
    mode = b.get("mode")
    vals = levels(b.get("value", 1), n)
    if mode == "immune":
        return with_cond(f"Immune to {subject}", b)
    if mode == "invert":
        return with_cond(f"{subject} hurts instead of healing", b)
    if mode == "amplifier":
        return with_cond(f"{subject} amplifier {series(vals, signed)}", b)
    return with_cond(f"{subject} duration {pct(vals)}", b)


def f_hunger_drain(b, n):
    return with_cond(f"Hunger drain {pct(levels(b.get('multiplier'), n))}", b)


def f_food_modifier(b, n):
    items = b.get("items")
    subject = things(items) if items else "All food"
    subject = subject[0].upper() + subject[1:]
    nut = levels(b.get("nutrition", 1), n)
    sat = levels(b.get("saturation", 1), n)
    parts = []
    if nut == sat and any(v != 1 for v in nut):
        parts.append(f"food value {pct(nut)}")
    else:
        if any(v != 1 for v in nut):
            parts.append(f"nutrition {pct(nut)}")
        if any(v != 1 for v in sat):
            parts.append(f"saturation {pct(sat)}")
    p = payload(b, n)
    if p and not parts:
        return with_cond(f"Eating {subject[0].lower() + subject[1:]} gives {p}", b)
    if p:
        parts.append(f"eating gives {p}")
    return with_cond(f"{subject}: {', '.join(parts) if parts else 'modified'}", b)


def f_xp_multiplier(b, n):
    return with_cond(f"XP from orbs {pct(levels(b.get('multiplier'), n))}", b)


def f_durability_multiplier(b, n):
    return with_cond(f"Item wear {pct(levels(b.get('multiplier'), n))}", b)


def f_environment_damage(b, n):
    helmet = " (a helmet protects)" if b.get("helmet_blocks") else ""
    cond = condition(b)
    if b.get("ignite_seconds") is not None:
        return f"Catches fire ({series(levels(b['ignite_seconds'], n), num, ' s')}) {cond}{helmet}".replace("  ", " ")
    interval = b.get("interval", 20)
    rate = "per s" if interval == 20 else f"every {num(interval / 20)} s"
    return f"{series(levels(b.get('damage'), n))} HP {rate} {cond}{helmet}".rstrip()


def f_struck_by(b, n):
    who = things(b.get("entities", []))
    return with_cond(f"{who} hits deal {series(levels(b.get('damage'), n))} extra damage", b)


def f_sink_in_water(b, n):
    return with_cond(f"Sinks in water ({series(levels(b.get('speed'), n))} blocks/tick²)", b)


def f_walk_on_fluid(b, n):
    fluid = b.get("fluid", "water")
    if b.get("mode") == "frost":
        return with_cond(f"Freezes {fluid} underfoot (radius {series(levels(b.get('radius'), n))})", b)
    return with_cond(f"Walks on {fluid}", b)


def f_mob_attitude(b, n):
    who = things(b.get("entities", []))
    att = b.get("attitude")
    if att == "ignore":
        return with_cond(f"Ignored by {who}", b)
    radius = series(levels(b.get("radius"), n), num, " blocks")
    if att == "flee":
        return with_cond(f"Scares off {who} within {radius}", b)
    return with_cond(f"Hunted by {who} within {radius}", b)


def f_detection_range(b, n):
    subject = f"Detection range for {things(b['entities'])}" if b.get("entities") else "Mob detection range"
    return with_cond(f"{subject} {pct(levels(b.get('multiplier'), n))}", b)


def f_aura(b, n):
    targets = b.get("targets")
    who = {"hostile": "hostile mobs", "all_mobs": "mobs"}.get(targets) if isinstance(targets, str) else None
    who = who or things(targets or [])
    radius = series(["—" if v <= 0 else num(v) for v in levels(b.get("radius"), n)], str, " blocks")
    if b.get("ignite_seconds") is not None and not b.get("effect"):
        burn = series(levels(b["ignite_seconds"], n), num, " s")
        return with_cond(f"Sets {who} within {radius} on fire for {burn}", b)
    return with_cond(f"Gives {who} within {radius} {payload(b, n)}", b)


def f_item_magnet(b, n):
    return with_cond(f"Pulls items within {series(levels(b.get('radius'), n), num, ' blocks')}", b)


def cooldown(b, n) -> str:
    return f"cooldown {seconds(levels(b['cooldown'], n))}" if "cooldown" in b else ""


def f_air_jump(b, n):
    return with_cond(f"{series(levels(b.get('charges'), n))} mid-air jumps", b)


def f_glide(b, n):
    return with_cond(f"Glides without an elytra for up to {seconds(levels(b.get('max_ticks'), n))}", b)


def f_flight(b, n):
    lock = levels(b.get("combat_lock_ticks", 0), n)
    txt = "Creative-style flight"
    if any(lock):
        txt += f", grounded for {seconds(lock)} after combat"
    return with_cond(txt, b)


def f_climb_walls(b, n):
    return with_cond("Climbs walls like a ladder", b)


def f_teleport(b, n):
    rng = series(levels(b.get("range"), n), num, " blocks")
    if b.get("trigger") == "on_hurt":
        return with_cond(f"When hurt: {series([v * 100 for v in levels(b.get('chance'), n)], num, ' %')} chance "
                         f"of a random teleport ({rng})", b)
    kind = "blink where you look" if b.get("mode") == "look" else "random teleport"
    cd = cooldown(b, n)
    return with_cond(f"Sneak-jump: {kind}, up to {rng}" + (f" ({cd})" if cd else ""), b)


def f_sneak_detonate(b, n):
    cd = cooldown(b, n)
    return with_cond(f"Double-tap sneak: explode (power {series(levels(b.get('power'), n))}, no block damage"
                     + (f", {cd}" if cd else "") + ")", b)


def f_shoot_projectile(b, n):
    proj = PROJECTILE_NAMES.get(b.get("projectile"), str(b.get("projectile", "?")).replace("_", " "))
    count = levels(b.get("count", 1), n)
    txt = f"Sneak-swing: {series(count)}× {proj}"
    extra = []
    if b.get("damage") is not None:
        extra.append(f"{series(levels(b['damage'], n))} damage")
    if b.get("explosion_power") is not None:
        extra.append(f"power {series(levels(b['explosion_power'], n))}")
    cd = cooldown(b, n)
    if cd:
        extra.append(cd)
    return with_cond(txt + (f" ({', '.join(extra)})" if extra else ""), b)


def f_sonic_boom(b, n):
    return with_cond(f"Sneak-swing: sonic boom, {series(levels(b.get('damage'), n))} damage, "
                     f"{series(levels(b.get('range'), n), num, ' blocks')} ({cooldown(b, n)})", b)


def f_wipe_on_death(b, n):
    return "Any death wipes **all** your traits"


FORMATTERS = {name[2:]: fn for name, fn in globals().items() if name.startswith("f_") and callable(fn)}


def escape(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def side_cell(side: dict, kind: str, n: int, lang: dict) -> str:
    key = side.get("key", "?")
    name = lang.get(f"absorbaholic.{kind}.{key}", title(key))
    desc = lang.get(f"absorbaholic.{kind}.{key}.desc", "")
    parts = [attribute(a, n) for a in side.get("attributes", []) if isinstance(a, dict)]
    fallback = False
    for b in side.get("behaviors", []):
        if not isinstance(b, dict):
            continue
        fn = FORMATTERS.get(path_of(str(b.get("type", ""))))
        if fn is None:
            fallback = True
            continue
        try:
            parts.append(fn(b, n))
        except (TypeError, ValueError, KeyError, AttributeError, IndexError):
            fallback = True
    if (fallback or not parts) and desc:
        parts.append(desc.rstrip("."))
    body = "; ".join(p for p in parts if p)
    return escape(f"**{name}**: {body}" if body else f"**{name}**")


# ---- sources -----------------------------------------------------------------------------------------------

def load_sources(root: Path) -> list:
    sources = []
    for folder in sorted(root.glob(DATA_GLOB)):
        namespace = folder.parent.parent.name
        for f in sorted(folder.rglob("*.json")):
            data = json.loads(f.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or data.get("disabled") is True:
                continue
            path = f.relative_to(folder).with_suffix("").as_posix()
            sources.append((f"{namespace}:{path}", data))
    return sources


def source_name(sid: str, data: dict, lang: dict) -> str:
    if data.get("name") and data["name"] in lang:
        return lang[data["name"]]
    targets = data.get("targets") or []
    for t in targets:
        if not t.startswith("#"):
            return title(t)
    return title(targets[0]) if targets else title(sid)


def max_level(data: dict) -> int:
    m = data.get("max_level", 3)
    return m if isinstance(m, int) and not isinstance(m, bool) and 1 <= m <= 10 else 3


def source_table(root: Path, show_ids: bool) -> str:
    lang = json.loads((root / LANG).read_text(encoding="utf-8"))
    rows = []
    for sid, data in load_sources(root):
        n = max_level(data)
        tier = data.get("tier", "common")
        name = source_name(sid, data, lang)
        kind = "mob" if data.get("kind") == "entity" else "block"
        cell = escape(name)
        if show_ids:
            shown = sid[len("absorbaholic:"):] if sid.startswith("absorbaholic:") else sid
            cell += f" (`{shown}`)"
        rows.append((TIERS.index(tier) if tier in TIERS else len(TIERS), kind != "block", name.lower(), [
            cell, kind, lang.get(f"absorbaholic.tier.{tier}", tier.capitalize()),
            side_cell(data.get("trait") or {}, "trait", n, lang),
            side_cell(data.get("weakness") or {}, "weakness", n, lang),
            roman(n),
        ]))
    rows.sort(key=lambda r: r[:3])
    blocks = sum(1 for r in rows if not r[1])
    out = [
        f"{len(rows)} sources ({blocks} block, {len(rows) - blocks} mob), sorted by tier. "
        "Numbers are per level (I/II/III); `−40 %` on damage means you take 40 % less.",
        "",
        "| Source | Kind | Tier | Trait (I/II/III) | Weakness (I/II/III) | Max level |",
        "|---|---|---|---|---|---|",
    ]
    out += ["| " + " | ".join(r[3]) + " |" for r in rows]
    return "\n".join(out)


# ---- caps --------------------------------------------------------------------------------------------------

CONST_RE = re.compile(r"public static final (?:int|float|double) ([A-Z0-9_]+) = ([-0-9.]+)[FDfd]?;")
CLAMP_RE = re.compile(r'new Clamp\("minecraft:([a-z_]+)", Clamp\.Mode\.([A-Z_]+), ([-0-9.]+), ([-0-9.]+)\)')

# (what, template) — {NAME} is replaced by the AbsorbCaps constant, {NAME/20} by ticks in seconds, {NAME%} by a
# fraction as a percentage. An unknown constant fails the script, so the docs can't drift from the code.
CAPS_ROWS = [
    ("Absorb channel", "hold {CHANNEL_TICKS} ticks ({CHANNEL_TICKS/20} s)"),
    ("Cooldown after an absorption", "{COOLDOWN_TICKS/20} s per player"),
    ("Mob health to absorb it", "at most {MOB_HEALTH_THRESHOLD%} of its max health"),
    ("Pure / mutation chance", "{PURE_CHANCE%} / {MUTATION_CHANCE%} per absorption"),
    ("Source max level", "default {DEFAULT_MAX_LEVEL}, allowed {MIN_MAX_LEVEL}–{MAX_MAX_LEVEL}"),
    ("Trait slots per player", "default {DEFAULT_MAX_TRAITS}, `/absorbaholic max` {MIN_MAX_TRAITS}–{MAX_MAX_TRAITS}"),
    ("Weakness damage", "at most {WEAKNESS_DAMAGE_BUDGET} HP per {WEAKNESS_DAMAGE_WINDOW_TICKS} ticks; one hit never "
                        "takes you from full health below {WEAKNESS_MIN_HEALTH_FROM_FULL} HP"),
    ("Damage taken (all traits combined)", "never below ×{DAMAGE_TAKEN_FLOOR} (max {DAMAGE_TAKEN_FLOOR~} "
                                           "reduction); weaknesses at most ×{DAMAGE_TAKEN_WEAKNESS_CEILING}"),
    ("Immunities", "only fire, fall, drowning, freezing, magma floor, cactus, berry bush, lightning and ender "
                   "pearl damage; never `/kill`, the void or generic damage"),
    ("Damage dealt", "×{DAMAGE_DEALT_MIN}–×{DAMAGE_DEALT_MAX}"),
    ("Healing", "×{HEAL_FACTOR_MIN}–×{HEAL_FACTOR_MAX}"),
    ("Hunger drain", "×{EXHAUSTION_FACTOR_MIN}–×{EXHAUSTION_FACTOR_MAX}"),
    ("XP from orbs", "×{EXPERIENCE_FACTOR_MIN}–×{EXPERIENCE_FACTOR_MAX}"),
    ("Item wear", "×{DURABILITY_FACTOR_MIN}–×{DURABILITY_FACTOR_MAX}"),
    ("Mob detection range", "×{VISIBILITY_FACTOR_MIN}–×{VISIBILITY_FACTOR_MAX}, provoking at most "
                            "{DETECTION_MAX_RADIUS} blocks away"),
    ("Effect amplifier from weaknesses", "at most level {EFFECT_AMPLIFIER_MAX+1}"),
    ("Active abilities", "cooldown ≥ {ABILITY_MIN_COOLDOWN_TICKS/20} s, range ≤ {ABILITY_MAX_RANGE} blocks, "
                         "≤ {ABILITY_MAX_TARGETS} targets, {ABILITY_EXHAUSTION} exhaustion per use; one ability per "
                         "trigger"),
    ("Teleport / sonic boom / evoker fangs", "≤ {TELEPORT_MAX_DISTANCE} blocks / ≤ {SONIC_BOOM_MAX_RANGE} blocks / "
                                             "≤ {EVOKER_FANGS_MAX} fangs"),
    ("Explosions", "power ≤ {ABILITY_MAX_EXPLOSION_POWER} (fireballs ≤ {FIREBALL_MAX_EXPLOSION_POWER}), never "
                   "break blocks"),
    ("Flight", "{FLIGHT_EXHAUSTION_PER_TICK} exhaustion per tick; losing it mid-air gives "
               "{FLIGHT_LOSS_SLOW_FALLING_TICKS/20} s of Slow Falling"),
    ("Auras / item magnet", "radius ≤ {AURA_MAX_RADIUS} / ≤ {ITEM_MAGNET_MAX_RADIUS} blocks"),
    ("World protection", "bedrock (`#absorbaholic:bedrock_protected`) can't be absorbed in the bottom "
                         "{PROTECTED_LAYERS} layers or at the Nether roof"),
]
CLAMP_UNITS = {"max_health": " HP", "block_interaction_range": " blocks", "entity_interaction_range": " blocks",
               "step_height": " blocks", "safe_fall_distance": " blocks"}
CLAMP_NAMES = {"fall_damage_multiplier": "Fall damage multiplier", "burning_time": "Burning time multiplier"}


def caps_values(root: Path) -> tuple:
    src = (root / CAPS).read_text(encoding="utf-8")
    consts = {m.group(1): float(m.group(2)) for m in CONST_RE.finditer(src)}
    clamps = CLAMP_RE.findall(src)
    if not consts or not clamps:
        raise SystemExit(f"error: could not read constants or clamps from {CAPS}")
    return consts, clamps


def caps_table(root: Path) -> str:
    consts, clamps = caps_values(root)

    def sub(m):
        expr = m.group(1)
        name = re.match(r"[A-Z0-9_]+", expr).group(0)
        if name not in consts:
            raise SystemExit(f"error: AbsorbCaps has no constant {name} (used by the caps table)")
        v, op = consts[name], expr[len(name):]
        if op == "/20":
            return num(v / 20)
        if op == "%":
            return num(v * 100) + " %"
        if op == "~":
            return num((1 - v) * 100) + " %"
        if op == "+1":
            return roman(int(v) + 1)
        return num(v)

    out = ["| Cap | Value |", "|---|---|"]
    for what, template in CAPS_ROWS:
        out.append(f"| {what} | {re.sub(r'{([^}]+)}', sub, template)} |")
    out += ["", "Attribute clamps (on the final value, whatever else changes it):", "",
            "| Attribute | Range |", "|---|---|"]
    for attr, mode, lo, hi in clamps:
        name = CLAMP_NAMES.get(attr) or ATTRIBUTE_NAMES.get(attr, attr.replace("_", " ").capitalize())
        unit = CLAMP_UNITS.get(attr, "")
        lo_s, hi_s = num(float(lo)), num(float(hi))
        if mode == "BASE_MULTIPLE":
            rng = f"×{lo_s}–×{hi_s} of base"
        elif mode == "BASE_OFFSET":
            rng = f"base {signed(float(lo))} to base {signed(float(hi))}"
        else:
            rng = f"{lo_s} to {hi_s}{unit}" if float(lo) < 0 else f"{lo_s}–{hi_s}{unit}"
        out.append(f"| {name} | {rng} |")
    return "\n".join(out)


# ---- files -------------------------------------------------------------------------------------------------

def replace_block(text: str, marker: str, body: str, required: bool, fname: str) -> str:
    start, end = f"<!-- {marker}:start -->", f"<!-- {marker}:end -->"
    i, j = text.find(start), text.find(end)
    if i < 0 or j < 0 or j < i:
        if required:
            raise SystemExit(f"error: {fname} has no {start} / {end} markers")
        return text
    return text[:i + len(start)] + "\n" + body + "\n" + text[j:]


def render(root: Path) -> dict:
    caps = caps_table(root)
    result = {}
    for fname, show_ids in TARGETS:
        path = root / fname
        old = path.read_text(encoding="utf-8")
        new = replace_block(old, "source-table", source_table(root, show_ids), True, fname)
        new = replace_block(new, "caps-table", caps, False, fname)
        result[fname] = (old, new)
    return result


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="exit 1 with a diff if a file is out of date")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="repository root")
    args = ap.parse_args(argv)
    root = Path(args.root)
    stale = False
    for fname, (old, new) in render(root).items():
        if old == new:
            continue
        if args.check:
            stale = True
            sys.stdout.writelines(difflib.unified_diff(
                old.splitlines(True), new.splitlines(True), f"a/{fname}", f"b/{fname}"))
        else:
            (root / fname).write_text(new, encoding="utf-8")
            print(f"updated {fname}")
    if stale:
        print("\nerror: generated tables are out of date; run: python3 scripts/gen-source-table.py", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
