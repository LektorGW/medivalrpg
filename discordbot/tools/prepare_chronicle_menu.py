# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Resource-only rebuild from 9f690a61. No class changes, no production access."""
from pathlib import Path
import copy
import hashlib
import json
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'deploy-discordbot-layout-20261029/plugins/update/Menu-1.0.0.jar'
OUT = ROOT / 'discordbot/build/menu-kronika'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(BASE) == '9f690a6184e55bba97a743cc1510d226181dffab52125a08c0685ed5c03d5bb5'
OUT.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(BASE) as source:
    menus = yaml.safe_load(source.read('menus.yml'))
    help_data = yaml.safe_load(source.read('pomoc.yml'))
    before_menus, before_help = copy.deepcopy(menus), copy.deepcopy(help_data)
    pause = yaml.safe_load((ROOT / 'deploy-kolej-pause-20261029/MENU-ADDITIONS.yml').read_text(encoding='utf-8'))['menus']['kolej-infrastruktura']['items']
    rail = menus['menus']['kolej-infrastruktura']
    keys = ['siec-postep', 'siec-wstrzymaj', 'siec-wznow']
    for key in keys:
        rail['items'][key] = copy.deepcopy(pause[key])
        rail['items'][key]['requires-command'] = 'kolej siec ' + key.removeprefix('siec-')
    rail['rows'] = 6
    rail['auto-slots'] = [row * 9 + column for row in range(1, 5) for column in range(1, 8)]
    rail['items']['wstecz']['slot'] = 49
    menus['menus']['admin']['items']['discord-kronika'] = {
        'material': 'WRITABLE_BOOK', 'name': '<gold>Kronika wojen — kolejka',
        'action': 'command:discord admin kronika', 'requires-plugin': 'DiscordBot',
        'requires-command': 'discord admin kronika', 'permission': 'discordbot.admin'}
    menus['menus']['admin']['items']['discord-layout']['requires-command'] = 'discord admin uklad podglad'
    help_data['categories']['start']['commands'].append({
        'command': '/discord admin kronika',
        'description': 'Stan kolejki kroniki wojen. Fakty po zdarzeniu, grupy co 5 min, podsumowanie o 21:00.',
        'permission': 'discordbot.admin', 'requires-plugin': 'DiscordBot',
        'requires-command': 'discord admin kronika', 'discord': False})
    # Prove every unrelated semantic YAML field remains untouched.
    compared = copy.deepcopy(menus)
    compared['menus']['kolej-infrastruktura'] = copy.deepcopy(before_menus['menus']['kolej-infrastruktura'])
    compared['menus']['admin']['items'].pop('discord-kronika')
    compared['menus']['admin']['items']['discord-layout'].pop('requires-command')
    assert compared == before_menus
    assert help_data['categories']['start']['commands'][:-1] == before_help['categories']['start']['commands']
    resources = {name: '# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl\n' + yaml.safe_dump(data, allow_unicode=True, sort_keys=False)
                 for name, data in [('menus.yml', menus), ('pomoc.yml', help_data)]}
    with zipfile.ZipFile(OUT / 'Menu-1.0.0.jar', 'w') as target:
        for entry in source.infolist():
            target.writestr(copy.copy(entry), resources[entry.filename].encode('utf-8') if entry.filename in resources else source.read(entry.filename))
    with zipfile.ZipFile(OUT / 'Menu-1.0.0.jar') as target:
        assert source.namelist() == target.namelist()
        changes = [name for name in source.namelist() if source.read(name) != target.read(name)]
        assert set(changes) == {'menus.yml', 'pomoc.yml'}
    for name, text in resources.items():
        (OUT / name).write_text(text, encoding='utf-8')
        (ROOT / 'menu/src/main/resources' / name).write_text(text, encoding='utf-8')
    # Only these keys must be merged on hosting if Menu already has a persistent menus.yml.
    additions = {'menus': {'kolej-infrastruktura': rail, 'admin': {'items': {
        'discord-kronika': menus['menus']['admin']['items']['discord-kronika'],
        'discord-layout': menus['menus']['admin']['items']['discord-layout']}}}}
    (OUT / 'MENU-ADDITIONS.yml').write_text('# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl\n' + yaml.safe_dump(additions, allow_unicode=True, sort_keys=False), encoding='utf-8')
result = {'baseSha256': sha(BASE), 'jarSha256': sha(OUT / 'Menu-1.0.0.jar'),
          'allClassesIdentical': True, 'unrelatedYamlUnchanged': True, 'changedEntries': changes, 'pauseKeys': keys}
(OUT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
