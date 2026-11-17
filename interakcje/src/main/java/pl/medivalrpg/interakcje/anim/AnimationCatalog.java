// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The animation catalogue ({@code bettermodel/animations.json}, written by
 * {@code animations-src/generate.py}): which clips exist in which limb model, how long they are,
 * when their impact moments happen, and how two-person animations are arranged. Pure data.
 */
public final class AnimationCatalog {
    public static final int TICKS_PER_SECOND = 20;
    public static final AnimationCatalog EMPTY = new AnimationCatalog(List.of(), Map.of(), Map.of(), Map.of());

    /** Who plays a role in an animation. */
    public enum Role {
        SENDER, TARGET;

        static Role parse(String raw) {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Type {
        PAIR, SOLO, POSE
    }

    /** How the participants are placed before the animation starts. */
    public enum Arrangement {
        /** Face to face at {@link Animation#distance()}. */
        FACE,
        /** Side by side (the target on the sender's right) at {@link Animation#distance()}. */
        SIDE,
        /** Solo: the sender turns towards the target (if any). */
        LOOK,
        /** Nothing is moved. */
        NONE
    }

    /**
     * One clip in a limb model.
     *
     * @param blendTicks how long to blend into the clip ({@code -1}: the {@code animations.blend-in-ticks} default);
     *                   loops that start far from standing (kneeling) use a longer one
     */
    public record Clip(String name, String model, int lengthTicks, boolean loop, List<Integer> impactTicks,
                       int blendTicks, Map<String, Prop> props) {

        public Clip(String name, String model, int lengthTicks, boolean loop, List<Integer> impactTicks, int blendTicks) {
            this(name, model, lengthTicks, loop, impactTicks, blendTicks, Map.of());
        }

        public Prop prop(String id) {
            return props.get(id);
        }
    }

    /**
     * A prop moved along with a clip (the crown of the coronation): points in the clip owner's model
     * space in pixels (16 px = 1 block at model scale; the character faces -Z, its right is +X).
     *
     * @param setTick the moment the prop leaves the hands (it is on a head from then on)
     */
    public record Prop(int setTick, List<PropPoint> points) {

        /** Linear interpolation at {@code tick} (clamped to the track). */
        public PropPoint at(double tick) {
            if (points.isEmpty()) {
                return null;
            }
            if (tick <= points.getFirst().tick()) {
                return points.getFirst();
            }
            for (int i = 1; i < points.size(); i++) {
                PropPoint b = points.get(i);
                if (tick <= b.tick()) {
                    PropPoint a = points.get(i - 1);
                    double u = b.tick() == a.tick() ? 1.0 : (tick - a.tick()) / (b.tick() - a.tick());
                    return new PropPoint(tick, a.x() + (b.x() - a.x()) * u, a.y() + (b.y() - a.y()) * u,
                            a.z() + (b.z() - a.z()) * u);
                }
            }
            return points.getLast();
        }

        public double firstTick() {
            return points.isEmpty() ? 0 : points.getFirst().tick();
        }
    }

    public record PropPoint(double tick, double x, double y, double z) {
    }

    /** @param lock moving or sneaking ends the animation for this participant */
    public record RoleClip(String clip, boolean lock) {
    }

    /**
     * @param mirror for {@link Arrangement#SIDE}: clips to use when the target ends up on the sender's
     *               left instead (empty when not supported)
     */
    public record Animation(String id, Type type, Arrangement arrangement, double distance,
                            Map<Role, RoleClip> roles, Map<Role, String> mirror) {

        public RoleClip role(Role role) {
            return roles.get(role);
        }

        public boolean pair() {
            return type == Type.PAIR;
        }
    }

    private final List<String> models;
    private final Map<String, Clip> clips;
    private final Map<String, Animation> animations;
    private final Map<String, String> work;

    private AnimationCatalog(List<String> models, Map<String, Clip> clips, Map<String, Animation> animations,
                             Map<String, String> work) {
        this.models = models;
        this.clips = clips;
        this.animations = animations;
        this.work = work;
    }

    /** NPC work animations: API name ({@code budowa}, {@code kilof}, ...) -> clip name. */
    public Map<String, String> work() {
        return work;
    }

    /**
     * Resolves a work animation name or a clip name to a clip of this catalogue.
     *
     * @return the clip, or {@code null} when unknown
     */
    public Clip workClip(String nameOrClip) {
        if (nameOrClip == null) {
            return null;
        }
        String key = nameOrClip.trim().toLowerCase(Locale.ROOT);
        String clip = work.get(key);
        return clips.get(clip != null ? clip : key);
    }

    public List<String> models() {
        return models;
    }

    public Map<String, Clip> clips() {
        return clips;
    }

    public Map<String, Animation> animations() {
        return animations;
    }

    public Animation animation(String id) {
        return id == null ? null : animations.get(id.toLowerCase(Locale.ROOT));
    }

    public Clip clip(String name) {
        return name == null ? null : clips.get(name);
    }

    public boolean isEmpty() {
        return animations.isEmpty();
    }

    /** Number of animations / clips, for logging. */
    public String summary() {
        return animations.size() + " animacji, " + clips.size() + " klipów, " + work.size() + " prac NPC, modele: "
                + String.join(", ", models);
    }

    // ------------------------------------------------------------------ parsing

    /** Parses one catalogue; problems are reported to {@code warn} and the broken entries skipped. */
    public static AnimationCatalog parse(Reader reader, Consumer<String> warn) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        return fromJson(root, warn);
    }

