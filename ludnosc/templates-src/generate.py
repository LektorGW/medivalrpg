# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Generates every built-in Ludnosc building template into src/main/resources/budynki/ and renders previews.

    python generate.py            # YAML + validation against the MC 26.3 block-state report
    python generate.py --preview  # + isometric PNG previews in ../preview/
    python generate.py --only-variants [--preview]  # only the look variants (variants.py, workshops.py), base files untouched

Block states are validated against src/test/resources/mc-block-states.json, extracted from the vanilla
registry report (java -DbundlerMainClass=net.minecraft.data.Main -jar paper-26.3-218.jar --reports).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import lib  # noqa: E402
import classic  # noqa: E402
import towns  # noqa: E402
import grand  # noqa: E402
import economy  # noqa: E402
import extra  # noqa: E402
import eras  # noqa: E402
import eras2  # noqa: E402
import variants  # noqa: E402
import workshops  # noqa: E402
import opisy  # noqa: E402

ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'budynki')
PREVIEW = os.path.join(ROOT, 'preview')
STATES = os.path.join(ROOT, 'src', 'test', 'resources', 'mc-block-states.json')
TEXTS = os.path.join(ROOT, 'src', 'main', 'resources', 'opisy.yml')
PREVIOUS = os.path.join(HERE, 'opisy_poprzednie.json')
# id -> (effect, description, look, base type) of every template written in this run (-> opisy.yml)
TEKSTY = {}


def meta(id_, name, color, desc, function, beds, cost, tier, category, zone, max_per=0, pop=0, treasury=0,
         foundation='fill', fblock='cobblestone', ground=0, era=1, discovery='', variant=''):
    # the description is composed in opisy.py (function of the BASE type + look of this template); `desc` is the old
    # look-only text, kept in the calls as a note for whoever draws the template
    effect, desc, look = opisy.teksty(id_, variant or id_, beds)
    assert "'" not in desc and "'" not in effect, id_
    TEKSTY[id_] = (effect, desc, look, variant or id_)
    m = {'id': id_}
    if variant:
        m['variant-of'] = variant  # look variant of a base type (same function, limits, config)
    m.update({'name': f"'<{color}>{name}</{color}>'", 'description': f"'{desc}'", 'function': function,
         'beds': beds, 'cost': cost, 'tier': tier, 'category': category, 'zone': zone, 'max-per-country': max_per})
    if pop or treasury or era > 1 or discovery:
        m['requires'] = {'population': pop, 'treasury': treasury}
        if era > 1:
            m['requires']['era'] = era
        if discovery:
            m['requires']['discovery'] = discovery
    m.update({'foundation': foundation, 'foundation-block': fblock, 'ground-layer': ground})
    return m


