// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure content of the server-info message. No JDA; only public http(s) links become buttons. */
record ServerInfo(String title,String description,List<Link> links) {
    record Link(String label,String url) {}
    static final String MAP_DEFAULT="http://mc.example.org:8100",PANEL_DEFAULT="https://panel.example.workers.dev/";
    static ServerInfo of(String address,String map,String panel) {
        List<Link> links=new ArrayList<>();StringBuilder text=new StringBuilder();
        String host=address==null?"":Policy.clean(address.trim(),100);
        if(!host.isBlank())text.append("🎮 **Adres serwera:** `").append(host).append("`\n\n");
        if(valid(map)){links.add(new Link("🗺️ Mapa świata",map.trim()));text.append("🗺️ **Mapa świata** — granice krajów, miasta i gracze na żywo.\n").append(map.trim()).append("\n\n");}
        if(valid(panel)){links.add(new Link("🏰 Panel zarządzania",panel.trim()));text.append("🏰 **Panel zarządzania** — skarbiec, podatki, armia i dyplomacja Twojego kraju w przeglądarce. Zaloguj się nickiem i hasłem z gry albo linkiem z komendy `/panel`.\n").append(panel.trim()).append("\n\n");}
        text.append("Wszystkie funkcje znajdziesz też w grze pod `/menu`.");
        return new ServerInfo("🌐 MEDIVALRPG — informacje o serwerze",text.toString(),List.copyOf(links));
    }
    static boolean valid(String url) {
        if(url==null||url.isBlank()||url.length()>512)return false;
        try {URI u=URI.create(url.trim());return Set.of("http","https").contains(String.valueOf(u.getScheme()).toLowerCase(Locale.ROOT))&&u.getHost()!=null&&u.getUserInfo()==null;}
        catch(IllegalArgumentException e){return false;}
    }
    String fingerprint() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");digest.update((title+"\n"+description).getBytes(StandardCharsets.UTF_8));
            for(Link l:links)digest.update(("\n"+l.label+"\n"+l.url).getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
