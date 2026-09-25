# Absorbaholic — Branding

**Tagline:** *Sneak. Absorb. Become.*
Alt: *You are what you absorb.*

## CurseForge description

> **Absorbaholic** lets you eat the world. Sneak with both hands empty, hold use on a block or a mob at low health, and **absorb it**: the thing is gone, and you keep **one of its traits and one of its weaknesses**. Obsidian makes you blast-proof but slow. A blaze lets you throw fireballs, and rain starts to hurt. The warden gives you a sonic boom and pulses of darkness. There are **128 sources** (blocks and mobs, from dirt to the Ender Dragon), each stacking to **level III**. Every absorption can **mutate** (15 %: one side jumps +2) or come out **pure** (2 %: no weakness gained). Hold up to 20 sources at once, and your oldest one makes room for the next. The mode is a toggle on the world-creation screen, saved with the world, and operators can flip it any time with `/absorbaholic on|off`. Everything is data-driven, so datapacks can add, tweak or remove any source. A **Fabric mod for Java 26.2–26.3**, in English and Russian.

## Features
- 🧬 **Absorb anything:** 128 sources covering over a thousand blocks and 72 mobs. Sneak, hold use for 1.5 s, and it's yours.
- ⚖️ **Every trait has a price:** each source gives one trait and one weakness. Diamond skin, but explosions hit ×2.5. Flight, but death wipes everything.
- 📈 **Stack it up:** absorb a source again to level both sides, up to III.
- 🎲 **Mutations:** 15 % of absorptions mutate (+2 on one side), 2 % come out pure (no weakness gained), with their own fanfare.
- ⚡ **Real abilities:** blink like an enderman, throw fireballs, sonic-boom, glide, double-jump, climb walls, walk on lava. No extra keys: sneak + swing, sneak + jump, double-tap sneak.
- 🔍 **Discover as you go:** sneak and look at something to see what it gives. Sources nobody has absorbed yet show `???`.
- 📜 **My Traits screen:** press K for every trait you carry, with levels, tiers and mutation tags.
- 🌍 **Per-world settings:** mode, keep-on-death, hints and trait slots, all switchable by operators with `/absorbaholic`.
- 🛡️ **Safety caps:** damage reduction, speed, health, armor and weakness damage are all capped, so nothing one-shots you and no build is truly immortal.
- 🔕 **Your call on noise:** turn the absorb sound, the messages, or both off (Mod Menu or `/absorbaholic-notify`).
- 📦 **Datapack-friendly:** one JSON file per source. Add, change or disable any of them.
- 🧑‍🤝‍🧑 **Multiplayer ready:** works on dedicated servers; mutations are announced to everyone.
- 🌐 **English and Russian.**

## Color palette
| Role | Hex |
|---|---|
| Background, deep teal | `#0E2E2B` |
| Background, darkest / border | `#07181A` |
| Ink / outline | `#081C1C` |
| Frame teal | `#2FB396` |
| Frame inner shade | `#17574D` |
| Glint magenta (absorb) | `#FF4FC8` |
| Sparkle pink | `#FF9AE6` |
| Energy light | `#B4FFE6` |
| Energy mid | `#46E0B4` |
| Energy dark | `#23A386` |
| Gold (helix rungs) | `#FFD640` |
| Gold shade | `#B07010` |
| Bonus green (+) | `#80FF40` |
| Hand skin light / mid / shade / dark | `#F4C9A0` / `#D69A6E` / `#9C6246` / `#6A3C2C` |

In-game text colors (as implemented):

| Where | Color |
|---|---|
| Title `🧬 Absorbed: <source>` | default white |
| Source name in the hint and on the traits screen | its tier color: Common white, Uncommon `§a`, Rare `§b`, Epic `§d`, Legendary `§6` |
| Trait name + level (actionbar, hint, screen) | `§a` green |
| Weakness name + level | `§c` red (a pure entry with no weakness: aqua) |
| Trait-mutation subtitle and broadcast | `§d` light purple |
| Weakness-mutation subtitle | `§5` dark purple |
| Pure subtitle and broadcast | `§b` aqua |
| Refusal reasons | `§c` red |
| Eviction notice | `§7` gray |
| `/absorbaholic` status header | `§6` gold |

Colors are applied in code (`withStyle(ChatFormatting.X)`), never as `§` codes inside lang values, so English and
Russian carry identical markup.

## Player-facing strings (en_us)

The core UX keys as merged in `fabric/src/main/resources/assets/absorbaholic/lang/en_us.json` (`ru_ru.json` has the
same keys). The full file also has the `.status`, `.none`, `.unknown` and tooltip variants.