def buildings():
    sb, g = classic.studnia()
    return [
        ('studnia', sb, meta('studnia', 'Studnia', '#5AA0E6', 'Kamienna studnia z daszkiem — serce osady.', 'well', 0, 800,
                             0, 'infrastructure', 'center', 3, ground=g)),
        ('chata', classic.chata(), meta('chata', 'Chata', '#C8A165', 'Mała drewniana chata na kamiennej podmurówce. 2 łóżka.',
                                        'housing', 2, 1500, 0, 'housing', 'inner')),
        ('dom', classic.dom(), meta('dom', 'Dom', '#E0D3B8', 'Piętrowy dom z muru pruskiego. 4 łóżka na piętrze.', 'housing',
                                    4, 4000, 0, 'housing', 'inner')),
        ('spichlerz', classic.spichlerz(), meta('spichlerz', 'Spichlerz', '#E6B35A',
                                                'Drewniany spichlerz ze skrzyniami na zapasy. Zwiększa pojemność spichlerza kraju.',
                                                'granary', 0, 3000, 0, 'food', 'outer')),
        ('kuznia', classic.kuznia(), meta('kuznia', 'Kuźnia', '#9A9A9A', 'Otwarta kuźnia z paleniskiem, kowadłem i kominem.',
                                          'smithy', 0, 3500, 1, 'economy', 'outer', 2, pop=8)),
        ('koszary', classic.koszary(), meta('koszary', 'Koszary', '#B04A3A',
                                            'Kamienne koszary z 6 pryczami dla żołnierzy (nie liczą się jako mieszkania).',
                                            'barracks', 6, 6000, 1, 'military', 'outer', 3, pop=10, fblock='stone_bricks')),
        ('wieza', classic.wieza(), meta('wieza', 'Wieża strażnicza', '#7F8C8D', 'Kamienna wieża z platformą obserwacyjną i blankami.',
                                        'tower', 0, 5000, 1, 'defense', 'border', 0, fblock='stone_bricks')),
        ('karczma', towns.karczma(), meta('karczma', 'Karczma', '#B5651D',
                                          'Karczma z szynkiem, kominkiem i pokojami gościnnymi (4 łóżka dla podróżnych).',
                                          'tavern', 4, 7000, 1, 'civic', 'inner', 2, pop=12)),
        ('mlyn', towns.mlyn(), meta('mlyn', 'Młyn', '#D8C9A3', 'Wiatrak ze skrzydłami z płotków i wełny, z żarnami w środku.',
                                    'mill', 0, 6000, 1, 'economy', 'outer', 2, pop=12)),
        ('stajnia', towns.stajnia(), meta('stajnia', 'Stajnia', '#8B5A2B', 'Drewniana stajnia z boksami, sianem i korytami.',
                                          'stable', 0, 4500, 1, 'economy', 'outer', 2, pop=10)),
        ('mur', towns.mur(), meta('mur', 'Mur z basztą', '#808080', 'Moduł murów miejskich: odcinek muru z blankami i basztą.',
                                  'wall', 0, 2500, 1, 'defense', 'border', 0, pop=10, fblock='stone_bricks')),
        ('pole', towns.pole(), meta('pole', 'Pole uprawne', '#9ACD32', 'Ogrodzone pole z kanałem wody, pszenicą, marchwią i ziemniakami. Daje jedzenie do spichlerza.',
                                    'farm', 0, 600, 0, 'farm', 'farm', 0, foundation='none')),
        ('targ', towns.targ(), meta('targ', 'Targ', '#E8B04A', 'Brukowany plac targowy ze straganami w barwach kraju i fontanną.',
                                    'market', 0, 5000, 2, 'civic', 'center', 1, pop=16, fblock='stone_bricks')),
        ('kamienica', towns.kamienica(), meta('kamienica', 'Kamienica', '#E5D9C3',
                                              'Trzykondygnacyjna kamienica mieszczańska ze sklepem na parterze. 6 łóżek.',
                                              'housing', 6, 9000, 2, 'housing', 'inner', 0, pop=16, fblock='stone_bricks')),
        ('ratusz', towns.ratusz(), meta('ratusz', 'Ratusz', '#C9A227', 'Ratusz z wieżą zegarową, salą rady i kancelarią.',
                                        'townhall', 0, 15000, 2, 'civic', 'center', 1, pop=20, treasury=20000, fblock='stone_bricks')),
        ('kosciol', towns.kosciol(), meta('kosciol', 'Kościół', '#F0E6D2', 'Kamienny kościół z dzwonnicą, witrażami i ołtarzem.',
                                          'church', 0, 20000, 2, 'religious', 'center', 1, pop=24, treasury=25000, fblock='stone_bricks')),
        ('brama', towns.brama(), meta('brama', 'Brama miejska', '#6E6E6E', 'Brama z dwiema basztami, broną i chorągwiami kraju.',
                                      'gate', 0, 8000, 2, 'defense', 'border', 4, pop=20, fblock='stone_bricks')),
        ('piekarnia', economy.piekarnia(), meta('piekarnia', 'Piekarnia', '#E3B778', 'Piekarnia z ceglanym piecem i kominem. Więcej jedzenia.',
                                               'bakery', 0, 3500, 1, 'economy', 'inner', 2, pop=8)),
        ('browar', economy.browar(), meta('browar', 'Browar', '#A0522D', 'Browar z kadziami, warnikami i beczkami. Wyższe podatki.',
                                          'brewery', 0, 5000, 2, 'economy', 'outer', 1, pop=16)),
        ('tartak', economy.tartak(), meta('tartak', 'Tartak', '#B8860B', 'Zadaszony tartak ze stosami bali i piłą. Najlepiej przy lesie.',
                                          'sawmill', 0, 3000, 1, 'economy', 'forest', 2, pop=8)),
        ('kopalnia', None, None),
        ('biblioteka', economy.biblioteka(), meta('biblioteka', 'Biblioteka', '#6A5ACD', 'Biblioteka z regałami i pulpitami. Szybszy wzrost osady.',
                                                  'library', 0, 9000, 2, 'civic', 'inner', 1, pop=20)),
        ('zielarnia', economy.zielarnia(), meta('zielarnia', 'Zielarnia', '#3CB371', 'Chata zielarza z ogródkiem ziół i alembikiem. Zdrowsza ludność.',
                                                'herbalist', 0, 3000, 1, 'civic', 'inner', 1, pop=10)),
        ('przystan', economy.przystan(), meta('przystan', 'Przystań', '#4682B4', 'Chata rybacka z pomostem. Stawiana nad wodą, daje ryby.',
                                              'harbor', 0, 3000, 1, 'economy', 'water', 2, pop=8)),
        ('cmentarz', economy.cmentarz(), meta('cmentarz', 'Cmentarz', '#708090', 'Ogrodzony cmentarz z nagrobkami i krzyżem.',
                                              'cemetery', 0, 1500, 1, 'religious', 'outer', 1, pop=15)),
        ('pomnik', economy.pomnik(), meta('pomnik', 'Pomnik władcy', '#DAA520', 'Pomnik władcy na schodkowym cokole w barwach kraju.',
                                          'monument', 0, 12000, 3, 'representative', 'center', 1, pop=30, treasury=40000)),
        ('magazyn', None, None),
        ('laznia', extra.laznia(), meta('laznia', 'Łaźnia', '#87CEEB', 'Łaźnia z basenem, kadziami gorącej wody i ławami. Zdrowsza i liczniejsza ludność.',
                                        'bath', 0, 8000, 2, 'civic', 'inner', 1, pop=18)),
        ('latarnia', extra.latarnia(), meta('latarnia', 'Latarnia morska', '#F5F5F5', 'Pasiasta latarnia z galerią i światłem morskim. Stawiana nad wodą; bezpieczniejsze połowy i handel.',
                                            'lighthouse', 0, 9000, 2, 'economy', 'water', 1, pop=20, fblock='stone_bricks')),
        ('rynek', extra.rynek(), meta('rynek', 'Rynek z fontanną', '#D2B48C', 'Duży brukowany rynek z fontanną, ławami, latarniami, drzewami i kramami.',
                                      'plaza', 0, 14000, 3, 'civic', 'center', 1, pop=30, treasury=30000, fblock='stone_bricks')),
        ('ogrody', extra.ogrody(), meta('ogrody', 'Ogrody pałacowe', '#7CFC00', 'Strzyżone żywopłoty, kwietniki, staw z liliami, altana i posągi.',
                                        'garden', 0, 30000, 4, 'representative', 'center', 1, pop=60, treasury=150000)),
        ('koszary2', extra.koszary2(), meta('koszary2', 'Koszary rozbudowane', '#8B2500', 'Dwupiętrowe kamienne koszary z 12 pryczami, zbrojownią i blankami (łóżka dla wojska).',
                                            'barracks', 12, 14000, 3, 'military', 'outer', 2, pop=30, fblock='stone_bricks')),
        ('akademia', extra.akademia(), meta('akademia', 'Akademia rycerska', '#4169E1', 'Sala wykładowa z pulpitami i plac ćwiczeń z tarczami, kukłami i torem do kopii.',
                                            'academy', 0, 22000, 3, 'military', 'outer', 1, pop=35, treasury=50000)),
        ('skarbiec', extra.skarbiec(), meta('skarbiec', 'Skarbiec królewski', '#FFD700', 'Grubościenny skarbiec z kratami, złotem i kancelarią. Wyższe podatki.',
                                            'treasury', 0, 25000, 3, 'representative', 'center', 1, pop=35, treasury=60000,
                                            fblock='polished_blackstone_bricks')),
        ('katedra', extra.katedra(), meta('katedra', 'Katedra', '#E6E6FA', 'Gotycka katedra z dwiema wieżami, witrażami, rozetą i ołtarzem; pod nią katakumby.',
                                          'cathedral', 0, 90000, 4, 'religious', 'center', 1, pop=50, treasury=120000,
                                          fblock='stone_bricks')),
        ('arena', extra.arena(), meta('arena', 'Arena', '#CD5C5C', 'Amfiteatr z piaszczystą areną, trybunami, bramami i blankami.',
                                      'arena', 0, 35000, 3, 'civic', 'outer', 1, pop=35, treasury=40000, fblock='stone_bricks')),
        ('most', None, None),
        ('kamieniolom', None, None),
        ('kamienica_renesansowa', eras.kamienica_renesansowa(), meta('kamienica_renesansowa', 'Kamienica renesansowa', '#F4E1C1',
            'Tynkowana kamienica z podcieniami, attyką i sklepem na parterze. 6 łóżek.', 'housing', 6, 9000, 1, 'housing', 'inner',
            0, pop=14, era=2, fblock='stone_bricks')),
        ('kamienica_czynszowa', eras.kamienica_czynszowa(), meta('kamienica_czynszowa', 'Kamienica czynszowa', '#B5523B',
            'Pięciokondygnacyjna ceglana kamienica z balkonami i kominami. 8 łóżek.', 'housing', 8, 12000, 2, 'housing', 'inner',
            0, pop=20, era=3, fblock='stone_bricks')),
        ('fabryka', eras.fabryka(), meta('fabryka', 'Fabryka', '#8B3A3A', 'Ceglana hala fabryczna z dachem szedowym, piecami, taśmą i dwoma kominami.',
            'factory', 0, 25000, 2, 'economy', 'outer', 3, pop=25, era=3, discovery='fabryki', fblock='stone_bricks')),
        ('dworzec', eras.dworzec(), meta('dworzec', 'Dworzec kolejowy', '#6B4F3A', 'Ceglany dworzec z halą, peronem pod wiatą i torami.',
            'station', 0, 30000, 3, 'infrastructure', 'outer', 1, pop=30, era=3, discovery='kolej', fblock='stone_bricks')),
        ('blok', None, None),
        ('wiezowiec_biurowy', eras.wiezowiec_biurowy(), meta('wiezowiec_biurowy', 'Wieżowiec biurowy', '#87CEFA',
            'Szklany biurowiec (ok. 45 bl.) z windą, oświetlonymi piętrami i lądowiskiem na dachu. Tylko na płaskiej działce.',
            'office', 0, 120000, 3, 'highrise', 'center', 2, pop=60, treasury=150000, era=4, discovery='beton_zbrojony',
            fblock='gray_concrete')),
        ('wiezowiec', None, None),
        ('uniwersytet', eras2.uniwersytet(), meta('uniwersytet', 'Uniwersytet', '#E8E4D8', 'Kolegium z salami wykładowymi, biblioteką i portykiem. Dużo punktów badań.',
            'university', 0, 30000, 3, 'civic', 'inner', 1, pop=40, treasury=40000, era=2, discovery='uniwersytety', fblock='polished_diorite')),
        ('drukarnia', eras2.drukarnia(), meta('drukarnia', 'Drukarnia', '#D8C9A8', 'Warsztat drukarski z prasami (krosna), pulpitami i papierem.',
            'printing', 0, 8000, 2, 'economy', 'inner', 1, pop=20, era=2, discovery='druk')),
        ('port', None, None),
        ('bank', eras2.bank(), meta('bank', 'Bank', '#DAA520', 'Bank z kolumnowym portykiem, kasą za kratą i skarbcem.',
            'bank', 0, 25000, 3, 'economy', 'center', 1, pop=35, treasury=60000, era=2, discovery='bankowosc', fblock='polished_andesite')),
        ('obserwatorium', eras2.obserwatorium(), meta('obserwatorium', 'Obserwatorium', '#9FB6CD', 'Wieża z kopułą ze szkła i miedzi i lunetą.',
            'observatory', 0, 18000, 3, 'civic', 'outer', 1, pop=30, era=2, discovery='astronomia', fblock='polished_andesite')),
        ('ratusz_renesansowy', eras2.ratusz_renesansowy(), meta('ratusz_renesansowy', 'Ratusz renesansowy', '#F0D9A0',
            'Tynkowany ratusz z attyką, salą rady i wieżą zegarową z dzwonem.', 'townhall', 0, 30000, 3, 'civic', 'center', 1, pop=40,
            treasury=40000, era=2, fblock='stone_bricks')),
        ('palac_renesansowy', eras2.palac_renesansowy(), meta('palac_renesansowy', 'Pałac renesansowy', '#F5DEB3',
            'Czteroskrzydłowy pałac z arkadowym dziedzińcem, ogrodem i fontanną. 4 łóżka.', 'palace', 4, 120000, 4, 'representative',
            'center', 1, pop=60, treasury=200000, era=2, fblock='stone_bricks')),
        ('szkola', eras2.szkola(), meta('szkola', 'Szkoła', '#B5523B', 'Ceglana szkoła z dwiema klasami, ławkami i dzwonkiem.',
            'school', 0, 12000, 2, 'civic', 'inner', 2, pop=25, era=3, fblock='stone_bricks')),
        ('szpital', None, None),
        ('most_stalowy', None, None),
        ('elektrownia', eras2.elektrownia(), meta('elektrownia', 'Elektrownia', '#7B3F00', 'Elektrownia z kotłami, transformatorami i dwoma kominami.',
            'powerplant', 0, 50000, 3, 'economy', 'outer', 1, pop=50, era=3, discovery='elektrycznosc', fblock='stone_bricks')),
        ('park', eras2.park(), meta('park', 'Park miejski', '#228B22', 'Ogrodzony park z alejkami, drzewami, stawem, ławkami i latarniami.',
            'park', 0, 9000, 2, 'civic', 'inner', 2, pop=30, era=3)),
        ('galeria', eras2.galeria(), meta('galeria', 'Galeria handlowa', '#B0C4DE', 'Dwupoziomowa galeria ze szklaną fasadą i sklepami.',
            'mall', 0, 90000, 4, 'economy', 'center', 1, pop=90, treasury=120000, era=4, discovery='beton_zbrojony', fblock='gray_concrete')),
        ('szpital_nowoczesny', None, None),
        ('szkola_nowoczesna', eras2.szkola_nowoczesna(), meta('szkola_nowoczesna', 'Szkoła nowoczesna', '#C0C0C0',
            'Betonowa szkoła z dużymi oknami i dwoma piętrami klas.', 'school', 0, 30000, 3, 'civic', 'inner', 2, pop=60, era=4,
            discovery='komputery', fblock='gray_concrete')),
        ('stadion', eras2.stadion(), meta('stadion', 'Stadion', '#32CD32', 'Stadion z boiskiem, bramkami, trybunami i masztami oświetlenia.',
            'stadium', 0, 140000, 4, 'civic', 'outer', 1, pop=100, treasury=150000, era=4, fblock='gray_concrete')),
        ('parlament', eras2.parlament(), meta('parlament', 'Parlament', '#E6E6FA', 'Gmach parlamentu z kolumnadą, szklaną kopułą i salą obrad.',
            'parliament', 0, 160000, 5, 'representative', 'center', 1, pop=120, treasury=250000, era=4, fblock='polished_andesite')),
        ('dworzec_nowoczesny', eras2.dworzec_nowoczesny(), meta('dworzec_nowoczesny', 'Dworzec nowoczesny', '#87CEEB',
            'Przeszklony dworzec z peronem pod szklanym dachem.', 'station', 0, 80000, 4, 'infrastructure', 'outer', 1, pop=90,
            era=4, discovery='kolej', fblock='gray_concrete')),
        ('parking', eras2.parking(), meta('parking', 'Parking', '#2F4F4F', 'Asfaltowy parking z liniami i latarniami.',
            'parking', 0, 6000, 3, 'infrastructure', 'inner', 3, pop=60, era=4, discovery='samochod', foundation='none')),
        ('laboratorium', eras2.laboratorium(), meta('laboratorium', 'Laboratorium', '#F8F8FF', 'Laboratorium z dygestoriami, stołami i aparaturą. Dużo punktów badań.',
            'lab', 0, 60000, 4, 'civic', 'outer', 1, pop=80, treasury=100000, era=4, discovery='komputery', fblock='gray_concrete')),
        ('lotnisko', eras2.lotnisko(), meta('lotnisko', 'Lotnisko', '#A9A9A9', 'Pas startowy ze światłami, terminal i wieża kontroli lotów.',
            'airport', 0, 180000, 5, 'infrastructure', 'outer', 1, pop=120, treasury=250000, era=4, discovery='lotnictwo',
            foundation='none')),
        ('zamek', grand.zamek(), meta('zamek', 'Zamek', '#A0A0B0',
                                      'Warowny zamek: mury z blankami, cztery wieże, brama z broną, dziedziniec i donżon (4 łóżka).',
                                      'castle', 4, 60000, 3, 'representative', 'center', 1, pop=40, treasury=100000,
                                      fblock='stone_bricks')),
        ('palac', grand.palac(), meta('palac', 'Pałac', '#FFD700',
                                      'Pałac z kolumnadą, ogrodem z fontanną, salą tronową ze złotym tronem i komnatami (4 łóżka).',
                                      'palace', 4, 150000, 4, 'representative', 'center', 1, pop=60, treasury=250000,
                                      fblock='smooth_stone')),
    ]


