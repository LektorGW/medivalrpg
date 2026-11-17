# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Compile only the synthetic Paper fixture. Never run concurrently with Gradle/Paper."""
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
query = "Get-CimInstance Win32_Process -Filter \"name = 'java.exe'\" | Select-Object -ExpandProperty ProcessId"
running = subprocess.run(['powershell', '-NoProfile', '-Command', query], capture_output=True, text=True, check=True)
if running.stdout.strip():
    raise SystemExit('Inna JVM działa; najpierw ją zakończ zgodnie z jej przeznaczeniem.')
out = ROOT / 'discordbot/build/chronicle-probe'
out.mkdir(parents=True, exist_ok=True)
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
jars = list((cache / 'io.papermc.paper/paper-api').rglob('*.jar'))
for prefix in ['net.kyori', 'com.mojang', 'com.google.code.gson', 'org.jetbrains']:
    for group in cache.glob(prefix + '*'):
        jars.extend(group.rglob('*.jar'))
jars.extend((ROOT / 'testserver/libraries').rglob('*.jar'))
source = ROOT / 'medival-api/kraje-api/src/main/java/pl/medivalrpg/kraje'
sources = [ROOT / 'discordbot/tools/probe/ChronicleProbe.java', source / 'model/Relation.java']
sources += [source / 'api/event' / name for name in ['CountryDisbandEvent.java', 'RelationChangeEvent.java', 'SiegeEndEvent.java']]
args = ['-J-Xmx128m', '-encoding', 'UTF-8', '--release', '25', '-cp', '"' + ';'.join(p.as_posix() for p in jars) + '"',
        '-d', '"' + (out / 'classes').as_posix() + '"'] + ['"' + p.as_posix() + '"' for p in sources]
# -J is a launcher option and must be outside the @argfile.
(out / 'javac.args').write_text('\n'.join(args[1:]), encoding='utf-8')
subprocess.run([str(Path.home() / '.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin/javac.exe'), args[0], '@' + str(out / 'javac.args')], check=True)
with zipfile.ZipFile(out / 'ChronicleProbe.jar', 'w') as jar:
    for p in (out / 'classes').rglob('*.class'):
        jar.write(p, p.relative_to(out / 'classes').as_posix())
    jar.writestr('plugin.yml', "name: Kraje\nversion: 0.0.0-test\nmain: pl.medivalrpg.discordbotprobe.ChronicleProbe\napi-version: '26.3'\nauthors: [Maciej Kaczmarczyk]\n")
print('Przygotowano wyłącznie atrapę testową; NIE WDRAŻAĆ.')
