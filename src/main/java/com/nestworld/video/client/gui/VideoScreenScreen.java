package com.nestworld.video.client.gui;

import com.google.gson.Gson;
import com.nestworld.video.*;
import com.nestworld.video.block.*;
import com.nestworld.video.client.*;
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
    private record Row(String label, String hint, boolean enabled, Runnable action, String url) {
        Row(String label, String hint, boolean enabled, Runnable action) { this(label, hint, enabled, action, ""); }
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
    private Button assemble,play,pause,stop,quieter,louder,skipBack,skipForward,previousPage,nextPage,searchButton,qualityButton;
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
    private boolean detail() { return view==View.VOICES || view==View.EPISODES || view==View.SOURCES; }
    @Override protected void init() {
        imageWidth=Math.min(500,width-12); imageHeight=Math.min(372,height-12);
        super.init();
        if(menu.getBlockEntity()==null) { onClose(); return; }
        entries.clear(); search=null; searchButton=null; qualityButton=null;
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
            search=new CinemaEditBox(font,leftPos+13,topPos+58,imageWidth-(remote?108:26),20,Component.literal("Пошук"));
            search.setMaxLength(120); search.setHint(Component.literal(remote ? "Назва або частина назви…" : "Пошук у бібліотеці…"));
            search.setValue(remote ? query : libraryQuery);
            search.setResponder(value->{ if(remote) query=value; else {libraryQuery=value;page=0;refreshEntries();} });
            addRenderableWidget(search);
            if(remote) searchButton=button("Знайти",imageWidth-88,58,76,b->requestSearch());
        } else {
            button("‹ Назад",12,58,78,b->back());
            boolean anime=selected.getName()!=null && selected.getName().startsWith("AniUA:");
            button(Component.literal(selected.getDisplayTitle()),96,58,imageWidth-(anime?184:108),b->{
                view=View.VOICES;page=0;rebuildWidgets();
            }).setTooltip(Tooltip.create(Component.literal(selected.getDisplayTitle()+"\n"+selected.getSeason())));
            if(anime) button("Сезони",imageWidth-84,58,72,b->{
                if(loading) return;
                view=View.SEARCH;page=0;results=List.of();status="Завантажую сезони та пов’язані фільми…";
                rebuildWidgets();request("seasons",selected.getName().substring(6).trim());
            }).setTooltip(Tooltip.create(Component.literal("Усі сезони та пов’язані фільми")));
            poster.load(selected.getPoster());
        }
        int columns=view==View.EPISODES ? 2 : 1;
        int start=100;
        int left=detail()?76:12;
        visibleRows=Math.max(1,(imageHeight-114-start)/24)*columns;
        int rowWidth=(imageWidth-left-12-(columns-1)*6)/columns;
        for(int i=0;i<visibleRows;i++) {
            final int index=i;
            entries.add(button("",left+(i%columns)*(rowWidth+6),start+(i/columns)*24,rowWidth,b->{
                int n=page*visibleRows+index;
                if(n<rows.size() && rows.get(n).enabled()) rows.get(n).action().run();
            }));
        }
        int W=imageWidth, H=imageHeight;
        previousPage=button("‹",12,H-110,28,b->{page--;refreshEntries();});
        nextPage=button("›",W-40,H-110,28,b->{page++;refreshEntries();});
        qualityButton=button(qualityLabel(),W-40-8-96,H-110,96,b->cycleQuality());
        qualityButton.setTooltip(Tooltip.create(Component.literal("Максимальна якість відео (HLS). Потік перезапуститься.")));
        // Transport row: three big actions on the left, seek and volume tucked into one cluster on the right.
        int ty=H-34, cluster=30+4+30+10+22+38+22;
        int bw=(W-24-cluster-12-8)/3;
        boolean wide=bw>=96;
        play=button((wide?"▶  ":"")+(wide?tr("play").getString():"▶"),12,ty,bw,b->send("play"));
        pause=button((wide?"❚❚  ":"")+(wide?tr("pause").getString():"❚❚"),16+bw,ty,bw,b->send("pause"));
        stop=button((wide?"■  ":"")+(wide?tr("stop").getString():"■"),20+bw*2,ty,bw,b->send("stop"));
        ((CinemaButton)play).primary=true; ((CinemaButton)play).centered=true;
        ((CinemaButton)pause).centered=true; ((CinemaButton)stop).centered=true;
        int cx=W-12-cluster;
        skipBack=button("−10",cx,ty,30,b->seek(-10_000));
        skipForward=button("+10",cx+34,ty,30,b->seek(10_000));
        quieter=button("−",cx+64+10,ty,22,b->volume(-10));
        louder=button("+",W-12-22,ty,22,b->volume(10));
        skipBack.setTooltip(Tooltip.create(Component.literal("Перемотати назад на 10 секунд")));
        skipForward.setTooltip(Tooltip.create(Component.literal("Перемотати вперед на 10 секунд")));
        quieter.setTooltip(Tooltip.create(tr("local_volume"))); louder.setTooltip(Tooltip.create(tr("local_volume")));
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
                for(Title title:results) rows.add(new Row(title.label(),title.title()+"\n"+title.seasonLabel(),!loading,()->{
                    searchPage=page; status="Завантажую серії: "+title.title()+"…";request("load",title.slug());
                }));
            }
            case LIBRARY -> {
                for(Category c:categories()) if(CatalogueOrder.matches(c.getDisplayTitle(),libraryQuery))
                    rows.add(new Row(c.getDisplayTitle(),c.getDisplayTitle(),true,()->{origin=View.LIBRARY;open(c);}));
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
                    String label=dub+"  ("+available+"/"+episodes.size()+" сер.)";
                    String hint=enabled
                            ? dub+"\n"+available+" з "+episodes.size()+" серій доступні."
                            : dub+"\nУ цій озвучці зараз немає доступного відеопотоку.";
                    rows.add(new Row(label,hint,enabled,()->{voice=dub;view=View.EPISODES;page=0;rebuildWidgets();}));
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
                        String label=season+"Серія "+number+(playable.isEmpty()?" — недоступна":"");
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
    private static String url(Video v) {return v.getUrl()==null?"":v.getUrl();}
    private static boolean playable(Video v) {return v.getUrl()!=null && !v.getUrl().isBlank();}
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
                button.setMessage(Component.literal((current?"▶ ":"")+row.label()));
                if(button instanceof CinemaButton cb) cb.selected=current;
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
    private void volume(int delta) {
        var root=screen();var display=root==null?null:ClientVideoManager.getInstance().getDisplay(root.getBlockPos());
        if(display!=null) display.setVolume(display.getVolume()+delta);
    }
    // Card geometry shared by drawing, hit-testing and tooltips (relative to the card's top-left corner).
    private int cardX() { return leftPos+12; }
    private int cardY() { return topPos+imageHeight-84; }
    private int cardW() { return imageWidth-24; }
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
        var root=screen();
        if(root!=null) Networking.INSTANCE.sendToServer(new PacketControlVideo(root.getBlockPos(),"seek_by",deltaMs));
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
        boolean audio=root!=null && ClientVideoManager.getInstance().getDisplay(root.getBlockPos())!=null;
        if(quieter!=null) quieter.active=audio;
        if(louder!=null) louder.active=audio;
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
            text(g,Component.literal(line),detail()?76:12,84,MUTED,imageWidth-(detail()?88:24));
            if(detail()) {
                int h=Math.min(84,imageHeight-192),w=Math.min(56,h*2/3);
                g.fill(leftPos+12,topPos+100,leftPos+12+w,topPos+100+h,0xFF304456);
                if(poster.texture()!=null) {
                    float scale=Math.min(w/(float)poster.width(),h/(float)poster.height());
                    int pw=(int)(poster.width()*scale),ph=(int)(poster.height()*scale);
                    g.blit(poster.texture(),leftPos+12+(w-pw)/2,topPos+100+(h-ph)/2,pw,ph,0,0,poster.width(),poster.height(),poster.width(),poster.height());
                } else {
                    String title=selected.getDisplayTitle();
                    String initial=title==null||title.isBlank()?"?":title.substring(0,1).toUpperCase();
                    g.pose().pushPose();
                    g.pose().translate(leftPos+12+w/2f,topPos+100+h/2f-8,0);
                    g.pose().scale(2f,2f,1f);
                    g.drawCenteredString(font,initial,0,0,MUTED);
                    g.pose().popPose();
                }
            }
            if(rows.isEmpty()) {
                String empty=loading?"Завантаження…":detail() && "Фільм".equals(selected.getSeason())
                        ? "Це фільм. AniUA не передав доступного відеопотоку для цього релізу."
                        : detail()?"У цьому релізі немає серій.":
                        view==View.LIBRARY?"Нічого не знайдено. Знайди аніме у вкладці AniUA.":status;
                if(!empty.equals(line)) g.drawWordWrap(font,Component.literal(empty),leftPos+(detail()?76:12),topPos+103,imageWidth-(detail()?88:24),MUTED);
            }
            int pages=Math.max(1,(rows.size()+visibleRows-1)/visibleRows);
            g.drawCenteredString(font,(page+1)+" / "+pages+"  ·  "+rows.size()+" "+(view==View.EPISODES?"серій":"записів"),
                    leftPos+imageWidth/2-20,topPos+imageHeight-104,MUTED);
            // "Now playing" card: what is on the screen, its state, time and a progress bar.
            String name=screen==null || screen.getCurrentVideoUrl().isBlank()?tr("none").getString():screen.getCurrentVideoTitle();
            var display=screen==null?null:ClientVideoManager.getInstance().getDisplay(screen.getBlockPos());
            boolean isPlaying=screen!=null && screen.isPlaying(), isStopped=screen==null || screen.isStopped();
            int stateColor=isPlaying?GOLD:isStopped?0xFF6B7885:0xFF6FA8DC;
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
            if(categoryOfCurrent(null)!=null) {
                String link="↩ До списку серій";
                int linkWidth=font.width(link);
                boolean hoverLink=mouseX>=cardX+cardW-12-linkWidth && mouseX<cardX+cardW-8 && mouseY>=cardY+17 && mouseY<cardY+30;
                g.drawString(font,link,cardX+cardW-10-linkWidth,cardY+19,hoverLink?0xFFF7DE9D:GOLD,false);
                if(hoverLink) g.fill(cardX+cardW-10-linkWidth,cardY+29,cardX+cardW-10,cardY+30,0xFFF7DE9D);
            }
            // Volume read-out sits between its two buttons.
            int volumeX=leftPos+imageWidth-12-22-19;
            g.drawCenteredString(font,display==null?"—":display.getVolume()+"%",volumeX,topPos+imageHeight-28,TEXT);
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
            if(categoryOfCurrent(null)!=null && my>=cardY()+4 && my<cardY()+30 && mx>=cardX()+8 && mx<cardX()+cardW()-8
                    && (my<cardY()+16 || mx>=cardX()+cardW()-12-font.width("↩ До списку серій"))) {
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
        else if(!construction && categoryOfCurrent(null)!=null && my>=cardY()+4 && my<cardY()+16 && mx>=cardX()+8 && mx<cardX()+cardW()-110)
            g.renderTooltip(font,Component.literal("Перейти до списку серій цього відео"),mx,my);
        if(!construction && !detail() && my>=topPos+80 && my<=topPos+96 && mx>=leftPos+12 && mx<leftPos+imageWidth-12)
            g.renderTooltip(font,font.split(Component.literal(status),imageWidth-30),mx,my);
        if(!construction && detail() && mx>=leftPos+12 && mx<leftPos+70 && my>=topPos+100 && my<topPos+imageHeight-114
                && poster.texture()==null) g.renderTooltip(font,Component.literal(poster.status()),mx,my);
        int viewersWidth=font.width("Глядачі: "+(viewerCount<0?"…":viewerCount));
        if(!construction && viewerCount>=0 && mx>=leftPos+imageWidth-36-viewersWidth && mx<leftPos+imageWidth-36
                && my>=topPos+9 && my<topPos+23) {
            String names=viewerNames.isEmpty()?"Ніхто не дивиться зараз.":String.join(", ",viewerNames);
            g.renderTooltip(font,font.split(Component.literal(names),240),mx,my);
        }
    }
}
