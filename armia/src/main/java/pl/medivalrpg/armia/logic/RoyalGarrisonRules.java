// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import com.google.gson.*;
import java.util.*;

/** Strict, deterministic planning for the explicitly authorised one-country royal rebuild macro. */
public final class RoyalGarrisonRules {
    public static final UUID COUNTRY=UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66");
    private static final String POSTS="posts",SIEGE_POSTS="siegePosts";
    public record Post(String id,String type,double x,double y,double z,double radius,float yaw) { }
    public record Request(UUID country,String world,List<Post> posts,List<Post> siegePosts) {
        public Request(final UUID country,final String world,final List<Post> posts) {this(country,world,posts,List.of());}
    }
    public record Candidate(UUID id,UUID country,String type,int era,boolean mounted,boolean artillery,
                            boolean embarked,boolean mercenary,String postId) { }
    public record Assignment(Post post,UUID soldier) { }
    public record Plan(List<Assignment> assignments,int creates) { }
    private RoyalGarrisonRules() { }

    private static String string(final JsonObject object,final String key,final int max) {
        final JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Brak tekstu: "+key);
        final String text=value.getAsString();
        if(text.isBlank() || text.length()>max) throw new IllegalArgumentException("Nieprawidłowy tekst: "+key);
        return text;
    }
    private static double number(final JsonObject object,final String key,final double min,final double max) {
        final JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Brak liczby: "+key);
        final double n=value.getAsDouble();
        if(!Double.isFinite(n) || n<min || n>max) throw new IllegalArgumentException("Liczba poza zakresem: "+key);
        return n==0?0:n;
    }
    public static Request parse(final String json,final String world,final Map<String,Integer> unitEras) {
        if(json==null || json.length()>65_536) throw new IllegalArgumentException("Zbyt duży manifest gwardii.");
        final JsonElement parsed=JsonParser.parseString(json);
        if(!parsed.isJsonObject()) throw new IllegalArgumentException("Manifest gwardii musi być obiektem.");
        final JsonObject input=parsed.getAsJsonObject();
        final UUID country=UUID.fromString(string(input,"countryId",36));
        if(!COUNTRY.equals(country)) throw new IllegalArgumentException("Makro gwardii jest ograniczone do KRÓLESTWA.");
        final String selectedWorld=input.has("world")?string(input,"world",128):world;
        if(!Objects.equals(world,selectedWorld)) throw new IllegalArgumentException("Inny świat niż świat królewskiego projektu.");
        final Set<String> ids=new HashSet<>(),positions=new HashSet<>();
        final List<Post> posts=parsePosts(input.get(POSTS),unitEras,ids,positions,false);
        final List<Post> siege=input.has(SIEGE_POSTS)?parsePosts(input.get(SIEGE_POSTS),unitEras,ids,positions,true):List.of();
        return new Request(country,selectedWorld,posts,siege);
    }
    private static List<Post> parsePosts(final JsonElement raw,final Map<String,Integer> unitEras,final Set<String> ids,
                                         final Set<String> positions,final boolean siege) {
        final int max=siege?10:40;
        if(raw==null || !raw.isJsonArray() || (!siege && raw.getAsJsonArray().isEmpty()) || raw.getAsJsonArray().size()>max)
            throw new IllegalArgumentException("Nieprawidłowa liczba posterunków: "+(siege?"siegePosts":"posts"));
        final List<Post> posts=new ArrayList<>();
        for(final JsonElement entry:raw.getAsJsonArray()) {
            if(!entry.isJsonObject()) throw new IllegalArgumentException("Nieprawidłowy posterunek.");
            final JsonObject post=entry.getAsJsonObject();final String id=string(post,"id",64),type=string(post,"type",64).toLowerCase(Locale.ROOT);
            if(!id.matches("[A-Za-z0-9_-]+") || !ids.add(id)) throw new IllegalArgumentException("Powtórzony lub błędny id posterunku: "+id);
            final Integer era=unitEras.get(type);
            if(era==null || era<1 || era>2) throw new IllegalArgumentException("Nieobsługiwany typ gwardii lub epoka: "+type);
            if((SiegeRules.engine(type)!=null)!=siege) throw new IllegalArgumentException("Typ nie pasuje do listy posterunków: "+type);
            final double x=number(post,"x",-29_999_000,29_999_000),y=number(post,"y",-2048,4096),z=number(post,"z",-29_999_000,29_999_000);
            final double radius=post.has("radius")?number(post,"radius",1,16):4;
            final float yaw=post.has("yaw")?(float)number(post,"yaw",-360,360):0;
            if(!positions.add(x+":"+y+":"+z)) throw new IllegalArgumentException("Dwa posterunki mają tę samą pozycję.");
            posts.add(new Post(id,type,x,y,z,radius,yaw));
        }
        return List.copyOf(posts);
    }
    public static Plan plan(final Request request,final Collection<Candidate> candidates,final int global,final int country,
                            final int globalLimit,final int countryLimit) {
        if(!COUNTRY.equals(request.country()) || request.posts().isEmpty() || request.posts().size()>40 || global<0 || country<0)
            throw new IllegalArgumentException("Nieprawidłowe żądanie gwardii.");
        return planPosts(request.posts(),candidates,global,country,globalLimit,countryLimit,false);
    }
    public static Plan siegePlan(final Request request,final Collection<Candidate> candidates,final int global,final int country,
                                final int globalLimit,final int countryLimit) {
        if(!COUNTRY.equals(request.country()) || request.siegePosts().size()>10 || global<0 || country<0)
            throw new IllegalArgumentException("Nieprawidłowe żądanie artylerii.");
        return planPosts(request.siegePosts(),candidates,global,country,globalLimit,countryLimit,true);
    }
    public static void requireCoverage(final Collection<UUID> held,final Collection<Assignment> assignments) {
        final Set<UUID> covered=new HashSet<>();assignments.forEach(a->{if(a.soldier()!=null) covered.add(a.soldier());});
        for(final UUID id:held) if(!covered.contains(id)) throw new IllegalArgumentException(
            "Manifest pomija ewakuowanego żołnierza "+id+"; dodaj bezpieczny posterunek przed finalizacją.");
    }
    private static Plan planPosts(final List<Post> posts,final Collection<Candidate> candidates,final int global,final int country,
                                   final int globalLimit,final int countryLimit,final boolean artillery) {
        final List<Candidate> eligible=candidates.stream().filter(c->COUNTRY.equals(c.country()) && !c.embarked()
            && !c.mercenary() && c.artillery()==artillery && c.era()>=1 && c.era()<=2)
            .sorted(Comparator.comparing(c->c.id().toString())).toList();
        final Map<String,UUID> selected=new LinkedHashMap<>();final Set<UUID> used=new HashSet<>();
        // Reserve previous bindings and exact weapon types before considering a fallback for any post.
        for(final Post post:posts) for(final Candidate c:eligible) if(!used.contains(c.id()) && post.id().equals(c.postId())) {
            selected.put(post.id(),c.id());used.add(c.id());break;
        }
        for(final Post post:posts) if(!selected.containsKey(post.id())) for(final Candidate c:eligible)
            if(!used.contains(c.id()) && post.type().equals(c.type())) {selected.put(post.id(),c.id());used.add(c.id());break;}
        for(final boolean mounted:new boolean[]{false,true}) for(final Post post:posts) if(!selected.containsKey(post.id()))
            for(final Candidate c:eligible) if(!used.contains(c.id()) && c.mounted()==mounted) {
                selected.put(post.id(),c.id());used.add(c.id());break;
            }
        final int creates=posts.size()-selected.size();
        if(creates>Math.max(0,Math.min(1000,globalLimit)-global) || creates>Math.max(0,Math.min(60,countryLimit)-country))
            throw new IllegalArgumentException("Brak miejsc w limicie wojska na pełne obsadzenie gwardii.");
        final List<Assignment> assignments=posts.stream().map(p->new Assignment(p,selected.get(p.id()))).toList();
        return new Plan(assignments,creates);
    }
}
