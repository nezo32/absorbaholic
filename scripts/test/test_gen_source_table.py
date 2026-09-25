#!/usr/bin/env python3
"""Tests for scripts/gen-source-table.py: python3 scripts/test/test_gen_source_table.py"""

import importlib.util
import sys
import io
import json
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("gen", REPO / "scripts" / "gen-source-table.py")
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

DOC = "# Doc\n\n<!-- source-table:start -->\nold\n<!-- source-table:end -->\n\n<!-- caps-table:start -->\n<!-- caps-table:end -->\n"


class Fixture:
    """A throwaway repo root with the real AbsorbCaps, a small lang file and chosen source files."""

    def __init__(self, sources: dict, lang: dict):
        self.dir = Path(tempfile.mkdtemp())
        caps = self.dir / gen.CAPS
        caps.parent.mkdir(parents=True)
        shutil.copy(REPO / gen.CAPS, caps)
        lang_path = self.dir / gen.LANG
        lang_path.parent.mkdir(parents=True)
        lang_path.write_text(json.dumps(lang), encoding="utf-8")
        for sid, data in sources.items():
            ns, path = sid.split(":")
            f = self.dir / f"fabric/src/main/resources/data/{ns}/absorbaholic/source/{path}.json"
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(json.dumps(data), encoding="utf-8")
        for fname, _ in gen.TARGETS:
            (self.dir / fname).parent.mkdir(parents=True, exist_ok=True)
            (self.dir / fname).write_text(DOC, encoding="utf-8")

    def run(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = gen.main(["--root", str(self.dir), *args])
        return code, out.getvalue()

    def read(self, fname="README.md"):
        return (self.dir / fname).read_text(encoding="utf-8")

    def close(self):
        shutil.rmtree(self.dir)


OBSIDIAN = {
    "kind": "block", "targets": ["minecraft:obsidian"], "tier": "rare", "max_level": 3,
    "trait": {"key": "blast_proof", "behaviors": [
        {"type": "absorbaholic:damage_multiplier", "damage_tag": "minecraft:is_explosion", "multiplier": [0.6, 0.4, 0.25]}]},
    "weakness": {"key": "dense", "attributes": [
        {"attribute": "minecraft:movement_speed", "operation": "add_multiplied_total", "amount": [-0.1, -0.2, -0.3]}]},
}
LANG = {
    "absorbaholic.trait.blast_proof": "Blast Proof", "absorbaholic.weakness.dense": "Dense",
    "absorbaholic.trait.mystery": "Mystery", "absorbaholic.trait.mystery.desc": "Does something odd.",
    "absorbaholic.weakness.meh": "Meh", "absorbaholic.tier.rare": "Rare", "absorbaholic.tier.common": "Common",
}


class GenSourceTableTest(unittest.TestCase):
    def test_numbers_and_names(self):
        fx = Fixture({"absorbaholic:obsidian": OBSIDIAN}, LANG)
        try:
            self.assertEqual(fx.run()[0], 0)
            readme = fx.read()
            self.assertIn("| Obsidian (`obsidian`) | block | Rare | **Blast Proof**: Explosion damage "
                          "−40/−60/−75 % | **Dense**: Movement speed −10/−20/−30 % | III |",
                          readme)
            self.assertNotIn("`obsidian`", fx.read("docs/branding/branding.md"))
            self.assertIn("| Absorb channel | hold 30 ticks (1.5 s) |", readme)
        finally:
            fx.close()

    def test_unknown_behavior_falls_back_to_description_and_disabled_is_skipped(self):
        fx = Fixture({
            "mypack:odd": {"kind": "entity", "targets": ["minecraft:tnt"], "max_level": 2,
                           "trait": {"key": "mystery", "behaviors": [{"type": "mypack:whatever", "x": 1}]},
                           "weakness": {"key": "meh", "attributes": [
                               {"attribute": "minecraft:armor", "operation": "add_value", "amount": -1}]}},
            "absorbaholic:gone": {"disabled": True},
        }, LANG)
        try:
            fx.run()
            readme = fx.read()
            self.assertIn("| TNT (`mypack:odd`) | mob | Common | **Mystery**: Does something odd "
                          "| **Meh**: Armor −1/−2 | II |", readme)
            self.assertIn("1 sources (0 block, 1 mob)", readme)
        finally:
            fx.close()

    def test_check_reports_stale_files(self):
        fx = Fixture({"absorbaholic:obsidian": OBSIDIAN}, LANG)
        try:
            code, out = fx.run("--check")
            self.assertEqual(code, 1)
            self.assertIn("-old", out)
            fx.run()
            self.assertEqual(fx.run("--check"), (0, ""))
        finally:
            fx.close()

    def test_repository_docs_are_up_to_date(self):
        self.assertEqual(gen.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
