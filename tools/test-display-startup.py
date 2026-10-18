"""Compile the production Display against a controllable player; no Minecraft/VLC required.
Run: python tools/test-display-startup.py --javac <JDK17+/bin/javac> --java <JDK17+/bin/java>
"""
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--javac', default='javac')
parser.add_argument('--java', default='java')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
sources = {
'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public class Minecraft { public static Minecraft getInstance(){return new Minecraft();}
public void execute(Runnable r){r.run();} public static int getFps(){return 60;} }''',
'org/slf4j/Logger.java': '''package org.slf4j;
public interface Logger { default void info(String s,Object... o){} default void warn(String s,Object... o){}
default void debug(String s,Object... o){} }''',
'com/mojang/logging/LogUtils.java': '''package com.mojang.logging;
public class LogUtils { public static org.slf4j.Logger getLogger(){return new org.slf4j.Logger(){};} }''',
'org/watermedia/videolan4j/player/base/MediaPlayer.java': '''package org.watermedia.videolan4j.player.base;
public class MediaPlayer {}''',
'org/watermedia/videolan4j/player/embedded/videosurface/callback/BufferFormat.java': '''package org.watermedia.videolan4j.player.embedded.videosurface.callback;
public class BufferFormat {}''',
'org/watermedia/api/player/videolan/VideoPlayer.java': '''package org.watermedia.api.player.videolan;
import java.net.URI; import java.nio.ByteBuffer; import java.awt.Dimension; import java.util.*; import java.util.concurrent.Executor;
import org.watermedia.videolan4j.player.base.MediaPlayer;
import org.watermedia.videolan4j.player.embedded.videosurface.callback.BufferFormat;
public class VideoPlayer {
public static List<VideoPlayer> players=new ArrayList<>();
public String state="OPENING"; public long time; public int seeks,pauses; public boolean canPause=true,released;
private final Executor executor;
public VideoPlayer(Executor e){executor=e;players.add(this);}
public void startPaused(URI u,String[] options){state="OPENING";}
public void display(MediaPlayer p,ByteBuffer[] b,BufferFormat f){executor.execute(()->{});}
public void frame(){display(null,null,null);}
public void setRepeatMode(boolean b){} public void setVolume(int i){}
public int preRender(){return 42;} public String getStateName(){return state;}
public boolean isReady(){return !isEnded()&&!isBroken();}
public boolean isPlaying(){return state.equals("PLAYING");} public boolean isPaused(){return state.equals("PAUSED");}
public boolean isEnded(){return state.equals("ENDED");} public boolean isBroken(){return state.equals("ERROR");}
public boolean isValid(){return true;} public boolean isSeekAble(){return true;}
public boolean isLoading(){return state.equals("OPENING");} public boolean isBuffering(){return state.equals("BUFFERING");}
public long getTime(){return time;} public long getDuration(){return 100000;}
public int width(){return 640;} public int height(){return 360;} public Dimension dimension(){return new Dimension(640,360);}
public void seekTo(long t){seeks++;time=t;} public void setPauseMode(boolean b){if(canPause){pauses++;state=b?"PAUSED":"PLAYING";}}
public VideoPlayer raw(){return this;} public VideoPlayer mediaPlayer(){return this;} public VideoPlayer status(){return this;}
public boolean canPause(){return canPause;} public void stop(){state="STOPPED";} public void release(){released=true;}
}''',
'DisplayStartupTest.java': '''import com.nestworld.video.client.util.Display;
import org.watermedia.api.player.videolan.VideoPlayer;
public class DisplayStartupTest {
static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
static VideoPlayer latest(){return VideoPlayer.players.get(VideoPlayer.players.size()-1);}
static void expireRetry(Display d)throws Exception{var f=Display.class.getDeclaredField("retryAfterNanos");f.setAccessible(true);f.setLong(d,System.nanoTime()-1);}
public static void main(String[] args)throws Exception{
Display d=new Display("https://example.test/video.m3u8"); VideoPlayer p=latest();
d.syncPosition(5000); d.tick(); check(p.seeks==0&&p.pauses==0,"OPENING must not consume controls");
check(d.getTextureId()==-1,"Allocated placeholder is not a decoded frame");
p.state="PAUSED"; d.tick(); check(p.seeks==0&&p.pauses==0,"Wait for first native frame");
p.frame(); p.canPause=false; d.tick(); check(p.seeks==1&&p.time==5000,"Initial seek after frame");
check(p.pauses==0,"Keep unpause pending while cannot pause"); p.canPause=true;d.tick();
check(p.isPlaying(),"Pending unpause retried");check(d.getTextureId()==42,"Expose uploaded video frame");
d.setPlaying(false);d.tick();check(p.isPaused(),"Pause works after startup");
d.setPlaying(true);d.tick();p.state="ENDED";int count=VideoPlayer.players.size();d.tick();expireRetry(d);d.tick();
check(VideoPlayer.players.size()==count,"Do not restart an episode after decoded frames");
Display failed=new Display("https://example.test/video.m3u8");VideoPlayer old=latest();old.state="ENDED";
failed.syncPosition(9000);failed.tick();check(latest()==old,"Retry must be delayed");expireRetry(failed);failed.tick();
VideoPlayer retry=latest();check(retry!=old&&old.released,"Recreate failed startup once");
retry.state="PAUSED";retry.frame();failed.tick();check(retry.time==9000,"Retain requested timeline during retry");
Display twice=new Display("https://example.test/video.m3u8");latest().state="ERROR";twice.tick();expireRetry(twice);twice.tick();
VideoPlayer second=latest();second.state="ENDED";count=VideoPlayer.players.size();twice.tick();expireRetry(twice);twice.tick();
check(VideoPlayer.players.size()==count&&twice.isBroken(),"Second failure is terminal, not an infinite retry loop");
Display paused=new Display("https://example.test/video.m3u8");VideoPlayer stopped=latest();stopped.state="ENDED";paused.setPlaying(false);
count=VideoPlayer.players.size();paused.tick();expireRetry(paused);paused.tick();check(VideoPlayer.players.size()==count,"Do not retry while user paused");
System.out.println("PASS: startup controls, first frame, pause, bounded recovery, timeline, natural end");
}}
'''
}
with tempfile.TemporaryDirectory(prefix='nestworld-startup-test-') as directory:
    temp = pathlib.Path(directory)
    files = []
    for name, content in sources.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(str(path))
    files.append(str(root/'src/main/java/com/nestworld/video/client/util/Display.java'))
    subprocess.run([args.javac, '--release', '17', '-d', str(temp), *files], check=True)
    subprocess.run([args.java, '-cp', str(temp), 'DisplayStartupTest'], check=True)
