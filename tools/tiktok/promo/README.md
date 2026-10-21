# Промо-ролик для TikTok (15,5 с, 1080×1920)

Блокова 3D-зала з екраном мода, намальована кодом, і 8-бітна музика, синтезована кодом. Рендериться рушієм
[huashu-art-motion](https://github.com/alchaincyf/huashu-art-motion) (MIT): потрібні uv, ffmpeg і Chromium для Playwright.

```sh
git clone https://github.com/alchaincyf/huashu-art-motion && cd huashu-art-motion
cp <video-nestworld>/tools/tiktok/promo/eras_nestworld.js scripts/engine/
uv run <video-nestworld>/tools/tiktok/promo/synth_nestworld.py music.wav
uv run --with playwright python scripts/engine/render.py --film nestworld --width 1080 --height 1920 --fps 30 --audio music.wav --out nestworld_promo.mp4
```

Текстури, персонажі й картинка на екрані — власні; кадрів і звуку з гри чи аніме тут немає.