def special(name):
    if name == 'port':
        b, g = eras2.port()
        return b, meta('port', 'Port', '#4682B4', 'Port z molo na palach, dźwigiem i magazynem portowym. Handel morski.',
                       'port', 0, 20000, 2, 'economy', 'water', 1, pop=30, era=2, discovery='nawigacja_oceaniczna', ground=g,
                       foundation='none')
    if name == 'szpital':
        b, beds = eras2.szpital()
        return b, meta('szpital', 'Szpital', '#FFFFFF', f'Ceglany szpital z salami chorych ({beds} łóżek dla chorych) i apteką.',
                       'hospital', beds, 25000, 2, 'civic', 'inner', 1, pop=30, era=3, discovery='szczepionki', fblock='stone_bricks')
    if name == 'szpital_nowoczesny':
        b, beds = eras2.szpital_nowoczesny()
        return b, meta('szpital_nowoczesny', 'Szpital nowoczesny', '#F0FFFF', f'Trzypiętrowy szpital z oddziałami ({beds} łóżek) i lądowiskiem.',
                       'hospital', beds, 80000, 4, 'civic', 'inner', 1, pop=80, era=4, discovery='antybiotyki', fblock='gray_concrete')
    if name == 'most_stalowy':
        b, g = eras2.most_stalowy()
        return b, meta('most_stalowy', 'Most stalowy', '#708090', 'Stalowy most kratownicowy na kamiennych filarach z latarniami.',
                       'bridge', 0, 15000, 2, 'infrastructure', 'water', 4, pop=25, era=3, discovery='stal', ground=g, foundation='none')
    if name == 'blok':
        b, beds = eras.blok()
        return b, meta('blok', 'Blok mieszkalny', '#DCDCDC', f'Sześciopiętrowy blok z betonu z oknami w pasach, klatką i płaskim dachem. {beds} łóżek.',
                       'housing', beds, 30000, 2, 'housing', 'inner', 0, pop=40, era=4, discovery='beton_zbrojony', fblock='gray_concrete')
    if name == 'wiezowiec':
        b, beds = eras.wiezowiec()
        return b, meta('wiezowiec', 'Wieżowiec mieszkalny', '#B0C4DE',
                       f'Mieszkalny wieżowiec (ok. 65 bl.) z 15 piętrami, windą i oświetleniem. {beds} łóżek. Tylko na płaskiej działce.',
                       'housing', beds, 200000, 5, 'highrise', 'inner', 3, pop=120, treasury=250000, era=4, discovery='beton_zbrojony',
                       fblock='gray_concrete')
    if name == 'most':
        b, g = extra.most()
        return b, meta('most', 'Most', '#A9A9A9', 'Kamienny most na dwóch filarach z łukami, balustradą i latarniami.',
                       'bridge', 0, 5000, 1, 'infrastructure', 'water', 4, pop=10, ground=g, foundation='none')
    if name == 'kamieniolom':
        b, g = extra.kamieniolom()
        return b, meta('kamieniolom', 'Kamieniołom', '#A0A0A0', 'Tarasowy kamieniołom z dźwigiem i ogrodzeniem; dochód z kamienia. Najlepiej w skałach.',
                       'quarry', 0, 4500, 2, 'economy', 'mountain', 2, pop=12, ground=g, foundation='none')
    if name == 'kopalnia':
        b, g = economy.kopalnia()
        return b, meta('kopalnia', 'Kopalnia', '#8B8682', 'Szyb kopalni z wyciągiem; górnicy drążą chodniki w głąb (rozbudowa daje dochód).',
                       'mine', 0, 4000, 1, 'economy', 'mountain', 2, pop=10, ground=g, foundation='none')
    b, g = economy.magazyn()
    return b, meta('magazyn', 'Magazyn', '#CD853F', 'Magazyn z piwnicą pełną beczek. Duży przyrost pojemności spichlerza.',
                   'warehouse', 0, 6000, 2, 'food', 'outer', 2, pop=16, ground=g)


