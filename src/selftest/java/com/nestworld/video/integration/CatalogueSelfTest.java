package com.nestworld.video.integration;

import com.google.gson.*;
import com.nestworld.video.*;
import com.nestworld.video.client.gui.CatalogueOrder;
import java.util.*;

/** Standalone regression checks; no game or network required. */
public final class CatalogueSelfTest {
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) {
        String body = """
            {"episodes":[
              {"episode":12,"title_ua":"Назва","team":"Команда А","player":"ashdi","m3u8":"https://media.example.org/12.m3u8"},
              {"episode":2,"title_ua":"Назва","team":"Команда А","player":"ashdi","m3u8":"https://media.example.org/2.m3u8"},
              {"episode":1,"title_ua":"Назва","team":"Команда Б","player":"ashdi","video_url":"https://ashdi.vip/vod/1"},
              {"episode":1,"title_ua":"Назва","team":"Команда А","player":"ashdi","m3u8":"https://media.example.org/1.m3u8"}
            ]}
            """;
        var category=AniuaApiClient.parse("example-title-abcdef",body);
        check(AniuaApiClient.parse("announced-abcdef","{\"episodes\":[]}").getVideos().isEmpty(),"announced release opens empty");
        check(category.getVideos().size()==4,"unavailable episode retained");
        var ordered=category.getVideos().stream().sorted(CatalogueOrder.EPISODES).toList();
        check(ordered.get(0).getEpisode().equals("1") && ordered.get(2).getEpisode().equals("2")
                && ordered.get(3).getEpisode().equals("12"),"numeric order, not lexical order");
        check(ordered.stream().filter(v->v.getUrl().isBlank()).count()==1,"unavailable stream remains empty");
        var legacy=new Video("Неосяжна блакить — Togarashi","AniUA • епізод 12","https://example.org/x.mp4","","");
        check(legacy.getEpisode().equals("12") && legacy.getDub().equals("Togarashi")
                && legacy.getSeriesTitle().equals("Неосяжна блакить"),"old config metadata migration");
        check(CatalogueOrder.matches("Неосяжна блакить - 2 сезон","НЕОС"),"partial case-insensitive search");
        var titles=HikkaCatalogClient.parseSearch(JsonParser.parseString("""
            {"list":[{"slug":"blue-season-2-abcdef","title_ua":"Блакить - 2 сезон","year":2025,"media_type":"tv","image":"https://cdn.hikka.io/poster.jpg"},
                     {"slug":"blue-123456","title_ua":"Блакить","year":null,"season":"summer"}]}
            """).getAsJsonObject());
        check(titles.size()==2 && titles.get(0).seasonLabel().equals("Сезон 2"),"explicit season");
        check(!titles.get(1).seasonLabel().contains("summer"),"calendar quarter is not season number");
        check(titles.get(0).poster().endsWith(".jpg"),"poster retained");
        category.setDetails("Блакить - 2 сезон",titles.get(0).poster(),"Сезон 2");
        var restored=new Gson().fromJson(new Gson().toJson(category),Category.class);
        check(restored.getSeason().equals("Сезон 2") && restored.getVideos().get(0).getDub().equals("Команда А"),"JSON metadata round trip");
        check(!AniuaApiClient.isSupportedMediaUrl("file:///etc/passwd") &&
                !AniuaApiClient.isSupportedMediaUrl("https://127.0.0.1/test.mp4"),"media validation");
        System.out.println("NESTWORLD CATALOGUE PASS: metadata, legacy config, unavailable entries, numeric sort, partial search, seasons, poster, JSON");
    }
}
