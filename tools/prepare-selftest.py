#!/usr/bin/env python3
"""Create fixtures only in build/selftest-run, then run ./gradlew runClient -Pselftest.
Requires Python 3 and ffmpeg. Does not edit an existing game profile.
"""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1] / 'build/selftest-run'
(root / 'config').mkdir(parents=True, exist_ok=True)
(root / 'options.txt').write_text('lang:uk_ua\nguiScale:2\nfullscreen:false\npauseOnLostFocus:false\n')
video = root / 'test-pattern.mp4'
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                'testsrc2=size=640x360:rate=24', '-t', '60', '-c:v', 'libx264', '-preset',
                'ultrafast', '-pix_fmt', 'yuv420p', '-y', str(video)], check=True)
categories = []
for c, name in enumerate(['Кіно', 'Мультфільми', 'Музика', 'Подорожі', 'Наука', 'Ігри', 'Архів']):
    categories.append({'name': name, 'videos': [
        {'title': 'Тестовий фільм' if c == 0 and v == 0 else f'{name} — відео {v+1}',
         'description': 'Тестовий каталог для перевірки пошуку й сторінок.',
         'url': video.as_uri() if c == 0 and v == 0 else f'https://example.invalid/{c}/{v}.mp4'}
        for v in range(8)]})
(root / 'config/videos.json').write_text(json.dumps({'categories': categories}, ensure_ascii=False), encoding='utf-8')
print('Fixtures ready:', root)