# templates deployed before material levels existed (their classic look must stay identical at level 1)
LEGACY = ['studnia', 'chata', 'dom', 'spichlerz', 'kuznia', 'koszary', 'wieza']

SLOTS = {
    'chata': {'oak_planks': 'wall', 'spruce_log': 'frame', 'spruce_planks': 'floor', 'cobblestone': 'base',
              'dark_oak_stairs': 'roof_stairs', 'glass_pane': 'window'},
    'dom': {'white_terracotta': 'wall', 'dark_oak_log': 'frame', 'cobblestone': 'base', 'spruce_planks': 'floor',
            'dark_oak_stairs': 'roof_stairs', 'dark_oak_slab': 'roof_slab', 'dark_oak_planks': 'roof_block', 'glass_pane': 'window'},
    'karczma': {'oak_planks': 'wall', 'dark_oak_log': 'frame', 'stone_bricks': 'base', 'cobblestone': 'base',
                'spruce_stairs': 'roof_stairs', 'spruce_slab': 'roof_slab', 'glass_pane': 'window'},
    'kamienica': {'white_terracotta': 'wall', 'dark_oak_log': 'frame', 'stone_bricks': 'base', 'cobblestone': 'base',
                  'dark_oak_stairs': 'roof_stairs', 'dark_oak_slab': 'roof_slab', 'dark_oak_planks': 'roof_block',
                  'glass_pane': 'window'},
    'stajnia': {'spruce_planks': 'wall', 'spruce_log': 'frame', 'spruce_stairs': 'roof_stairs', 'spruce_slab': 'roof_slab'},
    'spichlerz': {'spruce_planks': 'wall', 'spruce_log': 'frame', 'spruce_stairs': 'roof_stairs'},
    'mlyn': {'spruce_planks': 'wall', 'dark_oak_stairs': 'roof_stairs', 'dark_oak_slab': 'roof_slab', 'dark_oak_planks': 'roof_block',
             'glass_pane': 'window'},
    'kuznia': {'spruce_planks': 'wall', 'spruce_stairs': 'roof_stairs', 'spruce_slab': 'roof_slab'},
}


