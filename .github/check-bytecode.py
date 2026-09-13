"""Байткод мода обязан быть восьмым: на этом стоит вся 1.7.10.

Проверяются только наши классы: чужие библиотеки внутри jar живут по своим правилам, а
мультирелизные каталоги игра всё равно не читает.
"""

import struct
import sys
import zipfile
from pathlib import Path

JAVA_8 = 52
OURS = 'com/mrleonardos/'


def wrong(jar):
    found = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith('.class') or not name.startswith(OURS):
                continue
            head = archive.read(name)[:8]
            major = struct.unpack('>H', head[6:8])[0]
            if major != JAVA_8:
                found.append((name, major))
    return found


def main(folder):
    jars = sorted(Path(folder).glob('*.jar'))
    if not jars:
        print('Нет ни одного jar в ' + folder)
        return 1
    broken = 0
    for jar in jars:
        if jar.name.endswith('-sources.jar') or jar.name.endswith('-javadoc.jar'):
            continue
        found = wrong(jar)
        if found:
            broken += 1
            print(jar.name + ': не восьмой байткод')
            for name, major in found[:10]:
                print('    ' + name + ' major ' + str(major))
        else:
            print(jar.name + ': байткод восьмой')
    return 1 if broken else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'build/libs'))
