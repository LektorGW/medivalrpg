# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Resource-only Menu rebuild from the explicitly requested postactivation candidate. No production access."""
from pathlib import Path
import copy
import hashlib
import json
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'deploy-postactivation-20261030/plugins/update/Menu-1.0.0.jar'
OUT = ROOT / 'discordbot/build/menu'
OUT.mkdir(parents=True, exist_ok=True)
expected = '4ba2b7b6f4d1d1a1ee4e0c7660f9289c26e654011f4036c6ece9a03dba84b595'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(BASE) == expected
with zipfile.ZipFile(BASE) as source:
    menus = yaml.safe_load(source.read('menus.yml'))
    help_data = yaml.safe_load(source.read('pomoc.yml'))
    before_menus, before_help = copy.deepcopy(menus), copy.deepcopy(help_data)
    desired = yaml.safe_load((ROOT / 'menu/src/main/resources/pomoc.yml').read_text(encoding='utf-8'))
    discord_commands = [entry for entry in desired['categories']['start']['commands'] if entry['command'].startswith('/discord ')]
    assert len(discord_commands) == 8
    assert all(entry.get('permission') == 'discordbot.admin' and entry.get('discord') is False for entry in discord_commands if '/discord admin ' in entry['command'])
    commands = help_data['categories']['start']['commands']
    commands[:] = [entry for entry in commands if not entry['command'].startswith('/discord ')]
    commands.extend(discord_commands)
    additions = yaml.safe_load((ROOT / 'discordbot/MENU-ADDITIONS.yml').read_text(encoding='utf-8'))
    for section, value in additions['menus'].items():
        menus['menus'][section]['items'].update(value['items'])
    # Prove that all unrelated menus/help content survived the targeted merge.
    for data in (before_help, help_data):
        data['categories']['start']['commands'] = [entry for entry in data['categories']['start']['commands'] if not entry['command'].startswith('/discord ')]
    assert before_help == help_data
    # Rebuild the command list after the comparison above.
    help_data['categories']['start']['commands'].extend(discord_commands)
    for section, value in additions['menus'].items():
        for key in value['items']:
            before_menus['menus'][section]['items'].pop(key, None)
    compared = copy.deepcopy(menus)
    for section, value in additions['menus'].items():
        for key in value['items']:
            compared['menus'][section]['items'].pop(key, None)
    assert compared == before_menus
    copyright = '# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl\n'
    resources = {'menus.yml': copyright + yaml.safe_dump(menus, allow_unicode=True, sort_keys=False),
                 'pomoc.yml': copyright + yaml.safe_dump(help_data, allow_unicode=True, sort_keys=False)}
    target = OUT / 'Menu-1.0.0.jar'
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as destination:
        for entry in source.infolist():
            destination.writestr(copy.copy(entry), resources[entry.filename].encode('utf-8') if entry.filename in resources else source.read(entry.filename))
    with zipfile.ZipFile(target) as built:
        changes = [name for name in source.namelist() if source.read(name) != built.read(name)]
        assert set(changes) <= {'menus.yml', 'pomoc.yml'}
        assert source.namelist() == built.namelist()
    for name, text in resources.items():
        (OUT / name).write_text(text, encoding='utf-8')
result = {'baseSha256': expected, 'jarSha256': sha(target), 'changedEntries': changes,
          'allClassesIdentical': True, 'unrelatedYamlUnchanged': True, 'discordHelpCommands': len(discord_commands)}
(OUT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