def main():
    lib.load_states(STATES)
    os.makedirs(OUT, exist_ok=True)
    preview = '--preview' in sys.argv
    if preview:
        os.makedirs(PREVIEW, exist_ok=True)
        import render
    legacy = None
    if '--legacy' in sys.argv:
        legacy = sys.argv[sys.argv.index('--legacy') + 1]
        os.makedirs(legacy, exist_ok=True)
    only_variants = '--only-variants' in sys.argv
    for name, b, m in (buildings() + [(n, f(), m) for (n, f, m) in variants.entries(meta)]
                       + [(n, f(), m) for (n, f, m) in workshops.entries(meta)]):
        if b is None:
            b, m = special(name)
        if only_variants and 'variant-of' not in m:
            continue  # base templates stay untouched (python generate.py without the flag rewrites them identically)
        if legacy and name in LEGACY:
            lib.write(legacy, name, b, m)
            continue
        if legacy:
            continue
        if name in SLOTS:
            lib.slotify(b, SLOTS[name])
        lib.write(OUT, name, b, m)
        print(f'{name:10s} {b.w}x{b.d}x{b.h}  palette {len(b.used())}')
        if preview:
            render.render(os.path.join(OUT, name + '.yml'), os.path.join(PREVIEW, name + '.png'), materials=b.materials)
            if name in ('dom', 'karczma'):
                # the same template at every settlement level (material palettes)
                for level in range(len(lib.LEVELS)):
                    render.render(os.path.join(OUT, name + '.yml'), os.path.join(PREVIEW, f'{name}-poziom-{level + 1}.png'),
                                  level=level, materials=b.materials)
            if 'variant-of' in m:
                # look variants: Wieś and Miasto of their epoch (renaissance variants in the renaissance palette)
                first = (m.get('requires', {}).get('era', 1) - 1) * 6
                render.render(os.path.join(OUT, name + '.yml'), os.path.join(PREVIEW, name + '.png'), level=first + 1,
                              materials=b.materials)
                render.render(os.path.join(OUT, name + '.yml'), os.path.join(PREVIEW, f'{name}-miasto.png'),
                              level=first + 3, materials=b.materials)


