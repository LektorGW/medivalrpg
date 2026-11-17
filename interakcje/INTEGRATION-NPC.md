<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Animacje pracy NPC — łatki integracyjne dla Ludnosc, Skarbiec i Armia

Interakcje udostępniają API `pl.medivalrpg.interakcje.api.WorkAnimations` (ServicesManager). Moduły **mówią, co NPC robi**
(`play(npc, "kilof", true)`), a Interakcje decydują, kiedy animację naprawdę pokazać: tylko gdy w promieniu
`npc-animations.range` (32 bl.) jest gracz z paczką zasobów, najwyżej `max-active` (40) NPC naraz (najbliżsi pierwsi),
w budżecie `budget-ms` na przegląd. Zapętlona praca zapisuje się w PDC encji (`interakcje:work`) i wraca sama po
restarcie / wczytaniu chunka. Usunięcie encji sprząta animację automatycznie (nie trzeba wołać `stop`).

Nazwy prac: `budowa`, `noszenie_plecy`, `noszenie_rece`, `kilof`, `motyka`, `siekiera`, `kowadlo`, `zaproszenie`,
`straz`, `odpoczynek` (pętle) oraz gesty jednorazowe `salut`, `ziewanie` (`loop = false`; po geście NPC wraca do pracy).
Przedmiot w ręce (kilof, siekiera, skrzynia) pochodzi z ekwipunku Mannequina — BetterModel pokazuje go w dłoni modelu.

Wymagania: tylko encje `Mannequin` (konfiguracja `entity-types`). Skin: z profilu Mannequina (`textures`, nazwa Mojang
pobierana raz i trzymana w pamięci; profile bez nazwy — losowe UUID żołnierzy/władców — dostają skin zapasowy
BetterModel bez zapytań do Mojang). Wszystkie wywołania: wątek główny.

## 1. Pomocnik (bez zależności kompilacji) — skopiuj do modułu

Każdy moduł dostaje własną kopię w swoim pakiecie (np. `pl.medivalrpg.ludnosc.integration.WorkAnim`). Do `plugin.yml`
modułu dopisać `Interakcje` w `softdepend` (dla Ludnosc i Armia; **Skarbiec** — patrz uwaga niżej). Bez Interakcji
wszystkie metody po cichu nic nie robią.

```java
// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.MODUL.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

/** Work animations of NPCs through the Interakcje plugin (reflection: no compile dependency). Main thread only. */
public final class WorkAnim {
    private static final String API = "pl.medivalrpg.interakcje.api.WorkAnimations";
    private static Object provider;
    private static Method play;
    private static Method stop;
    private static long nextLookup;

    private WorkAnim() {
    }

    private static boolean ready() {
        if (provider != null && Bukkit.getPluginManager().isPluginEnabled("Interakcje")) {
            return true;
        }
        provider = null;
        long now = System.currentTimeMillis();
        if (now < nextLookup) {
            return false;
        }
        nextLookup = now + 30_000L; // Interakcje missing: try again in 30 s, not on every call
        try {
            for (Class<?> k : Bukkit.getServicesManager().getKnownServices()) {
                if (k.getName().equals(API)) {
                    Object p = Bukkit.getServicesManager().load(k);
                    if (p != null) {
                        play = k.getMethod("play", Entity.class, String.class, boolean.class);
                        stop = k.getMethod("stop", Entity.class);
                        provider = p;
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // older Interakcje without the API
        }
        return false;
    }

    /** The NPC does this (loop = work state, stored on the entity; false = one-off gesture). */
    public static void play(Entity npc, String work, boolean loop) {
        if (npc != null && ready()) {
            try {
                play.invoke(provider, npc, work, loop);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                provider = null;
            }
        }
    }

    /** The NPC stops working (also forgets the stored state). */
    public static void stop(Entity npc) {
        if (npc != null && ready()) {
            try {
                stop.invoke(provider, npc);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                provider = null;
            }
        }
    }
}
```

Ścieżkę refleksji (`getKnownServices` → `load` → `play`/`stop`) sprawdził test Paper (`verify-20261029/README.md`).
Można też kompilować przeciw jarowi Interakcji (`compileOnly(files("../interakcje/build/.../Interakcje-1.0.0.jar"))`)
i używać interfejsu wprost — wtedy obowiązkowe `softdepend: [Interakcje]` i sprawdzenie `null` z `getRegistration`.

## 2. Ludnosc — `service/BuilderCrew.java` (jedno miejsce dla wszystkich ekip)