| Key | Text | Where |
|---|---|---|
| `absorbaholic.createWorld.toggle` | Absorbaholic Mode | Create World (Game tab) |
| `absorbaholic.createWorld.toggle.tooltip` | Sneak with an empty hand and hold Use on a block, a fluid or a badly hurt mob to absorb it: you gain its trait, and its weakness too. Saved with this world. Operators can change it later with /absorbaholic on\|off. | Create World (Game tab) |
| `absorbaholic.absorbed.title` | 🧬 Absorbed: %s | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.title.short` | 🧬 Absorbed! | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.subtitle.joined` | %s · %s | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.actionbar` | %s %s · %s %s | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.actionbar.pure` | %s %s · no weakness | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.subtitle.mutate_trait` | Mutation! The trait surged | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.subtitle.mutate_weakness` | Mutation! The weakness surged | Absorbing (title, actionbar, chat) |
| `absorbaholic.absorbed.subtitle.pure` | Pure absorption! No weakness this time | Absorbing (title, actionbar, chat) |
| `absorbaholic.announce.mutate_trait` | 🧬 %s absorbed %s and MUTATED: the trait surged! | Absorbing (title, actionbar, chat) |
| `absorbaholic.announce.mutate_weakness` | 🧬 %s absorbed %s and MUTATED: the weakness surged! | Absorbing (title, actionbar, chat) |
| `absorbaholic.announce.pure` | ✨ %s pulled off a PURE absorption of %s: the weakness didn't grow! | Absorbing (title, actionbar, chat) |
| `absorbaholic.message.evicted` | No trait slots left: %s faded away to make room. | Absorbing (title, actionbar, chat) |
| `absorbaholic.message.lost` | ☠ Your absorbed traits died with you (keep-on-death is off). | Absorbing (title, actionbar, chat) |
| `absorbaholic.message.wiped` | ☠ A deathly curse wiped out all your absorbed traits! | Absorbing (title, actionbar, chat) |
| `absorbaholic.refuse.bees_inside` | Bees are home: wait until the hive is empty | Refusals (actionbar) |
| `absorbaholic.refuse.container_not_empty` | Empty it first: its contents would be lost | Refusals (actionbar) |
| `absorbaholic.refuse.cooldown` | Still digesting… %s s | Refusals (actionbar) |
| `absorbaholic.refuse.disabled` | Absorbaholic Mode is off in this world | Refusals (actionbar) |
| `absorbaholic.refuse.max_level` | %s is already at max level | Refusals (actionbar) |
| `absorbaholic.refuse.mob_carries_items` | Take its gear first: armor, saddle or chest would be lost | Refusals (actionbar) |
| `absorbaholic.refuse.mob_health` | Too healthy: weaken it to %s%% health or less | Refusals (actionbar) |
| `absorbaholic.refuse.not_allowed` | You're not allowed to break blocks here | Refusals (actionbar) |
| `absorbaholic.refuse.protected` | This bedrock holds the world together | Refusals (actionbar) |
| `absorbaholic.hint.empty_hand` | Empty your hand to absorb | Sneak hint (HUD) |
| `absorbaholic.hint.flowing` | Only a source block works | Sneak hint (HUD) |
| `absorbaholic.hint.maxed` | Already at max level | Sneak hint (HUD) |
| `absorbaholic.hint.needs_health` | Weaken it to %s%% health first | Sneak hint (HUD) |
| `absorbaholic.hint.not_absorbable` | Not absorbable | Sneak hint (HUD) |
| `absorbaholic.hint.protected` | Protected: holds the world together | Sneak hint (HUD) |
| `absorbaholic.hint.trait` | Trait: %s | Sneak hint (HUD) |
| `absorbaholic.hint.unknown` | ??? | Sneak hint (HUD) |
| `absorbaholic.hint.weakness` | Weakness: %s | Sneak hint (HUD) |
| `absorbaholic.command.mode.on` | Absorbaholic Mode is now ON for this world | Commands |
| `absorbaholic.command.mode.off` | Absorbaholic Mode is now OFF for this world | Commands |
| `absorbaholic.command.mode.status.on` | Absorbaholic Mode is ON in this world | Commands |
| `absorbaholic.command.mode.status.off` | Absorbaholic Mode is OFF in this world | Commands |
| `absorbaholic.command.status.header` | Absorbaholic in this world: | Commands |
| `absorbaholic.command.keep_on_death.on` | Players now keep their traits on death in this world | Commands |
| `absorbaholic.command.keep_on_death.off` | Players now lose their traits on death in this world | Commands |
| `absorbaholic.command.hints.on` | Absorb hints are now ON for this world | Commands |
| `absorbaholic.command.hints.off` | Absorb hints are now OFF for this world | Commands |
| `absorbaholic.command.max.set` | Players can now hold up to %s traits in this world | Commands |
| `absorbaholic.command.max.clamped` | %s is outside %s–%s: players can now hold up to %s traits in this world | Commands |
| `absorbaholic.command.traits.header` | Traits of %s (%s/%s): | Commands |
| `absorbaholic.command.traits.entry` | %s: %s %s · %s %s | Commands |
| `absorbaholic.command.traits.dormant` | Absorbaholic Mode is OFF: these traits are dormant | Commands |
| `absorbaholic.command.remove.done` | Removed %s from the traits of %s | Commands |
| `absorbaholic.command.reset.done` | Reset %s: %s traits removed | Commands |
| `absorbaholic.command.notify.sound` | Absorb sounds: %s | Commands |
| `absorbaholic.command.notify.message` | Absorb messages: %s | Commands |
| `absorbaholic.settings.title` | Absorbaholic Settings | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.notifySound` | Absorb Sound | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.notifySound.tooltip` | Play a sound when you absorb something. | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.notifyMessage` | Absorb Message | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.notifyMessage.tooltip` | Show the title and the actionbar line when you absorb something. | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.traits` | My Traits | Settings screen (Mod Menu) and keybind |
| `absorbaholic.settings.traits.tooltip` | Everything you have absorbed in this world. | Settings screen (Mod Menu) and keybind |
| `key.absorbaholic.traits` | Open Traits | Settings screen (Mod Menu) and keybind |
| `key.category.absorbaholic.main` | Absorbaholic | Settings screen (Mod Menu) and keybind |
| `absorbaholic.screen.traits.title.own` | My Traits | Traits screen |
| `absorbaholic.screen.traits.title` | Traits of %s | Traits screen |
| `absorbaholic.screen.traits.count` | %s / %s traits | Traits screen |
| `absorbaholic.screen.traits.empty.own` | Nothing absorbed yet. Sneak with an empty hand and hold Use on a block, a fluid or a badly hurt mob. | Traits screen |
| `absorbaholic.screen.traits.inactive` | Source removed by a datapack: inactive | Traits screen |
| `absorbaholic.screen.traits.status.mode_off` | Absorbaholic Mode is off: all traits are dormant | Traits screen |
| `absorbaholic.screen.traits.status.creative` | Dormant in Creative and Spectator | Traits screen |
| `absorbaholic.screen.traits.tag.mutated` | Mutated | Traits screen |
| `absorbaholic.screen.traits.tag.pure` | Pure | Traits screen |
| `death.attack.absorbaholic.weakness` | %1$s succumbed to an absorbed weakness | Death message |
| `death.attack.absorbaholic.weakness.player` | %1$s succumbed to an absorbed weakness while fighting %2$s | Death message |

**Trait, weakness and source names** come from the data: `absorbaholic.trait.<key>` and
`absorbaholic.weakness.<key>` (128 and 122 names, each with a `.desc`), `absorbaholic.tier.<tier>`, and
`absorbaholic.source.<id>` for the 53 family sources. Sources with a single direct target use the vanilla name of
that block or mob.

**Absorbed feedback:** mod clients measure the title. If `🧬 Absorbed: <source>` doesn't fit, the title becomes
`🧬 Absorbed!` and the source name moves to the subtitle. Levels use vanilla `enchantment.level.N`
(`Blast Proof III`). Server-built text goes through `Texts.tr`, so vanilla clients get the English text instead of
raw keys. The exception is death messages, which vanilla clients show as the raw key.

## CurseForge page

**Categories:** same as Enchantaholic. Its page (fetched 2026-09-24) lists **Miscellaneous** under Mods. ⚠️ Lead:
the fetch only showed that one; check the Enchantaholic project settings for extra categories (for example
*Adventure and RPG* or *Server Utility*) before copying.
**License:** MIT © 2026 nezo.
**Game versions:** 26.2, 26.3 · Fabric · Java 25 · Client and Server.

Paste everything between the lines into the CurseForge description editor (Markdown mode). The two tables are
generated: run `python3 scripts/gen-source-table.py` first.

---

<p align="center"><img src="https://raw.githubusercontent.com/nezo32/absorbaholic/main/docs/branding/curseforge_logo.png" alt="Absorbaholic" width="256"></p>

<p align="center"><em>Sneak. Absorb. Become.</em></p>

**Absorbaholic** lets you eat the world. Sneak with both hands empty, hold use on a block or a weakened mob, and
**absorb it**: the thing is gone, and you keep **one of its traits and one of its weaknesses**. Obsidian makes you
blast-proof but slow. A blaze lets you throw fireballs, and rain starts to hurt.

The mode is an **Absorbaholic Mode** button on the Create World screen, right below Difficulty, and it's **ON by
default**. Operators can switch it any time with `/absorbaholic on|off`.

## What it does

1. **Sneak** with **both hands empty** and **hold use** on a block, a lava source or a mob at **25 % health or
   less**. Sneak and look first: a small hint shows what it gives.
2. After **1.5 s** it's absorbed: blocks vanish without drops, mobs without loot or XP. Nothing valuable is
   destroyed by accident: full chests, hives with bees and mobs wearing gear are refused.
3. You gain its **trait** and its **weakness** at level I. Absorb it again for +1 on both, up to **III**.
4. Every absorption rolls: **15 % mutation** (trait or weakness +2) or **2 % pure** (no weakness gained).
5. Up to **20 sources** at once. A new one past the cap pushes out your oldest.
6. A **10 s** cooldown (relogging doesn't skip it), then go again.

Traits survive death by default (per world). The dragon egg doesn't care: its weakness wipes everything.
Creative and Spectator players never absorb, and their traits sleep until they're back in Survival.

## Features

- 🧬 **Absorb anything:** 128 sources covering over a thousand blocks and 72 mobs.
- ⚖️ **Every trait has a price:** diamond skin, but explosions hit ×2.5. Flight, but death wipes everything.
- 📈 **Stack it up:** repeat absorptions level both sides up to III.
- 🎲 **Mutations:** 15 % mutate, 2 % come out pure, with their own fanfare and a server-wide shout.
- ⚡ **Real abilities:** blink, fireballs, sonic boom, glide, double jump, wall climbing, lava walking. Sneak + swing, sneak + jump or double-tap sneak, no extra keys.
- 🔍 **Discover as you go:** sneak and look to see what something gives. Undiscovered sources show `???`.
- 📜 **My Traits:** press K (or use the Mod Menu button) to see everything you carry.
- 🌍 **Per-world settings:** mode, keep-on-death, hints and trait slots via `/absorbaholic`.
- 🛡️ **Safety caps:** nothing one-shots you, and no build is truly immortal.
- 🔕 **Your call on noise:** mute the sound, the messages or both (Mod Menu or `/absorbaholic-notify`).
- 📦 **Datapack-friendly:** one JSON file per source. Add, change or disable any of them.
- 🧑‍🤝‍🧑 **Multiplayer ready:** works on dedicated servers, settings per player.
- 🌐 **English and Russian.**

## Notification settings

Every absorption shows a title, an actionbar line and a sound. Turn off the sound, the messages or both from
Mod Menu (Mods → Absorbaholic → config: **Absorb Sound** / **Absorb Message**), or with
`/absorbaholic-notify sound off` / `/absorbaholic-notify message off`. Saved on your computer, applied on every
server.

## Sources

<!-- source-table:start -->
128 sources (56 block, 72 mob), sorted by tier. Numbers are per level (I/II/III); `−40 %` on damage means you take 40 % less.

| Source | Kind | Tier | Trait (I/II/III) | Weakness (I/II/III) | Max level |
|---|---|---|---|---|---|
| Coal | block | Common | **Slow Burner**: Hunger drain −10/−20/−30 % | **Sooty Lungs**: Drowning damage +50/+100/+150 % | III |
| Dirt | block | Common | **Down to Earth**: Knockback resistance +10/+20/+30 % | **Weak Knees**: Safe fall distance −0.5/−1/−1.5 blocks | III |
| Flowers | block | Common | **Sweet Scent**: Healing +10/+20/+35 % | **Wilting**: Hunger drain +50/+100/+150 % in darkness | III |
| Grass & Shrubs | block | Common | **Hide in the Grass**: Mob detection range −15/−30/−45 % while sneaking | **Bug Bait**: Detection range for arthropods +30/+60/+100 % | III |
| Gravel | block | Common | **Flint Finder**: Luck +0.5/+1/+1.5 | **Pulled Down**: Gravity +10/+20/+30 % | III |
| Harvest | block | Common | **Hearty Meals**: All food: saturation +15/+30/+50 % | **Rot-Prone**: Hunger duration +50/+100/+200 % | III |
| Leaves | block | Common | **Photosynthesis**: Heals 0.5/0.75/1 HP every 3 s in sunlight | **Light as a Leaf**: Knockback taken +20/+40/+60 % | III |
| Masonry | block | Common | **Reinforced**: Armor toughness +0.5/+1/+1.5; Explosion knockback resistance +10/+20/+30 % | **Concrete Shoes**: Sinks in water (0.015/0.025/0.035 blocks/tick²) | III |
| Melon | block | Common | **Juicy**: Heals 0.25/0.5/0.75 HP every 5 s | **Squishy**: Fall damage +20/+40/+60 % | III |
| Moss & Vines | block | Common | **Mossy Steps**: Sneaking speed +0.1/+0.2/+0.3 | **Overgrown**: Movement speed −3/−6/−9 % | III |
| Mud & Clay | block | Common | **Mud Mask**: Fire damage −15/−30/−45 % | **Slippery**: Friction −20/−35/−50 % | III |
| Mushrooms | block | Common | **Fungal Regrowth**: Heals 0.25/0.5/0.75 HP every 3 s in darkness | **Sun Shy**: Hunger drain +30/+60/+100 % in sunlight | III |
| Nether Rock | block | Common | **Hellforged**: Burning time −25/−50/−75 % | **Rain Hater**: 0.25/0.5/0.75 HP per s in rain | III |
| Pumpkin | block | Common | **Pumpkin Head**: Ignored by Enderman; Mob detection range −10/−20/−30 % | **Tunnel Vision**: Entity reach −0.2/−0.4/−0.6 blocks | III |
| Sand | block | Common | **Dune Walker**: Movement efficiency +25/+50/+75 % | **Sand in Your Boots**: Jump strength −5/−10/−15 % | III |
| Seaweed | block | Common | **Sea Eyes**: Oxygen bonus +0.5/+1/+2; Night Vision underwater | **Dry Fuel**: Fire damage +20/+40/+60 % | III |
| Snow | block | Common | **Frostproof**: Freezing damage −50/−75/−100 %; Speed I/I/II on snow | **Melting**: 0.25/0.5/0.75 HP per s in hot places | III |
| Stone | block | Common | **Rock Solid**: Armor +1/+2/+3 | **Stiff Joints**: Attack speed −5/−10/−15 % | III |
| Wood | block | Common | **Heartwood**: Max health +2/+3/+4 HP | **Kindling**: Fire damage +25/+50/+75 % | III |
| Woodwork | block | Common | **Carpenter**: Mining speed +10/+20/+30 % | **Creaky Floorboards**: Mob detection range +15/+30/+50 % | III |
| Wool | block | Common | **Soft Landing**: Safe fall distance +1/+2/+3 blocks | **Soggy Wool**: Slowness I/II/III when wet | III |
| Bat | mob | Common | **Echolocation**: Gives mobs within 6/10/14 blocks Glowing for 1.5 s in darkness | **Flimsy**: Max health −1/−2/−3 HP | III |
| Chicken | mob | Common | **Feather Fall**: Fall damage −40/−70/−100 % | **Fox Bait**: Hunted by Fox & Ocelot within 16/24/32 blocks | III |
| Cow | mob | Common | **Milk Drinker**: Harmful effects duration −15/−30/−45 % | **Herbivore**: Meat: food value −20/−40/−60 % | III |
| Endermite | mob | Common | **Pearl Hopper**: Ender pearl damage −50/−75/−100 % | **Enderman Snack**: Hunted by Enderman within 16/24/32 blocks | III |
| Fish | mob | Common | **Gills**: Oxygen bonus +1/+3/+6; Water movement efficiency +10/+20/+30 % | **Fish Out of Water**: Hunger drain +15/+30/+50 % when dry | III |
| Panda | mob | Common | **Chubby**: Max health +2/+3/+4 HP; Knockback resistance +5/+10/+15 % | **Lazy**: Movement speed −4/−8/−12 % | III |
| Pig | mob | Common | **Iron Stomach**: Hunger duration −50/−75/−100 % | **Pig Out**: Hunger drain +15/+30/+50 % | III |
| Rabbit | mob | Common | **Lucky Foot**: Luck +1/+2/+3 | **Prey Animal**: Hunted by Wolf & Fox within 16/24/32 blocks | III |
| Sheep | mob | Common | **Woolly Coat**: Armor +1/+1.5/+2; Freezing damage −50/−75/−100 % | **Flammable Fluff**: Burning time +50/+100/+150 % | III |
| Silverfish | mob | Common | **Stone Burrower**: Mining efficiency +1/+3/+5 | **Squishable**: Max health −1/−2/−3 HP | III |
| Slime | mob | Common | **Gelatinous**: Fall damage −25/−50/−75 % | **Jiggly**: Attack damage −0.5/−1/−1.5 | III |
| Squid | mob | Common | **Ink Cloud**: Melee attackers get Blindness for 2/3/4 s (30/50/70 % chance) | **Calamari**: Fire damage +30/+60/+100 % | III |
| Zombie | mob | Common | **Undead Vigor**: Max health +2/+3/+4 HP | **Sunburn**: Catches fire (2/4/6 s) in sunlight (a helmet protects) | III |
| Amethyst | block | Uncommon | **Long Reach**: Entity reach +0.5/+1/+1.5 blocks | **Chiming Steps**: Mob detection range +20/+40/+70 % | III |
| Bone Block | block | Uncommon | **Calcium Boost**: Armor toughness +1/+2/+3 | **Dog Treat**: Hunted by Wolf within 16/24/32 blocks | III |
| Cactus | block | Uncommon | **Prickly**: Melee attackers take 1/1.5/2 damage (50/75/100 % chance); Immune to cactus & berry bush damage | **Slow Healer**: Natural regeneration −25/−40/−55 % | III |
| Cinnabar | block | Uncommon | **Quicksilver**: Attack speed +10/+15/+20 % | **Mercury Poisoning**: Healing −15/−30/−45 % | III |
| Cobweb | block | Uncommon | **Sticky Strikes**: Melee hits inflict Slowness I/II/III for 2/3/4 s | **Spider Snack**: Hunted by Spider & Cave Spider within 16/24/32 blocks | III |
| Copper | block | Uncommon | **Conductor**: Attack speed +5/+10/+15 %; Lightning damage −50/−75/−100 % | **Oxidizing**: Mining Fatigue I/II/III when wet | III |
| Coral | block | Uncommon | **Reef Rest**: Heals 0.5/1/1.5 HP every 3 s in water | **Bleaching**: Hunger drain +30/+60/+100 % in sunlight and when dry | III |
| End Stone | block | Uncommon | **Low Gravity**: Gravity −10/−20/−30 % | **Otherworldly**: Hunger drain +15/+30/+50 % outside the End | III |
| Glass | block | Uncommon | **Glass Cannon**: Attack damage +1/+2/+3 | **Fragile**: Max health −2/−4/−6 HP | III |
| Glowing Blocks | block | Uncommon | **Glow Aura**: Night Vision; Gives hostile mobs within —/8/16 blocks Glowing for 2 s | **Walking Lantern**: Mob detection range +30/+60/+100 % | III |
| Gold | block | Uncommon | **Midas Luck**: Luck +1/+2/+3 | **Soft Metal**: Item wear +25/+50/+100 % | III |
| Honey | block | Uncommon | **Sweet Tooth**: Honey Bottle, Sweet Berries +5 more: food value +50/+100/+150 %; Poison duration −25/−50/−75 % | **Honey Thief**: Hunted by Bee within 16/24/32 blocks | III |
| Ice | block | Uncommon | **Frost Walker**: Freezes water underfoot (radius 2/3/4) | **Melts in Flames**: Burning time +100/+150/+200 % | III |
| Iron | block | Uncommon | **Iron Skin**: Armor +2/+3/+4 | **Sinks Like Iron**: Sinks in water (0.025/0.04/0.055 blocks/tick²) | III |
| Lapis Lazuli | block | Uncommon | **Wisdom**: XP from orbs +20/+40/+60 % | **Mana Allergy**: Magic damage +25/+50/+100 % | III |
| Magma Block | block | Uncommon | **Hot Touch**: Sets hostile mobs within 1/1.5/2 blocks on fire for 2/3/4 s; Immune to magma floor damage | **Snow Allergy**: 0.5/1/1.5 HP per s in the cold; Snowball hits deal 1/2/3 extra damage | III |
| Nether Quartz | block | Uncommon | **Sharp Edges**: Attack damage +0.5/+1/+1.5 | **Pincushion**: Projectile damage +15/+30/+45 % | III |
| Prismarine | block | Uncommon | **Aqua Affinity**: Underwater mining speed +0.3/+0.5/+0.8 | **Sea-Bound**: Hunger drain +15/+30/+50 % out of water | III |
| Redstone | block | Uncommon | **Overclocked**: Movement speed +5/+10/+15 % | **Short Circuit**: 0.5/0.75/1 HP per s when wet | III |
| Slime Block | block | Uncommon | **Bouncy**: Bounciness +0.3/+0.5/+0.7; Fall damage −30/−60/−90 %; Jump strength +5/+10/+15 % | **Wobbly**: Knockback taken +50/+75/+100 % | III |
| Soul Sand | block | Uncommon | **Soul Speed**: Movement efficiency +30/+60/+100 %; Speed I/II/III on soul sand/soil | **Soul Drag**: Movement speed −4/−8/−12 % | III |
| Sponge | block | Uncommon | **Absorbent**: Oxygen bonus +2/+5/+8 | **Waterlogged**: Hunger drain +100/+200/+300 % when wet | III |
| Sulfur | block | Uncommon | **Noxious Fumes**: Gives hostile mobs within 2/3/4 blocks Weakness for 3 s | **Rotten-Egg Stench**: Scares off Cow, Pig +10 more within 8/12/16 blocks | III |
| TNT | block | Uncommon | **Kaboom Punch**: Attack knockback +0.5/+1/+1.5 | **Volatile**: Fire damage +50/+100/+150 % | III |
| Armadillo | mob | Uncommon | **Roll Up**: Damage taken −20/−35/−50 % while sneaking | **Undead Jitters**: Slowness I/II/III near undead (8 blocks) | III |
| Axolotl | mob | Uncommon | **Play Dead**: Heals 1/1.5/2 HP every 2 s at low health | **Dries Out**: 0.25/0.5/0.75 HP per s when dry and in sunlight | III |
| Bogged | mob | Uncommon | **Toxic Arrows**: Projectile hits inflict Poison for 3/4/6 s | **Swamp Rot**: Healing −20/−35/−50 % | III |
| Camel | mob | Uncommon | **Desert Endurance**: Hunger drain −20/−35/−50 % | **Humpback**: Size +5/+10/+15 % | III |
| Cat | mob | Uncommon | **Cat Presence**: Scares off Creeper & Phantom within 6/10/14 blocks | **Hates Water**: Slowness I/II/III in water | III |
| Cave Spider | mob | Uncommon | **Venomous**: Melee hits inflict Poison for 3/5/7 s | **Fragile Frame**: Max health −2/−3/−4 HP | III |
| Copper Golem | mob | Uncommon | **Sticky Fingers**: Pulls items within 2/3/4 blocks | **Oxidizing**: Slowness I/II/III when wet | III |
| Drowned | mob | Uncommon | **Swift Swimmer**: Water movement efficiency +33/+66/+100 % | **Sunburn**: Catches fire (2/3/5 s) in sunlight (a helmet protects) | III |
| Fox | mob | Uncommon | **Night Stalker**: Night Vision at night; Mob detection range −10/−20/−30 % at night | **Drowsy by Day**: Natural regeneration −20/−40/−60 % by day | III |
| Frog | mob | Uncommon | **Big Leap**: Jump strength +15/+30/+45 %; Safe fall distance +1/+2/+3 blocks | **Cold-Blooded**: Slowness I/II/III in the cold; Freezing damage +50/+100/+200 % | III |
| Goat | mob | Uncommon | **Mountain Climber**: Step height +0.2/+0.4/+0.5 blocks; Safe fall distance +1/+2/+3 blocks | **Clumsy Hooves**: Block reach −0.5/−1/−1.5 blocks | III |
| Guardian | mob | Uncommon | **Spiked Hide**: Melee attackers take 1/2/3 damage (30/50/70 % chance) | **Flop**: Fall damage +50/+100/+150 % | III |
| Hoglin | mob | Uncommon | **Tusk Toss**: Attack knockback +0.5/+1/+1.5 | **Piglin Prey**: Hunted by Piglin & Piglin Brute within 16/24/32 blocks | III |
| Horse | mob | Uncommon | **Gallop**: Movement speed +6/+12/+18 % | **Heavy Hooves**: Fall damage +25/+50/+75 % | III |
| Husk | mob | Uncommon | **Hunger Touch**: Melee hits inflict Hunger I/I/II for 5/8/11 s | **Crumbling**: Knockback taken +15/+30/+45 % | III |
| Illagers | mob | Uncommon | **Raider's Edge**: Attack damage +0.5/+1/+1.5; Projectile damage dealt +10/+20/+30 % | **Golem's Grudge**: Hunted by Iron Golem within 16/24/32 blocks | III |
| Llama | mob | Uncommon | **Spit Take**: Sneak-swing: 1× llama spit (2/3/4 damage, cooldown 3/2/1 s) | **Picky Eater**: All food: food value −15/−30/−45 % | III |
| Magma Cube | mob | Uncommon | **Lava Bounce**: Jump strength +10/+20/+30 %; Fall damage −20/−40/−60 %; Immune to magma floor damage | **Cools Off**: Weakness I/II/III in water | III |
| Mooshroom | mob | Uncommon | **Stew Belly**: Mushroom Stew, Suspicious Stew +2 more: food value +50/+100/+200 % | **Lightning Magnet**: 0.5/1/1.5 HP per s in thunderstorms and under open sky; Lightning damage +100/+200/+300 % | III |
| Nautilus | mob | Uncommon | **Nautilus Breath**: Water movement efficiency +10/+20/+30 %; Breath of the Nautilus underwater | **Drowned Prey**: Detection range for Drowned +50/+100/+200 % | III |
| Ocelot | mob | Uncommon | **Jungle Sprinter**: Movement speed +5/+10/+15 % | **Skittish**: Knockback taken +20/+40/+60 % | III |
| Parched | mob | Uncommon | **Parching Shot**: Projectile hits inflict Weakness I/I/II for 3/5/7 s | **Eternal Thirst**: Hunger drain +20/+40/+60 % when dry | III |
| Parrot | mob | Uncommon | **Flutter**: Gravity −10/−20/−30 %; Fall damage −20/−40/−60 % | **Chatterbox**: Mob detection range +20/+40/+60 %; Eating cookies gives Poison I/I/II for 5/8/10 s | III |
| Piglin | mob | Uncommon | **Piglin Pal**: Attack damage +0.5/+1/+1.5; Ignored by Piglin | **Zombifying**: Healing −20/−40/−60 % outside the Nether | III |
| Polar Bear | mob | Uncommon | **Arctic Fur**: Attack damage +1/+1.5/+2; Freezing damage −50/−75/−100 % | **Overheating**: Slowness I/II/III in hot places | III |
| Pufferfish | mob | Uncommon | **Puff Up**: Melee attackers get Poison I/I/II for 3/5/7 s | **Inflated**: Size +5/+10/+15 % | III |
| Skeleton | mob | Uncommon | **Marksman**: Projectile damage dealt +15/+30/+50 % | **Sunburn**: Catches fire (3/5/8 s) in sunlight (a helmet protects) | III |
| Snow Golem | mob | Uncommon | **Snowballer**: Sneak-swing: 1/2/3× snowball (1/1.5/2 damage, cooldown 1/0.75/0.5 s); Immune to freezing damage | **Melting**: 0.25/0.5/0.75 HP per s in hot places or when wet | III |
| Spider | mob | Uncommon | **Wall Crawler**: Climbs walls like a ladder | **Venom Weakness**: Poison duration +50/+100/+150 %; Poison amplifier +0/+0/+1 | III |
| Stray | mob | Uncommon | **Frost Arrows**: Projectile hits inflict Slowness I/II/III for 3/4/5 s; Freezing damage −50/−75/−100 % | **Melting**: 0.25/0.5/0.75 HP per s in hot places | III |
| Strider | mob | Uncommon | **Lava Strider**: Walks on lava; Fire damage −15/−30/−45 % | **Shivers**: Slowness I/II/III when wet or in the cold | III |
| Turtle | mob | Uncommon | **Shell Armor**: Armor +1/+2/+3 | **Slowpoke**: Movement speed −5/−10/−15 % | III |
| Villager | mob | Uncommon | **Home Cooking**: Bread, Carrot +9 more: food value +25/+50/+100 % | **Zombie Bait**: Damage from zombies +25/+50/+100 % | III |
| Wolf | mob | Uncommon | **Pack Hunter**: Attack damage +1/+1.5/+2 | **Bone Rivalry**: Detection range for skeletons +30/+60/+100 % | III |
| Zombified Piglin | mob | Uncommon | **Fire-Blooded**: Fire damage −20/−40/−60 % | **Pigman Grudge**: Hunted by Zombified Piglin within 16/24/32 blocks | III |
| Chorus | block | Rare | **Chorus Hop**: Sneak-jump: random teleport, up to 8/12/16 blocks (cooldown 10/8/6 s) | **Unstable Matter**: When hurt: 10/20/30 % chance of a random teleport (4/6/8 blocks) | III |
| Crying Obsidian | block | Rare | **Last Stand**: Damage taken −20/−35/−50 % at low health | **Teary Eyes**: Blindness for 2/2.5/3 s every 6/5/4 s at low health | III |
| Diamond | block | Rare | **Diamond Skin**: Armor +3/+5/+7; Armor toughness +1/+2/+3 | **Brittle Brilliance**: Explosion damage +50/+100/+150 % | III |
| Emerald | block | Rare | **Haggler**: Hero of the Village I/II/III | **Wanted Poster**: Hunted by Iron Golem within 16/24/32 blocks | III |
| Obsidian | block | Rare | **Blast Proof**: Explosion knockback resistance +30/+60/+100 %; Explosion damage −40/−60/−75 % | **Dense**: Movement speed −10/−20/−30 % | III |
| Sculk | block | Rare | **Soul Harvest**: Kills heal 1/2/3 HP | **Soul Tax**: XP from orbs −25/−50/−75 % | III |
| Allay | mob | Rare | **Collector**: Pulls items within 4/6/8 blocks | **Delicate**: Max health −2/−4/−6 HP | III |
| Bee | mob | Rare | **Buzz Hover**: 1/2/3 mid-air jumps | **Nectar Addict**: Hunger drain +30/+60/+100 %; 1/2/3 HP per s when starving | III |
| Blaze | mob | Rare | **Blaze Barrage**: Sneak-swing: 1/2/3× small fireball (cooldown 2/1.5/1 s); Fire damage −40/−60/−75 % | **Snowball Bane**: Snowball hits deal 2/3/4 extra damage; 0.5/1/1.5 HP per s when wet | III |
| Breeze | mob | Rare | **Wind Burst**: Sneak-swing: 1× wind charge (cooldown 3/2/1.5 s) | **Gust-Blown**: Knockback taken +30/+60/+90 % | III |
| Creaking | mob | Rare | **Resin Heart**: Heals 0.5/1/1.5 HP every 2 s at night | **Sun-Stiff**: Slowness I/II/III in sunlight | III |
| Creeper | mob | Rare | **Controlled Blast**: Double-tap sneak: explode (power 2/2.5/3, no block damage, cooldown 30/25/20 s); Explosion damage −20/−35/−50 % | **Cat Phobia**: Slowness I/II/III near Cat & Ocelot (10 blocks) | III |
| Dolphin | mob | Rare | **Dolphin's Grace**: Oxygen bonus +1/+2/+4; Dolphin's Grace in water | **Air Breather**: Drowning damage +50/+100/+200 % | III |
| Ghast | mob | Rare | **Fireball**: Sneak-swing: 1× fireball (power 1/1/2, cooldown 5/4/3 s) | **Paper Thin**: Projectile damage +25/+50/+100 % | III |
| Happy Ghast | mob | Rare | **Cloud Floater**: Gravity −20/−35/−50 %; Slow Falling while sneaking and in mid-air | **Big Softie**: Size +10/+15/+20 %; Attack damage −0.5/−1/−1.5 | III |
| Piglin Brute | mob | Rare | **Brute Force**: Attack damage +2/+3/+4; Knockback resistance +10/+20/+30 % | **Hot-Headed**: Hunted by Piglin, Piglin Brute & Zombified Piglin within 16/24/32 blocks | III |
| Sniffer | mob | Rare | **Long Snout**: Block reach +1/+1.5/+2 blocks; Luck +0.5/+1/+1.5 | **Ancient Pace**: Movement speed −6/−12/−18 % | III |
| Vex | mob | Rare | **Spectral Blade**: Attack damage +1/+2/+3 | **Fading**: Healing −30/−50/−65 % | III |
| Witch | mob | Rare | **Brew Master**: Beneficial effects duration +25/+50/+100 % | **Bad Brew**: Harmful effects duration +25/+50/+100 % | III |
| Wither Skeleton | mob | Rare | **Withering Blade**: Melee hits inflict Wither for 3/5/7 s | **Brittle Bones**: Fall damage +30/+60/+100 % | III |
| Ancient Debris | block | Epic | **Netherite Hide**: Damage taken −15/−25/−35 % | **Bottomless Appetite**: Hunger drain +50/+100/+150 % | III |
| Bedrock | block | Epic | **Immovable**: Knockback resistance +40/+70/+100 %; Armor +2/+4/+6 | **Leaden Legs**: Jump strength −10/−25/−37 % | III |
| Lava | block | Epic | **Magma Blood**: Fire damage −50/−75/−100 % | **Hydrophobic**: 1/1.5/2 HP per s when wet | III |
| Enderman | mob | Epic | **Blink**: Sneak-jump: blink where you look, up to 8/12/16 blocks (cooldown 5/4/3 s) | **Ender Outcast**: 0.5/1/1.5 HP per s when wet; Hunted by Enderman within 16/32/48 blocks | III |
| Evoker | mob | Epic | **Fang Summoner**: Sneak-swing: 5/8/12× evoker fangs (6 damage, cooldown 8/6/4 s) | **Hollow Soul**: Max health −2/−4/−6 HP; Hunted by Iron Golem within 16/24/32 blocks | III |
| Iron Golem | mob | Epic | **Titan Strength**: Attack damage +3/+5/+7; Attack knockback +0.5/+1/+1.5; Knockback resistance +20/+40/+60 %; Scares off zombies, skeletons, spiders & raiders within 4/6/8 blocks | **Iron Anchor**: Movement speed −5/−10/−15 %; Sinks in water (0.04/0.06/0.08 blocks/tick²) | III |
| Phantom | mob | Epic | **Night Glide**: Glides without an elytra for up to 5/10/20 s | **Sunburn**: Catches fire (3/5/8 s) in sunlight (a helmet protects) | III |
| Ravager | mob | Epic | **Stampede**: Attack damage +2/+3/+4; Attack knockback +1/+1.5/+2; Knockback resistance +20/+40/+60 % | **Lumbering Beast**: Size +10/+15/+20 %; Movement speed −5/−10/−15 % | III |
| Shulker | mob | Epic | **Shulker Shot**: Armor +2/+3/+4; Sneak-swing: 1× shulker bullet (cooldown 4/3/2 s) | **Shell-Bound**: Movement speed −8/−14/−20 % | III |
| Beacon | block | Legendary | **Beacon Blessing**: Haste II/II/III; Speed —/I/II; Regeneration —/—/I | **Skybound**: Mining Fatigue I/II/III under a roof; Mob detection range +50/+100/+150 % | III |
| Dragon Egg | block | Legendary | **Dragon Flight**: Creative-style flight, grounded for 5 s after combat | **All or Nothing**: Any death wipes **all** your traits | I |
| Elder Guardian | mob | Legendary | **Lord of the Deep**: Water movement efficiency +30/+60/+100 %; Conduit Power I/II/III in water; Melee attackers take 2/3/4 damage in water | **Monument-Bound**: Mining Fatigue I/II/III out of water | III |
| Ender Dragon | mob | Legendary | **Dragon's Breath**: Max health +4/+8/+12 HP; Sneak-swing: 1× dragon fireball (cooldown 10/7/5 s); Damage taken −10/−15/−20 % | **Crystal Bane**: Explosion damage +50/+100/+200 %; Hunted by Enderman within 32/48/64 blocks | III |
| Warden | mob | Legendary | **Sonic Boom**: Max health +4/+6/+8 HP; Sneak-swing: sonic boom, 6/8/10 damage, 10/15/20 blocks (cooldown 10/8/6 s); Immune to Blindness & Darkness | **Echo Darkness**: Darkness for 3/4/5 s every 10/8/6 s | III |
| Wither | mob | Legendary | **Wither's Wrath**: Melee hits inflict Wither I/II/II for 5/5/7 s; Kills heal 2/4/6 HP; Immune to Wither | **Hollow Heart**: Max health −4/−7/−10 HP; Instant Health hurts instead of healing | III |
<!-- source-table:end -->

## Safety caps

<!-- caps-table:start -->
| Cap | Value |
|---|---|
| Absorb channel | hold 30 ticks (1.5 s) |
| Cooldown after an absorption | 10 s per player |
| Mob health to absorb it | at most 25 % of its max health |
| Pure / mutation chance | 2 % / 15 % per absorption |
| Source max level | default 3, allowed 1–5 |
| Trait slots per player | default 20, `/absorbaholic max` 1–64 |
| Weakness damage | at most 8 HP (and at most your max health − 1) per 20 ticks, weakness-caused burning and starvation included; if you were at full health during that time, weaknesses never take you below 1 HP |
| Damage taken (all traits combined) | never below ×0.25 (max 75 % reduction); weaknesses at most ×3 |
| Immunities | only fire, fall, drowning, freezing, magma floor, cactus, berry bush, lightning and ender pearl damage; never `/kill`, the void or generic damage |
| Damage dealt | ×0.5–×2 |
| Healing | ×0.25–×2 |
| Hunger drain | ×0.25–×5 |
| Knockback taken | ×0.5–×3 |
| XP from orbs | ×0.25–×2.5 |
| Item wear | ×0.5–×3 |
| Mob detection range | ×0.25–×3, provoking at most 48 blocks away |
| Effect amplifier from weaknesses | at most level V |
| Active abilities | cooldown ≥ 1 s, range ≤ 24 blocks, ≤ 8 targets, 1 exhaustion per use (0.5 per air jump); one ability per trigger, sneak-swing at most once per 4 ticks |
| Teleport / sonic boom / evoker fangs | ≤ 16 blocks / ≤ 20 blocks / ≤ 16 fangs |
| Explosions | power ≤ 3 (fireballs ≤ 2), never break blocks |
| Flight | speed ≤ 1, 0.01 exhaustion per tick; losing it mid-air gives 10 s of Slow Falling |
| Auras / item magnet | radius ≤ 16 / ≤ 10 blocks (≤ 64 items per pulse) |
| Mob scans (fear, hunting, detection) | ≤ 64 blocks, ≤ 48 mobs per scan |
| World protection | bedrock (`#absorbaholic:bedrock_protected`) can't be absorbed in the bottom 5 layers, in the top 5 under the Nether roof, or anywhere in the End |