    /** {@code base} overlaid with {@code extra} (same ids / clip names replace the base ones). */
    public static AnimationCatalog merge(AnimationCatalog base, AnimationCatalog extra) {
        Set<String> models = new LinkedHashSet<>(base.models);
        models.addAll(extra.models);
        Map<String, Clip> clips = new LinkedHashMap<>(base.clips);
        clips.putAll(extra.clips);
        Map<String, Animation> animations = new LinkedHashMap<>(base.animations);
        animations.putAll(extra.animations);
        Map<String, String> work = new LinkedHashMap<>(base.work);
        work.putAll(extra.work);
        return new AnimationCatalog(List.copyOf(models), Collections.unmodifiableMap(clips),
                Collections.unmodifiableMap(animations), Collections.unmodifiableMap(work));
    }

    static AnimationCatalog fromJson(JsonObject root, Consumer<String> warn) {
        List<String> models = new ArrayList<>();
        if (root.has("models") && root.get("models").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("models")) {
                models.add(e.getAsString());
            }
        }
        Map<String, Clip> clips = new LinkedHashMap<>();
        JsonObject clipsJson = root.has("clips") ? root.getAsJsonObject("clips") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : clipsJson.entrySet()) {
            try {
                JsonObject c = e.getValue().getAsJsonObject();
                String model = c.get("model").getAsString();
                double length = c.get("length").getAsDouble();
                if (!(length > 0) || Double.isInfinite(length)) {
                    warn.accept("klip " + e.getKey() + ": niepoprawna długość – pomijam.");
                    continue;
                }
                List<Integer> impacts = new ArrayList<>();
                if (c.has("events") && c.getAsJsonObject("events").has("impact")) {
                    JsonArray arr = c.getAsJsonObject("events").getAsJsonArray("impact");
                    for (JsonElement t : arr) {
                        impacts.add(toTicks(t.getAsDouble()));
                    }
                }
                Collections.sort(impacts);
                int blend = c.has("blend") ? Math.max(0, toTicks(c.get("blend").getAsDouble())) : -1;
                Map<String, Prop> props = new LinkedHashMap<>();
                if (c.has("props")) {
                    for (Map.Entry<String, JsonElement> pe : c.getAsJsonObject("props").entrySet()) {
                        JsonObject po = pe.getValue().getAsJsonObject();
                        List<PropPoint> pts = new ArrayList<>();
                        for (JsonElement pt : po.getAsJsonArray("points")) {
                            JsonArray q = pt.getAsJsonArray();
                            pts.add(new PropPoint(q.get(0).getAsDouble() * TICKS_PER_SECOND, q.get(1).getAsDouble(),
                                    q.get(2).getAsDouble(), q.get(3).getAsDouble()));
                        }
                        if (!pts.isEmpty()) {
                            props.put(pe.getKey(), new Prop(toTicks(po.get("set").getAsDouble()), List.copyOf(pts)));
                        }
                    }
                }
                clips.put(e.getKey(), new Clip(e.getKey(), model, Math.max(1, toTicks(length)),
                        c.has("loop") && c.get("loop").getAsBoolean(), List.copyOf(impacts), blend,
                        Collections.unmodifiableMap(props)));
                if (!models.contains(model)) {
                    models.add(model);
                }
            } catch (RuntimeException ex) {
                warn.accept("klip " + e.getKey() + ": " + ex.getMessage() + " – pomijam.");
            }
        }
        Map<String, Animation> animations = new LinkedHashMap<>();
        JsonObject animJson = root.has("animations") ? root.getAsJsonObject("animations") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : animJson.entrySet()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            try {
                JsonObject a = e.getValue().getAsJsonObject();
                Type type = Type.valueOf(a.get("type").getAsString().trim().toUpperCase(Locale.ROOT));
                Arrangement arrangement = a.has("arrangement")
                        ? Arrangement.valueOf(a.get("arrangement").getAsString().trim().toUpperCase(Locale.ROOT))
                        : Arrangement.NONE;
                double distance = a.has("distance") ? a.get("distance").getAsDouble() : 0.0;
                Map<Role, RoleClip> roles = new EnumMap<>(Role.class);
                for (Map.Entry<String, JsonElement> r : a.getAsJsonObject("roles").entrySet()) {
                    JsonObject ro = r.getValue().getAsJsonObject();
                    String clip = ro.get("clip").getAsString();
                    if (!clips.containsKey(clip)) {
                        throw new IllegalArgumentException("nieznany klip " + clip);
                    }
                    roles.put(Role.parse(r.getKey()), new RoleClip(clip, !ro.has("lock") || ro.get("lock").getAsBoolean()));
                }
                if (!roles.containsKey(Role.SENDER)) {
                    throw new IllegalArgumentException("brak roli sender");
                }
                if (type == Type.PAIR && !roles.containsKey(Role.TARGET)) {
                    throw new IllegalArgumentException("animacja dwuosobowa bez roli target");
                }
                if (type == Type.PAIR && (arrangement == Arrangement.FACE || arrangement == Arrangement.SIDE)
                        && !(distance > 0.2 && distance < 4.0)) {
                    throw new IllegalArgumentException("odległość poza zakresem 0,2–4 bl.");
                }
                Map<Role, String> mirror = new EnumMap<>(Role.class);
                if (a.has("mirror")) {
                    for (Map.Entry<String, JsonElement> m : a.getAsJsonObject("mirror").entrySet()) {
                        String clip = m.getValue().getAsString();
                        if (!clips.containsKey(clip)) {
                            throw new IllegalArgumentException("nieznany klip lustrzany " + clip);
                        }
                        mirror.put(Role.parse(m.getKey()), clip);
                    }
                }
                animations.put(id, new Animation(id, type, arrangement, distance,
                        Collections.unmodifiableMap(roles), Collections.unmodifiableMap(mirror)));
            } catch (RuntimeException ex) {
                warn.accept("animacja " + id + ": " + ex.getMessage() + " – pomijam.");
            }
        }
        Map<String, String> work = new LinkedHashMap<>();
        if (root.has("work") && root.get("work").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("work").entrySet()) {
                String clip = e.getValue().getAsString();
                if (!clips.containsKey(clip)) {
                    warn.accept("praca " + e.getKey() + ": nieznany klip " + clip + " – pomijam.");
                    continue;
                }
                work.put(e.getKey().toLowerCase(Locale.ROOT), clip);
            }
        }
        return new AnimationCatalog(List.copyOf(models), Collections.unmodifiableMap(clips),
                Collections.unmodifiableMap(animations), Collections.unmodifiableMap(work));
    }

    static int toTicks(double seconds) {
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }
}