Wszyscy widoczni pracownicy Ludności (budowniczowie `ConstructionService`, tragarze `LogisticsService`, górnicy
`MineService`, drwal / rolnik / kamieniarz / rybak `ResourceService`, kowal i rzemieślnicy `WorkshopService`) to
`Body` w `BuilderCrew`, tworzone przez `ensure(plugin, site, spawn, wanted, description)`, gdzie `description` niesie rolę.
Łatka: zapamiętać rolę, a w `move(step)` (co 2 ticki) ustawiać pracę **tylko przy zmianie stanu** (idzie / stoi).

```diff
--- a/ludnosc/src/main/java/pl/medivalrpg/ludnosc/service/BuilderCrew.java
+++ b/ludnosc/src/main/java/pl/medivalrpg/ludnosc/service/BuilderCrew.java
@@ class Body
+        /** Work animation shown now (null = none yet); changed only on a state change. */
+        String anim;
@@ void ensure(final LudnoscPlugin plugin, final UUID site, final Location spawn, final int wanted, final String description) {
         this.prune();
+        this.role = description;
         while (this.bodies.size() < wanted) {
@@ class BuilderCrew (fields)
+    private String role = "";
+
+    /** Work of the role while standing at the work spot / while walking. */
+    static String workAnim(final String role, final boolean walking) {
+        final String r = role == null ? "" : role.toLowerCase(java.util.Locale.ROOT);
+        if (r.startsWith("tragarz")) return walking ? "noszenie_plecy" : "odpoczynek";
+        if (walking) return "noszenie_rece";
+        if (r.startsWith("górnik") || r.startsWith("kamieniarz")) return "kilof";
+        if (r.startsWith("drwal")) return "siekiera";
+        if (r.startsWith("rolnik")) return "motyka";
+        if (r.startsWith("kowal")) return "kowadlo";
+        if (r.startsWith("rybak")) return "odpoczynek";
+        return "budowa"; // builders: description = building type
+    }
@@ void move(final double step) {
         for (final Body b : this.bodies) {
             final Entity e = b.entity();
             if (e == null || b.target == null || !b.target.getWorld().equals(b.pos.getWorld())) {
+                if (e != null && b.anim == null) {
+                    b.anim = workAnim(this.role, false);
+                    WorkAnim.play(e, b.anim, true);
+                }
                 continue;
             }
@@ (end of the loop body, after "b.pos = next; e.teleport(next);")
+            final String want = workAnim(this.role, !next.equals(previous));   // previous = b.pos before this step
+            if (!want.equals(b.anim)) {
+                b.anim = want;
+                WorkAnim.play(e, want, true);
+            }
```

Uwagi: (a) `previous` = kopia `b.pos` sprzed obliczenia `next` (dopisać `final Location previous = b.pos;` na początku
pętli); (b) zima — `ResourceService` wstrzymuje rolników: wtedy `WorkAnim.play(body, "odpoczynek", true)`, a raz na
kilka minut `WorkAnim.play(body, "ziewanie", false)`; (c) nic nie trzeba robić przy usuwaniu ciał (`prune`, `remove`):
Interakcje sprzątają same; (d) wariant `builderType: VILLAGER` nie jest animowany (tylko Mannequin).

## 3. Skarbiec — `npc/NpcManager.java`

Kupiec i bankier to trwałe Mannequiny; kliknięcia obsługuje osobny `Interaction` (hitbox), więc ukrycie ciała przez
model BetterModel nie wpływa na GUI. Praca zapisuje się w PDC, więc wystarczy ustawić ją przy spawnie oraz raz dla
NPC, które już stoją (w `respawnMissing` — wywołanie jest idempotentne i tanie).

```diff
--- a/skarbiec/src/main/java/pl/medivalrpg/skarbiec/npc/NpcManager.java
+++ b/skarbiec/src/main/java/pl/medivalrpg/skarbiec/npc/NpcManager.java
@@ private void spawn(final NpcRecord r) {
         r.entity = m.getUniqueId();
         this.save();
         this.spawnHitbox(r);
+        WorkAnim.play(m, r.type == NpcType.MERCHANT ? "zaproszenie" : "odpoczynek", true);
     }
@@ private void respawnMissing(final Chunk only) {
-            if (this.entity(r) == null) {
+            final Entity existing = this.entity(r);
+            if (existing == null) {
                 this.spawn(r); // also spawns a hitbox
-            } else if (this.hitbox(r) == null) {
-                this.spawnHitbox(r);
+            } else {
+                if (this.hitbox(r) == null) {
+                    this.spawnHitbox(r);
+                }
+                WorkAnim.play(existing, r.type == NpcType.MERCHANT ? "zaproszenie" : "odpoczynek", true);
             }
@@ usuwanie NPC (komenda /skarbiec npc usun)
+        WorkAnim.stop(entity);   // przed entity.remove() — opcjonalnie, sprzątanie i tak zadziała
```

