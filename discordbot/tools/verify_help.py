# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Sprawdza, że przygotowane komendy nie zmieniają publikowanego katalogu Discorda."""
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import build_player_help_catalog as gen
import test_build_player_help_catalog as suite

source = gen.SOURCE.read_text(encoding='utf-8')
data = yaml.safe_load(source)
baseline = copy.deepcopy(data)
entries = baseline['categories']['start']['commands']
discord = [entry for entry in entries if entry['command'].startswith('/discord ')]
added = [entry for entry in discord if entry.get('permission') == 'discordbot.admin']
assert len(discord) == 9 and len(added) == 5 and all(entry.get('discord') is False for entry in added)
baseline['categories']['start']['commands'] = [entry for entry in entries if entry not in added]
current_pages, baseline_pages = gen.build(data), gen.build(baseline)
assert current_pages == baseline_pages
assert '/discord admin ' not in '\n'.join(page['content'] for page in current_pages)


def run(source_file):
    gen.SOURCE = source_file
    result = unittest.TextTestRunner(stream=io.StringIO()).run(unittest.defaultTestLoader.loadTestsFromModule(suite))
    return {'tests': result.testsRun, 'failures': [test.id() for test, _ in result.failures],
            'errors': [test.id() for test, _ in result.errors]}


with tempfile.TemporaryDirectory() as temp:
    path = Path(temp) / 'baseline.yml'
    path.write_text(yaml.safe_dump(baseline, allow_unicode=True, sort_keys=False), encoding='utf-8')
    previous = run(path)
    current = run(ROOT / 'menu/src/main/resources/pomoc.yml')
assert previous == current
result = {'discordEntriesHidden': True, 'publishedPagesUnchanged': True, 'baseline': previous, 'withDiscordBot': current,
          'explanation': 'Cztery publiczne komendy pozostają aktywne; pięć administracyjnych ukryte. Usunięcie tylko administracji nie zmienia publikowanych stron.'}
out = ROOT / 'discordbot/build/help-validation.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: cztery publiczne komendy zachowane, pięć administracyjnych ukryte; strony publikacji identyczne z/bez wpisów administracji.')