Attribute clamps (on the final value, whatever else changes it):

| Attribute | Range |
|---|---|
| Movement speed | ×0.4–×2 of base |
| Size | 0.5–2 |
| Max health | 6–60 HP |
| Block reach | 2.5–8 blocks |
| Entity reach | 2–6 blocks |
| Jump strength | 0.2–1.2 |
| Step height | 0.6–2 blocks |
| Armor | 0–30 |
| Armor toughness | 0–20 |
| Attack damage | base −0.5 to base +20 |
| Attack speed | ×0.5–×2 of base |
| Attack knockback | 0–3 |
| Knockback resistance | 0–1 |
| Gravity | 0.02–0.16 |
| Safe fall distance | 1–24 blocks |
| Fall damage multiplier | 0–3 |
| Luck | −5 to 5 |
| Oxygen bonus | 0–8 |
| Burning time multiplier | 0–3 |
| Water movement efficiency | 0–1 |
| Movement efficiency | 0–1 |
| Mining speed | ×0.25–×4 of base |
| Mining efficiency | 0–40 |
| Underwater mining speed | 0.2–1 |
| Sneaking speed | 0.15–1 |
| Explosion knockback resistance | 0–1 |
| Max absorption | 0–20 |
<!-- caps-table:end -->

## Install

- Minecraft Java **26.2–26.3**
- Fabric Loader 0.19.5+ and Fabric API
- Java 25
- Mod Menu is optional (settings screen)
- Install on **both** the server and the clients

## Known quirks

- Players without the mod on their client can't absorb. Their existing traits still work on the server, except
  wall climbing, lava walking and gliding, and their messages are in English.
- Sources removed by a datapack stay on players but do nothing. `/absorbaholic remove` cleans them up.
- Chests, furnaces, lecterns and campfires with items, hives with bees, and mobs wearing or carrying gear are
  refused. Empty them first.
- You can't absorb where you can't break blocks: spawn protection, the world border, Adventure mode, claim mods.
- Bedrock is protected in the bottom 5 layers, under the Nether roof and everywhere in the End.
- An item in your off hand (a shield, a torch) blocks absorbing, so its vanilla use keeps working.
- Absorbing the Ender Dragon counts as killing it: the portal and the egg appear.
- Other mods' attribute bonuses stack with ours, and the caps apply to the total.

Source: https://github.com/nezo32/absorbaholic

---