**Uwaga o softdepend:** w Skarbcu nie dopisywać `Interakcje` do `softdepend` (Interakcje nie zależą od Skarbca, więc
cyklu nie ma — ale moduł świadomie ogranicza softdepend). `WorkAnim` szuka API leniwie przy pierwszym wywołaniu i co
30 s, więc kolejność ładowania nie ma znaczenia: NPC, którym `play` nie zadziałało przy starcie, dostaną pracę przy
najbliższym `respawnMissing` (np. wczytanie chunka).

## 4. Armia — `soldier/SoldierController.java` i `ruler/RulerService.java`

Żołnierz w trybie Mannequin = niewidzialny „mózg” (Mob) + ciało (Mannequin) przesuwane teleportem w `syncBody(Soldier)`.
Animację straży pokazujemy tylko, gdy żołnierz **stoi na posterunku** (`SoldierMode.GUARD`, ciało nie przesunęło się
od 1 s, brak celu ataku); w ruchu, walce i na koniu — bez animacji (zwykły Mannequin z ruchem kończyn vanilla).

```diff
--- a/armia/src/main/java/pl/medivalrpg/armia/soldier/SoldierController.java
+++ b/armia/src/main/java/pl/medivalrpg/armia/soldier/SoldierController.java
@@ class Soldier (pola stanu w pamięci, nie zapisywane)
+    String anim;            // pokazywana praca ciała
+    int stillSinceTick;     // od kiedy ciało stoi
@@ private void syncBody(final Soldier s) {
         final Location cl = body.getLocation();
         if (cl.getWorld() != bl.getWorld() || cl.distanceSquared(bl) > 1.0E-4
             || Math.abs(cl.getYaw() - bl.getYaw()) > 0.5f || Math.abs(cl.getPitch() - bl.getPitch()) > 0.5f) {
             body.teleport(bl);
+            if (cl.getWorld() != bl.getWorld() || cl.distanceSquared(bl) > 0.0025) {
+                s.stillSinceTick = this.tick;   // naprawdę idzie (nie tylko obrót głowy)
+            }
         }
+        final boolean onDuty = s.record().mode == SoldierMode.GUARD && b.getTarget() == null
+            && this.tick - s.stillSinceTick >= 20;
+        final String want = onDuty ? "straz" : null;
+        if (!java.util.Objects.equals(want, s.anim)) {
+            s.anim = want;
+            if (want == null) {
+                WorkAnim.stop(body);
+            } else {
+                WorkAnim.play(body, want, true);
+            }
+        }
     }
```

Opcjonalnie: salut przy podejściu władcy własnego kraju — w miejscu, gdzie Armia wykrywa gracza w pobliżu posterunku:
`WorkAnim.play(s.body, "salut", false)` (gest gra tylko, gdy ktoś go widzi; potem wraca `straz`).

Władca NPC (`RulerService`, po `spawn(... Mannequin.class ...)`): `WorkAnim.play(m, "straz", true)` — dostojna postawa
z dłonią na rękojeści. Załoga statków (`naval/FleetVisuals`): bez animacji (ciała przesuwane z łodzią co tick).

## 5. Wydajność i ryzyka (do decyzji koordynatora)

- Pierwszy tracker po starcie serwera kosztuje BetterModel jednorazowo 0,19–0,74 s (rozgrzewka JIT/klas; pomiary
  z kilku przebiegów izolowanego Papera). Każdy następny start modelu NPC to średnio ~6–11 ms, maks. 12–25 ms na
  głównym wątku (tworzenie ~16 encji display dla każdego widza), zatrzymanie ~1 ms. Dlatego domyślnie
  `max-starts-per-scan: 1` co 10 ticków i budżet 2 ms z przewidywaniem kosztu: po rozgrzewce najdłuższy przegląd
  trwał 9,8 ms (jeden start). 40 NPC zapełnia się w ~20 s od podejścia gracza, bez skoków większych niż jeden start.
- Koszt po stronie klienta: każdy animowany NPC to ~16 encji display u każdego widza w zasięgu; `max-active: 40`
  ogranicza to globalnie. Przy słabszych klientach zmniejszyć do 20–25 albo `range` do 24.
- Animacja na ruchomym ciele (tragarz, budowniczy w drodze) nie ma cyklu chodu nóg — nogi stoją, ciało sunie. Dlatego
  łatki pokazują „noszenie” (ręce zajęte, tułów pochylony), a nie chodzenie.
- Mannequin pod modelem jest ukrywany pakietami tylko dla widzów modelu; gracze bez paczki widzą zwykłego Mannequina.
