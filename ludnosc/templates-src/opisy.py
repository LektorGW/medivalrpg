# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Player-facing texts of every building: what it DOES first, what it needs, then one short clause about its look.

One format for all templates (decision of the owner, 2026-11-11):
    <co daje / co robią pracownicy, z liczbami>. <czego potrzebuje>. <wygląd>.
The functional part (FUNKCJE) is keyed by the BASE type, so every look variant says exactly the same about the
gameplay; only WYGLAD differs. Numbers are the jar defaults and are checked against the code by
`BuildingTextsTest` (tax / growth / granary bonuses = LudnoscConfig.Bonus.DEFAULTS, food and workshop values =
config.yml). Dead values are NOT mentioned: `bonus.food`, mine income and virtual farm production do nothing since
the physical economy (EconomyService.input, MineService.income).

{lozka} / {prycze} / {n} are filled with the real bed count of the template.
"""

WZROST = 'szansa na nowego osadnika +{g}% w każdym cyklu wzrostu'
NIE_MIESZKANIA = 'nie liczą się jako mieszkania'
CHRONIONY = 'przebudowa miasta nigdy go nie wyburza'


def lozka(n):
    if n == 1:
        return '1 łóżko'
    if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
        return f'{n} łóżka'
    return f'{n} łóżek'


def prycze(n):
    if n == 1:
        return '1 prycza'
    if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
        return f'{n} prycze'
    return f'{n} prycz'


MIESZKANIE = ('dom dla {n} obywateli',
              'Mieszkanie dla {n} obywateli ({lozka}); bez wolnych łóżek nie przybywa osadników.', '')

# base type -> (krótki efekt do listy /ludnosc typy, co daje, czego potrzebuje)
FUNKCJE = {
    # ---- mieszkania
    'chata': MIESZKANIE, 'dom': MIESZKANIE, 'kamienica': MIESZKANIE, 'kamienica_renesansowa': MIESZKANIE,
    'kamienica_czynszowa': MIESZKANIE, 'blok': MIESZKANIE, 'wiezowiec': MIESZKANIE,
    # ---- 17 budynków krajów graczy
    'kopalnia': ('bruk, węgiel i rudy do skrzyni',
                 'Górnicy drążą chodnik w głąb i znoszą do skrzyni kopalni bruk, węgiel i rudy (bez dochodu w monetach).',
                 'Potrzebuje górników (2 miejsca pracy na poziom kopalni) i pochodni w skrzyni — bez światła praca staje.'),
    'kamieniolom': ('do 32 bloków kamienia dziennie, podatki +4%',
                    'Kamieniarz wycina z wyrobiska pod budynkiem do 32 bloków kamienia dziennie (do skrzyni); podatki +4%.',
                    'Stawiaj na skale: złoże jest skończone (wyrobisko ok. 9×9, do 7 tarasów).'),
    'tartak': ('kłody z drzew w promieniu 28 bloków, podatki +5%',
               'Drwal ścina drzewa w promieniu 28 bloków na ziemi kraju, sadzi sadzonki i składa kłody do skrzyni tartaku; '
               'podatki +5%.',
               'Potrzebuje lasu w zasięgu.'),
    'pole': ('plony (jedzenie) do spichlerza lub magazynu',
             'Rolnicy (2 miejsca pracy) zbierają i od razu obsiewają pszenicę, marchew i ziemniaki; plony karmią ludność.',
             'Nie ma własnej skrzyni — plony trafiają do spichlerza albo magazynu; zimą prawie nie rodzi.'),
    'przystan': ('ryby do skrzyni, podatki +3%',
                 'Rybacy (2 miejsca pracy) łowią dorsze i łososie do skrzyni przystani — do 6 ryb dziennie na rybaka; '
                 'podatki +3%.',
                 'Łowią za dnia, gdy gracz jest w promieniu 64 bloków; stawiaj nad wodą.'),
    'huta_szkla': ('szkło z piasku na budowy',
                   'Hutnik kopie piasek w promieniu 24 bloków (do 48 dziennie) i wytapia szkło do skrzyni huty — dla budów.',
                   'Potrzebuje piasku, paliwa (węgiel, węgiel drzewny albo kłody) i gracza w promieniu 96 bloków.'),
    'cegielnia': ('cegły i terakota z gliny na budowy',
                  'Ceglarz kopie glinę w promieniu 24 bloków (do 24 dziennie) i wypala cegły oraz terakotę do skrzyni '
                  'cegielni — dla budów.',
                  'Potrzebuje gliny (także spod wody), paliwa (węgiel, węgiel drzewny albo kłody) i gracza w promieniu '
                  '96 bloków.'),
    'owczarnia': ('wełna i bele siana na budowy',
                  'Stado do 4 owiec daje wełnę, a pasterz wiąże bele siana (9 pszenicy → 1 bela) — do skrzyni owczarni, '
                  'dla budów.',
                  'Potrzebuje pszenicy z pól (jagnię kosztuje 2 pszenice) i gracza w promieniu 96 bloków.'),
    'kuznia': ('sztabki żelaza i pochodnie dla kopalni',
               'Kowal przetapia surowe żelazo na sztabki i robi pochodnie dla kopalni (1 kłoda + 4 węgla → 16 pochodni); '
               'daje pracę kowalom.',
               'Potrzebuje surowego żelaza, węgla i kłód (donoszą tragarze) oraz gracza w promieniu 96 bloków.'),
    'spichlerz': ('pojemność spichlerza +2000',
                  'Zwiększa pojemność spichlerza kraju o 2000 pkt jedzenia; jedzenie włożone do jego skrzyń i beczek karmi '
                  'ludność (obywatel zjada 4 pkt dziennie).',
                  'Przyjmuje też plony z pól i ryby z przystani.'),
    'magazyn': ('skład materiałów kraju, pojemność spichlerza +300',
                'Skład kraju: tragarze znoszą tu urobek z pełnych skrzyń kopalni, tartaku, kamieniołomu i zakładów, a budowy '
                'biorą stąd materiały; pojemność spichlerza +300.',
                'Liczy się każda skrzynia i beczka w budynku — towar możesz wkładać sam.'),
    'studnia': ('lepszy nastrój, woda do gaszenia pożarów',
                'Poprawia nastrój mieszkańców, jest miejscem wieczornych spotkań i źródłem wody dla ekip gaszących pożary.',
                'Nie potrzebuje pracowników.'),
    'koszary': ('limit żołnierzy +{n}',
                'Każda prycza podnosi limit żołnierzy kraju o 1 ({prycze} = +{n}); rekruci stawiają się przy koszarach, a '
                'żołnierze stojący w pobliżu powoli się leczą.',
                'Prycze ' + NIE_MIESZKANIA + '.'),
    'koszary2': ('limit żołnierzy +{n}',
                 'Każda prycza podnosi limit żołnierzy kraju o 1 ({prycze} = +{n}); rekruci stawiają się przy koszarach, a '
                 'żołnierze stojący w pobliżu powoli się leczą.',
                 'Prycze ' + NIE_MIESZKANIA + '.'),
    'ratusz': ('podatki +8%',
               'Podatki od obywateli +8% i praca dla kartografów; ' + CHRONIONY + '.', ''),
    'karczma': ('podatki +5%, osadnicy +10%, lepszy nastrój',
                'Podatki +5%, ' + WZROST.format(g=10) + ' i lepszy nastrój — mieszkańcy schodzą się tu wieczorami.',
                'Łóżka dla podróżnych ({n}) ' + NIE_MIESZKANIA + '.'),
    'kosciol': ('osadnicy +20%, lepszy nastrój',
                WZROST.format(g=20).capitalize() + ', lepszy nastrój (niedzielna msza) i praca dla kapłanów; '
                + CHRONIONY + '.', ''),
    # ---- pozostałe (średniowiecze i renesans)
    'wieza': ('posterunek straży, bez premii',
              'Budowla obronna: posterunek dla strażników; nie daje premii do podatków ani wzrostu.', ''),
    'mlyn': ('praca dla rolników',
             'Daje pracę rolnikom (młynarzom); sam nie zwiększa zapasu jedzenia.', ''),
    'stajnia': ('praca dla garbarzy i pasterzy',
                'Daje pracę garbarzom i pasterzom; nie daje premii do podatków ani wzrostu.', ''),
    'mur': ('odcinek murów, bez premii',
            'Odcinek murów miejskich z basztą; nie daje premii do podatków ani wzrostu.', ''),
    'brama': ('przejście w murach, bez premii',
              'Przejście w murach miejskich; nie daje premii do podatków ani wzrostu.', ''),
    'targ': ('podatki +10%, lepszy nastrój',
             'Podatki +10%, lepszy nastrój (mieszkańcy przychodzą tu w południe) i praca dla rzeźników i garbarzy.', ''),
    'piekarnia': ('praca dla rolników i rzeźników',
                  'Daje pracę rolnikom i rzeźnikom (piekarzom); sama nie zwiększa zapasu jedzenia.', ''),
    'browar': ('podatki +4%, osadnicy +10%',
               'Podatki +4%, ' + WZROST.format(g=10) + ' i lepszy nastrój (liczy się jak karczma); praca dla rolników.', ''),
    'biblioteka': ('podatki +2%, osadnicy +15%, badania',
                   'Podatki +2%, ' + WZROST.format(g=15) + ' i punkty badań (Postęp); praca dla bibliotekarzy i '
                   'kartografów.', ''),
    'zielarnia': ('osadnicy +25%, lepszy nastrój',
                  WZROST.format(g=25).capitalize() + ' i lepszy nastrój; praca dla kapłanów (zielarzy).', ''),
    'cmentarz': ('osadnicy +5%',
                 WZROST.format(g=5).capitalize() + '; ' + CHRONIONY + '.', ''),
    'pomnik': ('podatki +3%', 'Podatki +3%; ' + CHRONIONY + '.', ''),
    'laznia': ('osadnicy +15%, lepszy nastrój', WZROST.format(g=15).capitalize() + ' i lepszy nastrój.', ''),
    'latarnia': ('podatki +3%', 'Podatki +3%; nie zwiększa połowów.', 'Stawiaj nad wodą.'),
    'rynek': ('podatki +6%, osadnicy +5%, lepszy nastrój',
              'Podatki +6%, ' + WZROST.format(g=5) + ' i lepszy nastrój; plac festynów i spotkań.', ''),
    'ogrody': ('podatki +3%, osadnicy +10%',
               'Podatki +3%, ' + WZROST.format(g=10) + ' i lepszy nastrój (od renesansu).', ''),
    'akademia': ('punkty badań', 'Daje punkty badań (Postęp) i pracę bibliotekarzom; bez premii do podatków.', ''),
    'skarbiec': ('podatki +8%', 'Podatki +8%.', ''),
    'katedra': ('podatki +2%, osadnicy +30%, badania',
                'Podatki +2%, ' + WZROST.format(g=30) + ' i punkty badań (Postęp); liczy się jak kościół.', ''),
    'arena': ('podatki +4%, osadnicy +5%',
              'Podatki +4%, ' + WZROST.format(g=5) + ' i lepszy nastrój (od renesansu).', ''),
    'most': ('podatki +2%, przeprawa', 'Przeprawa przez wodę; podatki +2%.', 'Stawiany nad wodą.'),
    'zamek': ('podatki +10%, badania',
              'Podatki +10% i punkty badań (Postęp).', 'Łóżka ({n}) ' + NIE_MIESZKANIA + '.'),
    'palac': ('podatki +15%, badania',
              'Podatki +15% i punkty badań (Postęp).', 'Łóżka ({n}) ' + NIE_MIESZKANIA + '.'),
    'palac_renesansowy': ('podatki +12%, osadnicy +5%, badania',
                          'Podatki +12%, ' + WZROST.format(g=5) + ' i punkty badań (Postęp).',
                          'Łóżka ({n}) ' + NIE_MIESZKANIA + '.'),
    'ratusz_renesansowy': ('podatki +10%, badania', 'Podatki +10% i punkty badań (Postęp).', ''),
    'uniwersytet': ('dużo punktów badań',
                    'Daje najwięcej punktów badań (Postęp) i pracę bibliotekarzom i kartografom; bez premii do podatków.', ''),
    'drukarnia': ('podatki +3%, osadnicy +5%, badania',
                  'Podatki +3%, ' + WZROST.format(g=5) + ' i punkty badań (Postęp); praca dla kartografów.', ''),
    'port': ('podatki +8%, badania', 'Podatki +8% i punkty badań (Postęp); nie zwiększa połowów.', 'Stawiany nad wodą.'),
    'bank': ('podatki +10%, badania', 'Podatki +10% i punkty badań (Postęp).', ''),
    'obserwatorium': ('punkty badań', 'Daje punkty badań (Postęp); bez premii do podatków.', ''),
    # ---- epoki 3–4 (wyłączone: maksymalna epoka serwera to renesans)
    'fabryka': ('podatki +10%, badania', 'Podatki +10% i punkty badań (Postęp).', ''),
    'dworzec': ('podatki +6%, osadnicy +5%', 'Podatki +6%, ' + WZROST.format(g=5) + ' i punkty badań (Postęp).', ''),
    'wiezowiec_biurowy': ('podatki +15%', 'Podatki +15%.', 'Tylko na płaskiej działce.'),
    'szkola': ('podatki +2%, osadnicy +10%, badania',
               'Podatki +2%, ' + WZROST.format(g=10) + ' i punkty badań (Postęp); praca dla bibliotekarzy.', ''),
    'szpital': ('osadnicy +30%', WZROST.format(g=30).capitalize() + ' i punkty badań (Postęp).',
                'Łóżka dla chorych ({n}) ' + NIE_MIESZKANIA + '.'),
    'most_stalowy': ('przeprawa, bez premii', 'Przeprawa przez wodę; nie daje premii do podatków ani wzrostu.',
                     'Stawiany nad wodą.'),
    'elektrownia': ('podatki +12%', 'Podatki +12%.', ''),
    'park': ('osadnicy +15%', WZROST.format(g=15).capitalize() + '.', ''),
    'galeria': ('podatki +18%', 'Podatki +18%.', ''),
    'szpital_nowoczesny': ('osadnicy +45%', WZROST.format(g=45).capitalize() + ' i punkty badań (Postęp).',
                           'Łóżka dla chorych ({n}) ' + NIE_MIESZKANIA + '.'),
    'szkola_nowoczesna': ('podatki +3%, osadnicy +15%, badania',
                          'Podatki +3%, ' + WZROST.format(g=15) + ' i punkty badań (Postęp).', ''),
    'stadion': ('podatki +6%, osadnicy +10%', 'Podatki +6% i ' + WZROST.format(g=10) + '.', ''),
    'parlament': ('podatki +12%, osadnicy +5%', 'Podatki +12% i ' + WZROST.format(g=5) + '.', ''),
    'dworzec_nowoczesny': ('podatki +10%, osadnicy +5%',
                           'Podatki +10%, ' + WZROST.format(g=5) + ' i punkty badań (Postęp).', ''),
    'parking': ('podatki +3%', 'Podatki +3%.', ''),
    'laboratorium': ('dużo punktów badań', 'Daje bardzo dużo punktów badań (Postęp); bez premii do podatków.', ''),
    'lotnisko': ('podatki +15%, osadnicy +5%', 'Podatki +15% i ' + WZROST.format(g=5) + '.', ''),
}

# template id -> jedno krótkie zdanie o wyglądzie
WYGLAD = {
    'studnia': 'Kamienna studnia z daszkiem.',
    'chata': 'Mała drewniana chata na kamiennej podmurówce.',
    'chata_szachulec': 'Niska chata z muru pruskiego z okiennicami i kominem.',
    'chata_zrebowa': 'Chata z bali z gankiem na słupach.',
    'dom': 'Piętrowy dom z muru pruskiego.',
    'dom_szachulec': 'Dom z muru pruskiego z wysuniętym piętrem i lukarną.',
    'dom_kamienny': 'Murowany dom z okapem i dużym kominem.',
    'spichlerz': 'Drewniany spichlerz ze skrzyniami na zapasy.',
    'spichlerz_kamienny': 'Murowany spichlerz ze szkarpami i belką wyciągu.',
    'kuznia': 'Otwarta kuźnia z paleniskiem, kowadłem i kominem.',
    'kuznia_kamienna': 'Murowana kuźnia z zadaszonym placem i kowadłem.',
    'koszary': 'Kamienne koszary.',
    'koszary2': 'Dwupiętrowe kamienne koszary ze zbrojownią i blankami.',
    'wieza': 'Kamienna wieża z platformą obserwacyjną i blankami.',
    'wieza_okragla': 'Okrągła baszta ze strzelnicami i stożkowym dachem.',
    'karczma': 'Karczma z szynkiem i kominkiem.',
    'karczma_zajazd': 'Przydrożny zajazd z galerią na słupach i koniowiązem.',
    'mlyn': 'Wiatrak z żarnami w środku.',
    'stajnia': 'Drewniana stajnia z boksami i korytami.',
    'stajnia_kamienna': 'Murowana stajnia ze stryszkiem na siano.',
    'mur': 'Mur z blankami.',
    'pole': 'Ogrodzone pole z kanałem wody.',
    'targ': 'Brukowany plac ze straganami w barwach kraju.',
    'kamienica': 'Trzykondygnacyjna kamienica ze sklepem na parterze.',
    'kamienica_szczytowa': 'Kamienica ze schodkowym szczytem i kramem pod markizą.',
    'kamienica_podcieniowa': 'Kamienica z podcieniami na kamiennych filarach.',
    'ratusz': 'Ratusz z wieżą zegarową i salą rady.',
    'kosciol': 'Kamienny kościół z dzwonnicą i witrażami.',
    'kosciol_drewniany': 'Drewniany kościół z bali z wieżą-dzwonnicą.',
    'brama': 'Brama z dwiema basztami i broną.',
    'piekarnia': 'Piekarnia z ceglanym piecem i kominem.',
    'piekarnia_szachulec': 'Piekarnia z muru pruskiego z okienkiem sprzedaży.',
    'browar': 'Browar z kadziami i beczkami.',
    'tartak': 'Zadaszony tartak z piłą i stosami bali.',
    'tartak_kryty': 'Kryta hala tartaku na kamiennych stopach.',
    'kopalnia': 'Szyb z wyciągiem.',
    'biblioteka': 'Biblioteka z regałami i pulpitami.',
    'zielarnia': 'Chata zielarza z ogródkiem ziół.',
    'przystan': 'Chata rybacka z pomostem.',
    'cmentarz': 'Ogrodzony cmentarz z nagrobkami.',
    'pomnik': 'Pomnik władcy na cokole w barwach kraju.',
    'magazyn': 'Magazyn z piwnicą pełną beczek.',
    'laznia': 'Łaźnia z basenem i ławami.',
    'latarnia': 'Pasiasta latarnia morska z galerią.',
    'rynek': 'Duży brukowany rynek z fontanną i kramami.',
    'ogrody': 'Żywopłoty, kwietniki, staw i altana.',
    'akademia': 'Sala wykładowa i plac ćwiczeń rycerskich.',
    'skarbiec': 'Grubościenny skarbiec z kratami.',
    'katedra': 'Gotycka katedra z dwiema wieżami; pod nią katakumby.',
    'arena': 'Amfiteatr z piaszczystą areną i trybunami.',
    'most': 'Kamienny most z łukami i latarniami.',
    'kamieniolom': 'Tarasowy kamieniołom z dźwigiem.',
    'kamienica_renesansowa': 'Tynkowana kamienica z podcieniami i attyką.',
    'kamienica_renesansowa_loggia': 'Renesansowa kamienica z arkadową loggią.',
    'kamienica_czynszowa': 'Pięciokondygnacyjna ceglana kamienica z balkonami.',
    'fabryka': 'Ceglana hala z dachem szedowym i dwoma kominami.',
    'dworzec': 'Ceglany dworzec z halą i peronem pod wiatą.',
    'blok': 'Sześciopiętrowy blok z betonu.',
    'wiezowiec_biurowy': 'Szklany biurowiec (ok. 45 bloków).',
    'wiezowiec': 'Wieżowiec mieszkalny (ok. 65 bloków); tylko na płaskiej działce.',
    'uniwersytet': 'Kolegium z salami wykładowymi i portykiem.',
    'drukarnia': 'Warsztat z prasami drukarskimi.',
    'port': 'Port z molo na palach i dźwigiem.',
    'bank': 'Bank z kolumnowym portykiem i skarbcem.',
    'obserwatorium': 'Wieża z kopułą i lunetą.',
    'ratusz_renesansowy': 'Tynkowany ratusz z attyką i wieżą zegarową.',
    'palac_renesansowy': 'Czteroskrzydłowy pałac z arkadowym dziedzińcem.',
    'szkola': 'Ceglana szkoła z dwiema klasami.',
    'szpital': 'Ceglany szpital z salami chorych i apteką.',
    'most_stalowy': 'Stalowy most kratownicowy.',
    'elektrownia': 'Elektrownia z kotłami i dwoma kominami.',
    'park': 'Ogrodzony park z alejkami i stawem.',
    'galeria': 'Dwupoziomowa galeria ze szklaną fasadą.',
    'szpital_nowoczesny': 'Trzypiętrowy szpital z lądowiskiem.',
    'szkola_nowoczesna': 'Betonowa szkoła z dużymi oknami.',
    'stadion': 'Stadion z boiskiem i trybunami.',
    'parlament': 'Gmach z kolumnadą i szklaną kopułą.',
    'dworzec_nowoczesny': 'Przeszklony dworzec z peronem.',
    'parking': 'Asfaltowy parking z latarniami.',
    'laboratorium': 'Laboratorium z aparaturą.',
    'lotnisko': 'Pas startowy, terminal i wieża kontroli lotów.',
    'zamek': 'Warowny zamek z czterema wieżami, bramą i donżonem.',
    'palac': 'Pałac z kolumnadą, ogrodem i salą tronową.',
    'huta_szkla': 'Huta z kopułowym piecem szklarskim.',
    'huta_szkla_murowana': 'Murowana huta ze szkarpami i kominem.',
    'cegielnia': 'Cegielnia z piecem komorowym i wiatą suszarni.',
    'cegielnia_kryta': 'Kryta suszarnia z muru pruskiego i ceglany piec.',
    'owczarnia': 'Szopa z krosnem i zagroda dla owiec.',
    'owczarnia_kamienna': 'Kamienna owczarnia ze stryszkiem i zagrodą.',
}


def _fill(text, beds):
    return text.replace('{lozka}', lozka(beds)).replace('{prycze}', prycze(beds)).replace('{n}', str(beds))


def teksty(id_, base, beds):
    """(effect, description, look) of a template; `base` = variant-of or the id itself."""
    effect, gives, needs = FUNKCJE[base]
    look = WYGLAD[id_]
    parts = [_fill(gives, beds)]
    if needs:
        parts.append(_fill(needs, beds))
    parts.append(look)
    return _fill(effect, beds), ' '.join(parts), look
