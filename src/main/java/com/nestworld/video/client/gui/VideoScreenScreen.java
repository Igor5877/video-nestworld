package com.nestworld.video.client.gui;

import com.google.gson.Gson;
import com.nestworld.video.*;
import com.nestworld.video.block.*;
import com.nestworld.video.client.*;
import com.nestworld.video.client.WatchHistory;
import com.nestworld.video.integration.HikkaCatalogClient.Title;
import com.nestworld.video.network.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Release -> dub -> episode browser. Discovery and saved-library filtering are independent. */
public class VideoScreenScreen extends AbstractContainerScreen<VideoScreenMenu> {
    private static final int TEXT=0xE8EDF2, MUTED=0xAAB8C7, GOLD=0xE7BE70;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private enum View { SEARCH, LIBRARY, VOICES, EPISODES, SOURCES }
    private record Row(String label, String hint, boolean enabled, Runnable action, String url, String poster, String meta, float ratio) {
        Row(String label, String hint, boolean enabled, Runnable action) { this(label, hint, enabled, action, "", "", "", -1f); }
        Row(String label, String hint, boolean enabled, Runnable action, String url) { this(label, hint, enabled, action, url, "", "", -1f); }
        Row(String label, String hint, boolean enabled, Runnable action, String url, String poster, String meta) { this(label, hint, enabled, action, url, poster, meta, -1f); }
    }
    private View view=View.SEARCH, origin=View.SEARCH;
    private boolean construction, loading;
    private String query="", libraryQuery="", status="Введи частину назви українською або англійською.";
    private String voice="", episode="";
    private int viewerCount=-1;
    private List<String> viewerNames=List.of();
    private int page, visibleRows=1, requestId, ticks, searchPage;
    private Category selected;
    private List<Title> results=List.of();
    private final List<Row> rows=new ArrayList<>();
    private final List<Button> entries=new ArrayList<>();
    private final AnimePoster poster=new AnimePoster();
    private EditBox search;
    private ScreenLayout preview;
    private Button assemble,play,pause,stop,skipBack,skipForward,skipIntro,previousPage,nextPage,searchButton,qualityButton,sortButton,favButton,favToggle;
    private CinemaSlider volumeSlider;
    private enum Sort { RECENT("Останні"), AZ("А–Я"), UNWATCHED("Не дивився"); final String label; Sort(String label) { this.label=label; } }
    /** Library filters survive closing the menu. */
    private static Sort sort=Sort.RECENT;
    private static boolean favoritesOnly;
    private static final int INTRO_SKIP_MS=85_000;
    /** Browser position survives closing the menu, so the episode list can be reopened in one click. */
    private record Saved(View view, View origin, Category selected, String voice, String episode, List<Title> results,
                         String query, String libraryQuery, int page, int searchPage) {}
    private static Saved saved;
    public VideoScreenScreen(VideoScreenMenu menu, Inventory inventory, Component title) {
        super(menu,inventory,title);
        if(saved!=null) {
            view=saved.view; origin=saved.origin; selected=saved.selected; voice=saved.voice; episode=saved.episode;
            results=saved.results; query=saved.query; libraryQuery=saved.libraryQuery; page=saved.page; searchPage=saved.searchPage;
            if(detail() && selected==null) view=origin=View.SEARCH;
            status=detail() ? "" : results.isEmpty() ? status : "Вибери аніме та сезон зі списку.";
        } else restoreFromPlaying();
    }
    /** First open: jump to the episode list of whatever the screen is currently playing, if it is in the library. */
    private void restoreFromPlaying() {
        var root=menu.getBlockEntity()==null ? null : menu.getBlockEntity().controller();
        if(root==null || root.getCurrentVideoUrl().isBlank()) return;
        for(Category c:categories()) {
            if(c.getVideos()==null) continue;
            for(Video v:c.getVideos()) if(v!=null && root.getCurrentVideoUrl().equals(v.getUrl())) {
                selected=c; origin=View.LIBRARY; voice=v.getDub(); view=View.EPISODES; status=""; return;
            }
        }
    }
    private Component tr(String key,Object... args) { return Component.translatable("gui.nestworldvideo."+key,args); }
    private void send(String action) {
        var root=screen();
        if("pause".equals(action) && root!=null) {
            long mediaTime=ClientVideoManager.getInstance().getPlaybackTime(root.getBlockPos());
            // The server validates that the packet's block entity matches the
            // menu's block entity. A menu opened on a child tile still controls
            // its master, so target the clicked/menu tile here and let the
            // server resolve its controller just like play/stop do.
            Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),action,mediaTime));
            return;
        }
        Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),action));
    }
    private Button button(Component label,int x,int y,int w,Button.OnPress action) {
        return addRenderableWidget(new CinemaButton(label,leftPos+x,topPos+y,w,action));
    }
    private Button button(String label,int x,int y,int w,Button.OnPress action) { return button(Component.literal(label),x,y,w,action); }
    private VideoScreenBlockEntity screen() { return menu.getBlockEntity()==null ? null : menu.getBlockEntity().controller(); }
    /** The release poster gets a proper column of its own; the list and the quick action sit around it. */
    private int rawPosterWidth() { return Math.max(56,Math.min(92,imageWidth/5)); }
    /** On a short window the poster column would be a sliver: drop it and give the list the whole width. */
    private int posterWidth() { return detail() && Math.min(rawPosterWidth()*3/2,imageHeight-114-100-28)>=48 ? rawPosterWidth() : 0; }
    private int posterHeight() { return Math.min(posterWidth()*3/2,imageHeight-114-100-28); }
    private int listLeft() { return detail() ? 12+posterWidth()+8 : 12; }
    private boolean detail() { return view==View.VOICES || view==View.EPISODES || view==View.SOURCES; }
    @Override protected void init() {
        imageWidth=Math.min(500,width-12); imageHeight=Math.min(430,height-12);
        super.init();
        if(menu.getBlockEntity()==null) { onClose(); return; }
        entries.clear(); search=null; searchButton=null; qualityButton=null; sortButton=null; favButton=null; favToggle=null; volumeSlider=null; skipIntro=null;
        int tab=(imageWidth-32)/3;
        View tabView=detail() ? origin : view;
        ((CinemaButton)button("Пошук AniUA",12,30,tab,b->switchView(View.SEARCH))).selected=!construction && tabView==View.SEARCH;
        ((CinemaButton)button("Бібліотека",16+tab,30,tab,b->switchView(View.LIBRARY))).selected=!construction && tabView==View.LIBRARY;
        ((CinemaButton)button(tr("screen"),20+tab*2,30,tab,b->{construction=true; rebuildWidgets();})).selected=construction;
        button("×",imageWidth-30,7,18,b->onClose()).setTooltip(Tooltip.create(tr("close")));
        if(construction) initConstruction(); else initBrowser();
        updateControls();
    }
    private void switchView(View next) {
        construction=false; view=next; page=0; status=next==View.SEARCH ?
                (results.isEmpty() ? "Введи частину назви українською або англійською." : "Вибери аніме та сезон зі списку.") : "Збережені аніме та відео";
        rebuildWidgets();
    }
    private List<Category> categories() {
        var config=ClientData.getVideoConfig();
        return config==null || config.getCategories()==null ? List.of() : config.getCategories().stream().filter(Objects::nonNull).toList();
    }
    private List<Video> videos() {
        return selected==null || selected.getVideos()==null ? List.of() :
                selected.getVideos().stream().filter(v->v!=null && v.getTitle()!=null).sorted(CatalogueOrder.EPISODES).toList();
    }
    private void initBrowser() {
        if (!detail()) {
            boolean remote=view==View.SEARCH;
            search=new CinemaEditBox(font,leftPos+13,topPos+58,imageWidth-(remote?108:26+110+26),20,Component.literal("Пошук"));
            search.setMaxLength(120); search.setHint(Component.literal(remote ? "Назва або частина назви…" : "Пошук у бібліотеці…"));
            search.setValue(remote ? query : libraryQuery);
            search.setResponder(value->{ if(remote) query=value; else {libraryQuery=value;page=0;refreshEntries();} });
            addRenderableWidget(search);
            if(remote) searchButton=button("Знайти",imageWidth-88,58,76,b->requestSearch());
            else {
                sortButton=button("",imageWidth-12-26-110+4,58,106,b->{
                    sort=Sort.values()[(sort.ordinal()+1)%Sort.values().length]; page=0; sortButton.setMessage(Component.literal("Сорт.: "+sort.label)); refreshEntries();
                });
                sortButton.setMessage(Component.literal("Сорт.: "+sort.label));
                sortButton.setTooltip(Tooltip.create(Component.literal("Порядок бібліотеки: останні переглянуті / за абеткою / ще не дивився")));
                favButton=button("",imageWidth-12-22,58,22,b->{
                    favoritesOnly=!favoritesOnly; page=0; favButton.setMessage(Component.literal(favoritesOnly?"★":"☆")); refreshEntries();
                });
                favButton.setMessage(Component.literal(favoritesOnly?"★":"☆"));
                ((CinemaButton)favButton).selected=false;
                favButton.setTooltip(Tooltip.create(Component.literal("Показати лише вибране")));
            }
        } else {
            button("‹ Назад",12,58,78,b->back());
            boolean anime=selected.getName()!=null && selected.getName().startsWith("AniUA:");
            favToggle=button(WatchHistory.isFavorite(selected)?"★":"☆",imageWidth-12-22,58,22,b->{
                WatchHistory.toggleFavorite(selected); favToggle.setMessage(Component.literal(WatchHistory.isFavorite(selected)?"★":"☆"));
            });
            favToggle.setTooltip(Tooltip.create(Component.literal("Додати до вибраного або прибрати")));
            button(Component.literal(selected.getDisplayTitle()),96,58,imageWidth-(anime?184:108)-26,b->{
                view=View.VOICES;page=0;rebuildWidgets();
            }).setTooltip(Tooltip.create(Component.literal(selected.getDisplayTitle()+"\n"+selected.getSeason())));
            if(anime) button("Сезони",imageWidth-84-26,58,72,b->{
                if(loading) return;
                view=View.SEARCH;page=0;results=List.of();status="Завантажую сезони та пов’язані фільми…";
                rebuildWidgets();request("seasons",selected.getName().substring(6).trim());
            }).setTooltip(Tooltip.create(Component.literal("Усі сезони та пов’язані фільми")));
            poster.load(selected.getPoster());
            int actionWidth=posterHeight()*2/3;
            if(posterWidth()>0) {
            var watch=button("▶ Дивитись",12,100+posterHeight()+6,Math.max(actionWidth,76),b->playFromStart());
            ((CinemaButton)watch).primary=true; ((CinemaButton)watch).centered=true;
            watch.active=bestStart()!=null;
            watch.setTooltip(Tooltip.create(Component.literal("Почати з першої доступної серії в найкращій озвучці")));
            }
        }
        boolean cards=!detail() && (view==View.SEARCH || view==View.LIBRARY), voiceCards=view==View.VOICES;
        int columns=cards || view==View.EPISODES ? 2 : 1;
        int start=100, available=imageHeight-114-start;
        // Cards stretch so the list fills the space: about three rows, never tiny and never taller than a poster needs.
        int cardRows=Math.max(1,Math.round(available/62f));
        int cardHeight=Math.max(46,Math.min(66,available/cardRows-4)), pitch=cards?cardHeight+4:voiceCards?Math.max(24,Math.min(46,available)):24;
        int left=listLeft();
        visibleRows=Math.max(1,(imageHeight-114-start)/pitch)*columns;
        int rowWidth=(imageWidth-left-12-(columns-1)*6)/columns;
        for(int i=0;i<visibleRows;i++) {
            final int index=i;
            Button.OnPress press=b->{
                int n=page*visibleRows+index;
                if(n<rows.size() && rows.get(n).enabled()) rows.get(n).action().run();
            };
            int x=left+(i%columns)*(rowWidth+6), y=start+(i/columns)*pitch;
            if(voiceCards) {
                var card=addRenderableWidget(new CardButton(leftPos+x,topPos+y,rowWidth,Math.max(22,Math.min(42,available-2)),press));
                card.compact=true;
                entries.add(card);
            } else entries.add(cards ? addRenderableWidget(new CardButton(leftPos+x,topPos+y,rowWidth,cardHeight,press))
                    : button("",x,y,rowWidth,press));
        }
        int W=imageWidth, H=imageHeight;
        previousPage=button("‹",12,H-110,28,b->{page--;refreshEntries();});
        nextPage=button("›",W-40,H-110,28,b->{page++;refreshEntries();});
        qualityButton=button(qualityLabel(),W-40-8-96,H-110,96,b->cycleQuality());
        qualityButton.setTooltip(Tooltip.create(Component.literal("Максимальна якість відео (HLS). Потік перезапуститься.")));
        // Transport row: three big actions on the left, seek and volume tucked into one cluster on the right.
        int ty=H-34, sliderW=60, introW=62, cluster=30+4+30+4+introW+10+sliderW;
        int bw=(W-24-cluster-12-8)/3;
        boolean wide=bw>=80;
        play=button((wide?"▶  ":"")+(wide?tr("play").getString():"▶"),12,ty,bw,b->send("play"));
        pause=button((wide?"❚❚  ":"")+(wide?tr("pause").getString():"❚❚"),16+bw,ty,bw,b->send("pause"));
        stop=button((wide?"■  ":"")+(wide?tr("stop").getString():"■"),20+bw*2,ty,bw,b->send("stop"));
        ((CinemaButton)play).primary=true; ((CinemaButton)play).centered=true;
        ((CinemaButton)pause).centered=true; ((CinemaButton)stop).centered=true;
        int cx=W-12-cluster;
        skipBack=button("−10",cx,ty,30,b->seek(-10_000));
        skipForward=button("+10",cx+34,ty,30,b->seek(10_000));
        skipIntro=button("Заставка",cx+68,ty,introW,b->seek(INTRO_SKIP_MS));
        volumeSlider=addRenderableWidget(new CinemaSlider(leftPos+W-12-sliderW,topPos+ty,sliderW,200,100,
                ()->{ var d=display(); return d==null?0:d.getVolume(); },v->{ var d=display(); if(d!=null) d.setVolume(v); }));
        skipBack.setTooltip(Tooltip.create(Component.literal("Перемотати назад на 10 секунд")));
        skipForward.setTooltip(Tooltip.create(Component.literal("Перемотати вперед на 10 секунд")));
        skipIntro.setTooltip(Tooltip.create(Component.literal("Пропустити заставку: +85 секунд")));
        volumeSlider.setTooltip(Tooltip.create(tr("local_volume")));
        refreshEntries();
    }
    private void back() {
        if(view==View.SOURCES) view=View.EPISODES;
        else if(view==View.EPISODES && videos().stream().anyMatch(v->!v.getEpisode().isBlank())) view=View.VOICES;
        else {view=origin;page=origin==View.SEARCH ? searchPage : 0;rebuildWidgets();return;}
        page=0;rebuildWidgets();
    }
    private void open(Category category) { open(category, true); }
    private void openLoaded(Category category) { open(category, false); }
    private void open(Category category, boolean refreshStale) {
        selected=category; voice=""; page=0;
        view=videos().stream().anyMatch(v->!v.getEpisode().isBlank()) ? View.VOICES : View.EPISODES;
        boolean stale = refreshStale && category.getName() != null && category.getName().startsWith("AniUA:")
                && videos().stream().anyMatch(v -> v.getUrl() == null || v.getUrl().isBlank());
        status=stale ? "Оновлюю посилання на відео…" : "";
        rebuildWidgets();
        if (stale) request("load", category.getName().substring("AniUA:".length()).trim());
    }
    private void requestSearch() {
        if(loading) return;
        if(query.trim().length()<2) {status="Введи хоча б 2 символи.";return;}
        results=List.of();page=0;status="Шукаю «"+query.trim()+"»…";
        request("search",query.trim());
    }
    private void request(String action,String input) {
        loading=true; requestId=REQUESTS.incrementAndGet();
        Networking.INSTANCE.sendToServer(new PacketBrowseRequest(menu.containerId,requestId,action,input));
        refreshEntries();
    }
    public void acceptBrowse(PacketBrowseResult result) {
        if(result.menuId()!=menu.containerId || result.requestId()!=requestId) return;
        loading=false;
        if(result.error()) {status=result.body();refreshEntries();return;}
        try {
            if(result.action().equals("search") || result.action().equals("seasons")) {
                results=Arrays.asList(new Gson().fromJson(result.body(),Title[].class));
                if(view==View.SEARCH) page=0;
                status=results.isEmpty() ? "Нічого не знайдено. Спробуй коротшу або іншу назву." :
                        "Вибери аніме та сезон"+(results.size()==50 ? ". Перші 50 збігів; уточни назву." : ".");
            } else if(result.action().equals("load")) {
                openLoaded(new Gson().fromJson(result.body(),Category.class));return;
            }
            refreshEntries();
        } catch(Exception e) {status="Не вдалося прочитати відповідь. Повтори пошук.";refreshEntries();}
    }

    public void acceptViewerInfo(net.minecraft.core.BlockPos pos, int count, List<String> names) {
        var root=screen();
        if(root==null || !root.getBlockPos().equals(pos)) return;
        viewerCount=Math.max(0,count);
        viewerNames=names==null?List.of():List.copyOf(names);
    }
    private void buildRows() {
        rows.clear();
        switch(view) {
            case SEARCH -> {
                for(Title title:results) rows.add(new Row(title.title(),title.title()+"\n"+title.seasonLabel(),!loading,()->{
                    searchPage=page; status="Завантажую серії: "+title.title()+"…";request("load",title.slug());
                },"",title.poster(),metaOf(title)));
            }
            case LIBRARY -> {
                var all=new ArrayList<Category>();
                for(Category c:categories()) {
                    if(!CatalogueOrder.matches(c.getDisplayTitle(),libraryQuery)) continue;
                    if(favoritesOnly && !WatchHistory.isFavorite(c)) continue;
                    if(sort==Sort.UNWATCHED && WatchHistory.touched(c)) continue;
                    all.add(c);
                }
                if(sort==Sort.AZ) { var collator=java.text.Collator.getInstance(Locale.forLanguageTag("uk")); all.sort((x,y)->collator.compare(x.getDisplayTitle(),y.getDisplayTitle())); }
                else if(sort==Sort.RECENT) all.sort(Comparator.comparingLong((Category c)->{ var e=WatchHistory.latest(c); return e==null?0:-e.time; }));
                // "Continue watching": where you stopped, one click away (only on the plain, unfiltered shelf).
                if(libraryQuery.isBlank() && !favoritesOnly && sort==Sort.RECENT) addResumeRows(all);
                for(Category c:all)
                    rows.add(new Row((WatchHistory.isFavorite(c)?"★ ":"")+c.getDisplayTitle(),c.getDisplayTitle(),true,()->{origin=View.LIBRARY;open(c);},"",c.getPoster(),metaOf(c)));
                // Also find individual videos by a partial title in non-anime categories.
                if(!libraryQuery.isBlank()) for(Category c:categories()) if(c.getVideos()!=null && (c.getName()==null || !c.getName().startsWith("AniUA:")))
                    for(Video v:c.getVideos()) if(v!=null && CatalogueOrder.matches(v.getTitle(),libraryQuery))
                        rows.add(new Row(v.getTitle(),v.getTitle(),playable(v),()->select(v),url(v)));
            }
            case VOICES -> {
                var voices=new LinkedHashMap<String,Set<String>>();
                for(Video v:videos()) voices.computeIfAbsent(v.getDub(),k->new LinkedHashSet<>()).add(v.getEpisode());
                voices.forEach((dub,episodes)->{
                    var dubVideos=videos().stream().filter(v->v.getDub().equals(dub)).toList();
                    long available=dubVideos.stream().filter(VideoScreenScreen::playable).map(Video::getEpisode).distinct().count();
                    boolean enabled=available>0;
                    String label=dub;
                    String voiceMeta=enabled ? available+" з "+episodes.size()+" серій доступні" : "Немає доступних потоків";
                    int playedHeight=WatchHistory.height(selected,dub);
                    if(enabled && playedHeight>0) voiceMeta+=" · "+qualityTag(playedHeight);
                    String hint=enabled
                            ? dub+"\n"+available+" з "+episodes.size()+" серій доступні."
                            : dub+"\nУ цій озвучці зараз немає доступного відеопотоку.";
                    rows.add(new Row(label,hint,enabled,()->{voice=dub;view=View.EPISODES;page=0;rebuildWidgets();},"","",voiceMeta,
                            episodes.isEmpty()?0f:available/(float)episodes.size()));
                });
            }
            case EPISODES -> {
                if(voice.isEmpty()) {
                    for(Video v:videos()) rows.add(new Row(v.getTitle(),v.getTitle(),playable(v),()->select(v),url(v)));
                } else {
                    var grouped=new LinkedHashMap<String,List<Video>>();
                    for(Video v:videos()) if(v.getDub().equals(voice)) grouped.computeIfAbsent(v.getEpisode(),k->new ArrayList<>()).add(v);
                    grouped.forEach((number,variants)->{
                        var playable=variants.stream().filter(VideoScreenScreen::playable).toList();
                        String season=selected.getSeason().startsWith("Сезон ") ? selected.getSeason()+" · " : "";
                        var seen=playable.isEmpty()?null:WatchHistory.get(selected,playable.get(0));
                        String mark=seen==null?"":seen.watched?"✓ ":seen.position>=WatchHistory.MIN_RESUME_MS?"◐ ":"";
                        String label=mark+season+"Серія "+number+(playable.isEmpty()?" — недоступна":
                                seen!=null && !seen.watched && seen.position>=WatchHistory.MIN_RESUME_MS ? " · "+formatTime(seen.position) : "");
                        rows.add(new Row(label,selected.getDisplayTitle()+"\n"+selected.getSeason()+"\n"+voice+"\n"+
                            (playable.isEmpty()?"Джерело не надало прямого відеопотоку.": "Серія "+number+". Натисни, щоб вибрати, потім «Відтворити»."),
                            !playable.isEmpty(),()->{
                                if(playable.size()==1) select(playable.get(0));
                                else {episode=number;view=View.SOURCES;page=0;rebuildWidgets();}
                            },playable.isEmpty()?"":url(playable.get(0))));
                    });
                }
            }
            case SOURCES -> {
                int n=0;
                var urls=new HashSet<String>();
                for(Video v:videos()) if(v.getDub().equals(voice) && v.getEpisode().equals(episode) && playable(v) && urls.add(v.getUrl())) {
                    String label="Серія "+episode+" · "+(v.getProvider().isBlank()?"Джерело "+(++n):v.getProvider());
                    rows.add(new Row(label,label,true,()->select(v),url(v)));
                }
            }
        }
    }
    private static String qualityTag(int height) {
        return (height>=1000?1080:height>=700?720:height>=450?480:360)+"p";
    }
    /** Up to four "continue" cards: the half-watched episode, or the one after an episode you finished. */
    private void addResumeRows(List<Category> visible) {
        var recent=new ArrayList<Category>();
        for(Category c:visible) if(WatchHistory.touched(c)) recent.add(c);
        int shown=0;
        for(Category c:recent) {
            if(shown>=4) break;
            var entry=WatchHistory.latest(c);
            Video current=null;
            for(Video v:c.getVideos()==null?List.<Video>of():c.getVideos())
                if(v!=null && playable(v) && v.getDub().equals(entry.dub) && v.getEpisode().equals(entry.episode)) { current=v; break; }
            if(current==null) continue;
            final Video video=current;
            long position=0; String meta; float ratio=-1f;
            if(entry.watched) {
                Video next=NextEpisode.find(c.getVideos(),video.getUrl());
                if(next==null) continue;
                final Video follow=next;
                meta="Далі: серія "+(follow.getEpisode().isBlank()?follow.getTitle():follow.getEpisode());
                rows.add(new Row("▶ "+c.getDisplayTitle(),"Продовжити: "+meta,true,()->resume(c,follow,0),"",c.getPoster(),meta));
            } else {
                position=entry.position>=WatchHistory.MIN_RESUME_MS ? entry.position : 0;
                meta="Серія "+(video.getEpisode().isBlank()?video.getTitle():video.getEpisode())
                        +(position>0?" · "+formatTime(position)+(entry.duration>0?" / "+formatTime(entry.duration):""):"");
                ratio=position>0 && entry.duration>0 ? (float)entry.fraction() : -1f;
                final long at=position;
                rows.add(new Row("▶ "+c.getDisplayTitle(),"Продовжити: "+meta,true,()->resume(c,video,at),"",c.getPoster(),meta,ratio));
            }
            shown++;
        }
    }
    private void resume(Category category,Video video,long positionMs) {
        origin=View.LIBRARY; selected=category; voice=video.getDub(); view=View.EPISODES; page=0; status=""; construction=false;
        select(video); send("play");
        if(positionMs>=WatchHistory.MIN_RESUME_MS) seek(positionMs);
        rebuildWidgets();
        for(int i=0;i<rows.size();i++) if(url(video).equals(rows.get(i).url())) { page=i/Math.max(1,visibleRows); break; }
        refreshEntries();
    }
    private static String metaOf(Title title) {
        var parts=new ArrayList<String>();
        parts.add(title.seasonLabel());
        if(title.year()!=null && !title.year().isBlank()) parts.add(title.year());
        if(title.type()!=null && !title.type().isBlank()) parts.add(title.type().toUpperCase());
        return String.join(" · ",parts);
    }
    /** Ukrainian plural: 1 серія, 2–4 серії, 5–20 серій, 21 серія ... */
    private static String plural(long n,String one,String few,String many) {
        long mod100=n%100, mod10=n%10;
        String word=mod100>=11 && mod100<=14 ? many : mod10==1 ? one : mod10>=2 && mod10<=4 ? few : many;
        return n+" "+word;
    }
    private static String metaOf(Category category) {
        var parts=new ArrayList<String>();
        if(category.getSeason()!=null && !category.getSeason().isBlank()) parts.add(category.getSeason());
        var videos=category.getVideos();
        if(videos!=null) {
            long episodes=videos.stream().filter(Objects::nonNull).map(Video::getEpisode).filter(e->!e.isBlank()).distinct().count();
            boolean film="Фільм".equals(category.getSeason()) && episodes<=1;
            if(!film) parts.add(episodes>0 ? plural(episodes,"серія","серії","серій") : plural(videos.size(),"відео","відео","відео"));
        }
        return String.join(" · ",parts);
    }
    private static String url(Video v) {return v.getUrl()==null?"":v.getUrl();}
    private static boolean playable(Video v) {return v.getUrl()!=null && !v.getUrl().isBlank();}
    /** First playable episode of the chosen voice, or of the voice with the most playable episodes. */
    private Video bestStart() {
        var all=videos();
        String chosen=voice;
        if(chosen.isEmpty()) {
            var counts=new LinkedHashMap<String,Long>();
            for(Video v:all) if(playable(v)) counts.merge(v.getDub(),1L,Long::sum);
            chosen=counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        }
        final String dub=chosen;
        return all.stream().filter(VideoScreenScreen::playable).filter(v->dub.isEmpty() || v.getDub().equals(dub)).findFirst().orElse(null);
    }
    private void playFromStart() {
        Video start=bestStart();
        if(start==null) return;
        select(start);
        send("play");
    }
    private void select(Video video) {
        Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),"select",video.getUrl(),""));
    }
    private void refreshEntries() {
        if(construction) return;
        buildRows();
        var playingRoot=screen();
        String currentUrl=playingRoot==null?"":playingRoot.getCurrentVideoUrl();
        int pages=Math.max(1,(rows.size()+visibleRows-1)/visibleRows);
        page=Math.max(0,Math.min(page,pages-1));
        for(int i=0;i<entries.size();i++) {
            var button=entries.get(i);int n=page*visibleRows+i;
            button.visible=n<rows.size();
            if(button.visible) {
                var row=rows.get(n);
                boolean current=!row.url().isBlank() && row.url().equals(currentUrl);
                if(!(button instanceof CardButton)) button.setMessage(Component.literal((current?"▶ ":"")+row.label()));
                if(button instanceof CinemaButton cb) cb.selected=current;
                if(button instanceof CardButton card) card.set(row.label(),row.meta(),row.poster(),row.ratio());
                button.active=row.enabled();button.setTooltip(Tooltip.create(Component.literal(row.hint())));
            }
        }
        if(previousPage!=null) previousPage.active=page>0;
        if(nextPage!=null) nextPage.active=page+1<pages;
    }
    private static final int[] QUALITIES={0,1080,720,480,360};
    private static String qualityLabel() {
        int h=NestworldConfig.MAX_VIDEO_HEIGHT.get();
        return "Якість: "+(h==0?"Авто":h+"p");
    }
    private void cycleQuality() {
        int current=NestworldConfig.MAX_VIDEO_HEIGHT.get(), next=QUALITIES[0];
        for(int i=0;i<QUALITIES.length;i++) if(QUALITIES[i]==current) next=QUALITIES[(i+1)%QUALITIES.length];
        NestworldConfig.MAX_VIDEO_HEIGHT.set(next);
        NestworldConfig.MAX_VIDEO_HEIGHT.save();
        if(qualityButton!=null) qualityButton.setMessage(Component.literal(qualityLabel()));
        ClientVideoManager.getInstance().restartAll();
    }
    private com.nestworld.video.client.util.Display display() {
        var root=screen();
        return root==null?null:ClientVideoManager.getInstance().getDisplay(root.getBlockPos());
    }
    // Card geometry shared by drawing, hit-testing and tooltips (relative to the card's top-left corner).
    private int cardX() { return leftPos+12; }
    private int cardY() { return topPos+imageHeight-84; }
    private int cardW() { return imageWidth-24; }
    private ClientVideoManager.NextUp nextUp() {
        var root=screen();
        return root==null?null:ClientVideoManager.getInstance().getNextUp(root.getBlockPos());
    }
    private boolean cancelShown() { return nextUp()!=null; }
    /** Right-hand line of the "now playing" card: the countdown with its cancel, or the jump back to the episode list. */
    private String rightText() {
        var up=nextUp();
        if(up!=null) return "Наступна за "+up.seconds()+" с · Скасувати";
        return categoryOfCurrent(null)!=null ? "↩ До списку серій" : null;
    }
    private boolean overRight(double mx,double my,String text) {
        return text!=null && mx>=cardX()+cardW()-12-font.width(text) && mx<cardX()+cardW()-8 && my>=cardY()+17 && my<cardY()+30;
    }
    // Two small toggles on the card's state line.
    private int chipX(int index) {
        int x=cardX()+11+font.width("❚❚ Відтворюється")+14;
        if(index>0) x+=font.width("Авто")+10+4;
        return x;
    }
    private int chipW(int index) { return font.width(index==0?"Авто":"Повтор")+10; }
    private boolean overChip(double mx,double my,int index) {
        return mx>=chipX(index) && mx<chipX(index)+chipW(index) && my>=cardY()+17 && my<cardY()+30;
    }
    private void chip(GuiGraphics g,int index,String label,boolean on,int mx,int my) {
        int x=chipX(index), y=cardY()+17, w=chipW(index);
        boolean hover=overChip(mx,my,index);
        StarMenuPaint.round(g,x,y,w,13,3,on?(hover?0xFF5A4F30:0xFF4A4129):(hover?0xFF354A5E:0xFF2C3E4F));
        g.drawString(font,label,x+5,y+3,on?0xFFE7BE70:0xFF8FA1B3,false);
    }
    private boolean overBar(double mx,double my) {
        return mx>=cardX()+8 && mx<cardX()+cardW()-8 && my>=cardY()+30 && my<cardY()+43;
    }
    /** Position of the playing video in ms as the viewer's own decoder sees it. */
    private long currentPosition() {
        var root=screen();
        if(root==null) return 0;
        var display=ClientVideoManager.getInstance().getDisplay(root.getBlockPos());
        long position=root.getPlaybackPositionMs();
        if(display!=null) { long media=display.getPlaybackTime(); if(media>=0) position=media; }
        return position;
    }
    private long currentDuration() {
        var root=screen();
        var display=root==null?null:ClientVideoManager.getInstance().getDisplay(root.getBlockPos());
        return display==null?-1:display.getDuration();
    }
    private double barFraction(double mx) {
        double inner=cardW()-22;
        return Math.max(0,Math.min(1,(mx-(cardX()+11))/inner));
    }
    private boolean canSeekOnBar() {
        var root=screen();
        return root!=null && !root.getCurrentVideoUrl().isBlank() && !root.isStopped() && currentDuration()>0;
    }
    /** The release/voice list that contains the video now on the screen, or null when it is not in the catalogue. */
    private Category categoryOfCurrent(Video[] found) {
        var root=screen();
        String url=root==null?"":root.getCurrentVideoUrl();
        if(url.isBlank()) return null;
        for(Category c:categories()) {
            if(c.getVideos()==null) continue;
            for(Video v:c.getVideos()) if(v!=null && url.equals(v.getUrl())) { if(found!=null) found[0]=v; return c; }
        }
        return null;
    }
    /** Back to the episode list of whatever is playing: same release, same voice, on the page that holds it. */
    private void jumpToCurrent() {
        Video[] video=new Video[1];
        Category category=categoryOfCurrent(video);
        if(category==null) return;
        construction=false;
        selected=category;
        voice=video[0].getDub();
        if(origin==View.SEARCH && results.isEmpty()) origin=View.LIBRARY;
        view=View.EPISODES;
        page=0;
        status="";
        rebuildWidgets();
        String url=video[0].getUrl();
        for(int i=0;i<rows.size();i++) if(url.equals(rows.get(i).url())) { page=i/Math.max(1,visibleRows); break; }
        refreshEntries();
    }
    private void seek(long deltaMs) {
        if(screen()!=null) Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),"seek_by",deltaMs));
    }
    private static String formatTime(long millis) {
        long totalSeconds=Math.max(0,millis)/1000;
        long hours=totalSeconds/3600, minutes=(totalSeconds/60)%60, seconds=totalSeconds%60;
        return hours>0?String.format(Locale.ROOT,"%d:%02d:%02d",hours,minutes,seconds):String.format(Locale.ROOT,"%02d:%02d",minutes,seconds);
    }
    private void refreshPreview() {
        if (minecraft != null && minecraft.level != null && menu.getBlockEntity() != null)
            preview = ScreenLayout.detect(minecraft.level, menu.getBlockEntity().getBlockPos());
    }
    private void initConstruction() {
        refreshPreview();
        int bw = (imageWidth - 28) / 2;
        assemble = button(tr("assemble"), 12, imageHeight - 36, bw, b -> send("assemble"));
        button(tr("separate"), 16 + bw, imageHeight - 36, bw, b -> send("reset_layout"))
                .setTooltip(Tooltip.create(tr("separate_hint")));
    }

    private boolean assembled() {
        var root = screen();
        return preview != null && preview.valid() && root != null
                && root.getBlockPos().equals(preview.origin())
                && root.getScreenCols() == preview.cols() && root.getScreenRows() == preview.rows();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if(++ticks%20==0) { if(construction) refreshPreview();else refreshEntries(); }
        if(!construction && ticks%40==0) send("viewers");
        updateControls();
    }
    private void updateControls() {
        if(construction) {if(assemble!=null) assemble.active=preview!=null && preview.valid() && !assembled();return;}
        var root=screen();boolean has=root!=null && !root.getCurrentVideoUrl().isBlank();
        if(play!=null) play.active=has && !root.isPlaying();
        if(pause!=null) pause.active=has && root.isPlaying();
        if(stop!=null) stop.active=has && !root.isStopped();
        if(volumeSlider!=null) volumeSlider.active=display()!=null;
        if(skipIntro!=null) skipIntro.active=has && !root.isStopped();
        if(skipBack!=null) skipBack.active=has && !root.isStopped();
        if(skipForward!=null) skipForward.active=has && !root.isStopped();
        if(searchButton!=null) searchButton.active=!loading && query.trim().length()>=2;
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {}
    private void text(GuiGraphics g,Component value,int x,int y,int color,int maxWidth) {
        g.drawString(font,font.plainSubstrByWidth(value.getString(),Math.max(1,maxWidth)),leftPos+x,topPos+y,color,false);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY) {
        StarMenuPaint.panel(g,leftPos,topPos,imageWidth,imageHeight,0xFA1D2A36,0xFFE7BE70);
        text(g,Component.literal("Nestworld · Кінотеатр"),12,12,TEXT,imageWidth-50);
        var screen=screen();
        if(construction) {
            int pw = (imageWidth - 36) / 2, ph = imageHeight - 145;
            int cols = preview != null && preview.valid() ? preview.cols() : screen == null ? 1 : screen.getScreenCols();
            int rows = preview != null && preview.valid() ? preview.rows() : screen == null ? 1 : screen.getScreenRows();
            float scale = Math.min((pw - 10f) / cols, (ph - 10f) / rows);
            int w = Math.max(1, (int)(cols * scale)), h = Math.max(1, (int)(rows * scale));
            int x = leftPos + 12 + (pw - w) / 2, y = topPos + 58 + (ph - h) / 2;
            g.fill(leftPos + 12, topPos + 58, leftPos + 12 + pw, topPos + 58 + ph, 0xFF18232E);
            g.fill(x, y, x + w, y + h, 0xFF365C78);
            if (scale >= 5) {
                for (int c = 1; c < cols; c++) g.fill(x + (int)(c * scale), y, x + (int)(c * scale) + 1, y + h, 0xFF263341);
                for (int r = 1; r < rows; r++) g.fill(x, y + (int)(r * scale), x + w, y + (int)(r * scale) + 1, 0xFF263341);
            }
            g.drawCenteredString(font, cols + " × " + rows, leftPos + 12 + pw / 2, topPos + 63 + ph, GOLD);
            g.drawWordWrap(font, tr("build_hint", ScreenLayout.MAX_SIZE, ScreenLayout.MAX_SIZE), leftPos + pw + 24, topPos + 60, pw, TEXT);
            Component status = preview != null && preview.valid()
                    ? assembled() ? tr("assembled", cols, rows) : tr("ready", cols * rows) : tr("error." + (preview == null ? "missing" : preview.error()));
            g.drawWordWrap(font, status, leftPos + 12, topPos + imageHeight - 63, imageWidth - 24,
                    preview != null && preview.valid() ? GOLD : 0xFF9D92);

        } else {
            boolean noPlayableVoice=detail() && view==View.EPISODES && !voice.isBlank()
                    && !rows.isEmpty() && rows.stream().noneMatch(Row::enabled);
            String line=detail() ? selected.getSeason()+" · "+(view==View.VOICES?"Вибери озвучку":voice)
                    +(noPlayableVoice?" · немає доступних потоків":"") : status;
            text(g,Component.literal(line),listLeft(),84,MUTED,imageWidth-listLeft()-12);
            if(posterWidth()>0) {
                int h=posterHeight(),w=h*2/3;
                StarMenuPaint.round(g,leftPos+12,topPos+100,w,h,4,0xFF1E2B37);
                if(poster.texture()!=null) {
                    float scale=Math.min(w/(float)poster.width(),h/(float)poster.height());
                    int pw=(int)(poster.width()*scale),ph=(int)(poster.height()*scale);
                    g.blit(poster.texture(),leftPos+12+(w-pw)/2,topPos+100+(h-ph)/2,pw,ph,0,0,poster.width(),poster.height(),poster.width(),poster.height());
                } else {
                    String title=selected.getDisplayTitle();
                    String initial=title==null||title.isBlank()?"?":title.substring(0,1).toUpperCase();
                    g.pose().pushPose();
                    g.pose().translate(leftPos+12+w/2f,topPos+100+h/2f-12,0);
                    g.pose().scale(3f,3f,1f);
                    g.drawCenteredString(font,initial,0,0,0xFF6F8294);
                    g.pose().popPose();
                }
            }
            if(rows.isEmpty()) {
                String empty=loading?"Завантаження…":detail() && "Фільм".equals(selected.getSeason())
                        ? "Це фільм. AniUA не передав доступного відеопотоку для цього релізу."
                        : detail()?"У цьому релізі немає серій.":
                        view==View.LIBRARY?"Нічого не знайдено. Знайди аніме у вкладці AniUA.":status;
                if(!empty.equals(line)) g.drawWordWrap(font,Component.literal(empty),leftPos+listLeft(),topPos+103,imageWidth-listLeft()-12,MUTED);
            }
            int pages=Math.max(1,(rows.size()+visibleRows-1)/visibleRows);
            g.drawCenteredString(font,(page+1)+" / "+pages+"  ·  "+rows.size()+" "+(view==View.EPISODES?"серій":"записів"),
                    leftPos+imageWidth/2-20,topPos+imageHeight-104,MUTED);
            // "Now playing" card: what is on the screen, its state, time and a progress bar.
            String name=screen==null || screen.getCurrentVideoUrl().isBlank()?tr("none").getString():screen.getCurrentVideoTitle();
            var display=screen==null?null:ClientVideoManager.getInstance().getDisplay(screen.getBlockPos());
            boolean isPlaying=screen!=null && screen.isPlaying(), isStopped=screen==null || screen.isStopped();
            // Fills ignore colours without an alpha channel (GOLD is an RGB text colour), so force it opaque.
            int stateColor=0xFF000000|(isPlaying?GOLD:isStopped?0x6B7885:0x6FA8DC);
            int cardX=leftPos+12,cardY=topPos+imageHeight-84,cardW=imageWidth-24;
            StarMenuPaint.round(g,cardX,cardY,cardW,44,5,0xFF243545);
            StarMenuPaint.round(g,cardX,cardY,3,44,1,stateColor);
            long position=screen==null?0:screen.getPlaybackPositionMs();
            if(display!=null) {
                long mediaTime=display.getPlaybackTime();
                if(mediaTime>=0) position=mediaTime;
            }
            long duration=display==null?-1:display.getDuration();
            String time=formatTime(position)+" / "+(duration>0?formatTime(duration):"--:--");
            int timeWidth=font.width(time);
            g.drawString(font,font.plainSubstrByWidth(name,Math.max(1,cardW-timeWidth-34)),cardX+11,cardY+7,GOLD,false);
            g.drawString(font,time,cardX+cardW-9-timeWidth,cardY+7,TEXT,false);
            String state=tr(screen==null?"error.unloaded":isPlaying?"playing":isStopped?"stopped":"paused").getString();
            g.drawString(font,(isPlaying?"▶ ":isStopped?"■ ":"❚❚ ")+state,cardX+11,cardY+20,stateColor,false);
            int barX=cardX+11,barY=cardY+33,barW=cardW-22;
            boolean hoverBar=overBar(mouseX,mouseY) && canSeekOnBar();
            StarMenuPaint.round(g,barX,barY,barW,hoverBar?6:4,2,0xFF34495C);
            if(duration>0) {
                int filled=(int)(barW*Math.max(0,Math.min(1,position/(double)duration)));
                if(filled>0) StarMenuPaint.round(g,barX,barY,Math.max(3,filled),hoverBar?6:4,2,stateColor);
                if(hoverBar) StarMenuPaint.round(g,barX+filled-3,barY-2,6,10,2,0xFFF7DE9D);
            }
            if(screen!=null && !screen.getCurrentVideoUrl().isBlank()) {
                chip(g,0,"Авто",screen.isAutoNext(),mouseX,mouseY);
                chip(g,1,"Повтор",screen.isRepeat(),mouseX,mouseY);
            }
            String right=rightText();
            if(right!=null) {
                int rightWidth=font.width(right);
                boolean hoverRight=overRight(mouseX,mouseY,right);
                int color=cancelShown()?0xFFF0A070:GOLD;
                g.drawString(font,right,cardX+cardW-10-rightWidth,cardY+19,hoverRight?0xFFF7DE9D:0xFF000000|color,false);
                if(hoverRight) g.fill(cardX+cardW-10-rightWidth,cardY+29,cardX+cardW-10,cardY+30,0xFFF7DE9D);
            }
            String viewers="Глядачі: "+(viewerCount<0?"…":viewerCount);
            g.drawString(font,viewers,leftPos+imageWidth-36-font.width(viewers),topPos+13,MUTED,false);
        }
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(!construction && search!=null && search.isFocused() && key!=256) {
            if(key==257 || key==335) {if(view==View.SEARCH)requestSearch();return true;}
            if(search.keyPressed(key,scan,modifiers)) return true;
            if(key!=258) return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(!construction && button==0) {
            if(overBar(mx,my) && canSeekOnBar()) {
                seek((long)(barFraction(mx)*currentDuration())-currentPosition());
                return true;
            }
            var root=screen();
            if(root!=null && !root.getCurrentVideoUrl().isBlank()) {
                if(overChip(mx,my,0)) { Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),"auto_next",root.isAutoNext()?0L:1L)); return true; }
                if(overChip(mx,my,1)) { Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(),"repeat",root.isRepeat()?0L:1L)); return true; }
            }
            String right=rightText();
            if(overRight(mx,my,right)) {
                var up=nextUp();
                if(up!=null) Networking.INSTANCE.sendToServer(new PacketCancelNext(up.pos()));
                else jumpToCurrent();
                return true;
            }
            if(categoryOfCurrent(null)!=null && my>=cardY()+4 && my<cardY()+16 && mx>=cardX()+8 && mx<cardX()+cardW()-8) {
                jumpToCurrent();
                return true;
            }
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if(!construction && y>=topPos+100 && y<topPos+imageHeight-114) {
            page+=delta<0?1:-1;refreshEntries();return true;
        }
        return super.mouseScrolled(x,y,delta);
    }
    @Override public void removed() {
        saved=new Saved(view,origin,selected,voice,episode,results,query,libraryQuery,page,searchPage);
        poster.close();super.removed();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partialTick) {
        renderBackground(g);super.render(g,mx,my,partialTick);renderTooltip(g,mx,my);
        if(!construction && overBar(mx,my) && canSeekOnBar())
            g.renderTooltip(font,Component.literal(formatTime((long)(barFraction(mx)*currentDuration()))+" — клік, щоб перемотати"),mx,my);
        else if(!construction && screen()!=null && !screen().getCurrentVideoUrl().isBlank() && overChip(mx,my,0))
            g.renderTooltip(font,Component.literal("Автоперехід: після кінця серії сам запускати наступну"),mx,my);
        else if(!construction && screen()!=null && !screen().getCurrentVideoUrl().isBlank() && overChip(mx,my,1))
            g.renderTooltip(font,Component.literal("Повтор: після кінця серії почати її знову"),mx,my);
        else if(!construction && categoryOfCurrent(null)!=null && my>=cardY()+4 && my<cardY()+16 && mx>=cardX()+8 && mx<cardX()+cardW()-110)
            g.renderTooltip(font,Component.literal("Перейти до списку серій цього відео"),mx,my);
        if(!construction && !detail() && my>=topPos+80 && my<=topPos+96 && mx>=leftPos+12 && mx<leftPos+imageWidth-12)
            g.renderTooltip(font,font.split(Component.literal(status),imageWidth-30),mx,my);
        if(!construction && posterWidth()>0 && mx>=leftPos+12 && mx<leftPos+12+posterHeight()*2/3 && my>=topPos+100 && my<topPos+100+posterHeight()
                && poster.texture()==null) g.renderTooltip(font,Component.literal(poster.status()),mx,my);
        int viewersWidth=font.width("Глядачі: "+(viewerCount<0?"…":viewerCount));
        if(!construction && viewerCount>=0 && mx>=leftPos+imageWidth-36-viewersWidth && mx<leftPos+imageWidth-36
                && my>=topPos+9 && my<topPos+23) {
            String names=viewerNames.isEmpty()?"Ніхто не дивиться зараз.":String.join(", ",viewerNames);
            g.renderTooltip(font,font.split(Component.literal(names),240),mx,my);
        }
    }
}