def write_texts():
    """src/main/resources/opisy.yml: the texts of every built-in template + the descriptions shipped before.

    A copy of a built-in template exported to plugins/Ludnosc/budynki/ overrides the jar, so an OLD exported copy would
    keep its old description for ever. TemplateTexts (Java) shows the current text whenever the file still carries one
    of the `previous` descriptions (= nobody edited it) and keeps a description an admin really changed.
    """
    import json
    with open(PREVIOUS, encoding='utf-8') as f:
        previous = json.load(f)
    lines = ['# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl',
             '# Wygenerowane przez templates-src/generate.py (opisy.py) — zmiany wprowadzaj w generatorze.',
             '# effect = krótki skutek na liście /ludnosc typy, description = pełny opis, look = zdanie o wyglądzie,',
             '# previous = opisy z wcześniejszych wersji (nieedytowana kopia w plugins/Ludnosc/budynki dostaje nowy opis).']
    for id_ in sorted(TEKSTY):
        effect, desc, look, base = TEKSTY[id_]
        lines.append(f'{id_}:')
        lines.append(f"  base: '{base}'")
        lines.append(f"  effect: '{effect}'")
        lines.append(f"  description: '{desc}'")
        lines.append(f"  look: '{look}'")
        old = [d for d in previous.get(id_, []) if d != desc]
        if old:
            lines.append('  previous:')
            for d in old:
                assert "'" not in d, id_
                lines.append(f"    - '{d}'")
    with open(TEXTS, 'w', encoding='utf-8', newline=chr(10)) as f:
        f.write(chr(10).join(lines) + chr(10))


if __name__ == '__main__':
    main()
    if '--only-variants' not in sys.argv and '--legacy' not in sys.argv:
        write_texts()
        print(f'opisy.yml: {len(TEKSTY)} szablonów')
