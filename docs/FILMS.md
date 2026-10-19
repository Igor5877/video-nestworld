# Фільми (ручний список)

Файл `config/nestworld-films.json` на сервері, потім команда `/films reload` (оп 2+).

```json
[
  {
    "title": "Назва фільму",
    "year": "2014",
    "poster": "https://…/poster.jpg",
    "description": "Короткий опис",
    "dub": "Озвучка",
    "rating": "pg_13",
    "adult": false,
    "sources": [
      "https://ashdi.vip/vod/123456",
      "https://example.org/резервне/film.m3u8"
    ]
  }
]
```

- `sources` перевіряються по черзі: перше робоче виграє, мертве джерело не ховає фільм.
- Підтримуються лише пряме `.m3u8`/`.mp4` по https і сторінки `ashdi.vip/vod/…`.
- `adult: true` ховає фільм за правом `nestworldvideo.adult` так само, як 18+ аніме.
- У меню екрана фільми показуються у вкладці «Фільми» (у «Бібліотеці» їх немає).

## Пошук через TMDB

1. Безкоштовний ключ: themoviedb.org → Settings → API. Встав у `config/nestworldvideo-server-content.toml`, секція `[films]`, `tmdb_api_key` (v3 ключ або v4 read token). Ключ лишається на сервері.
2. `/films search Інтерстеллар`: список із TMDB, клац по рядку підставляє `/films add <id> `.
3. `/films add <id> <джерело> [<резервне> …]`: назва українською, рік, постер, опис і позначка 18+ беруться з TMDB, фільм записується в `nestworld-films.json` і одразу з'являється у вкладці «Фільми».
- 18+: прапорець `adult` TMDB або сертифікація NC-17/18/X у UA, US, GB, DE.
- TMDB дає лише дані про фільм. Посилання на потік (Ashdi чи m3u8) вказуєш ти; автопошук потоку (Eneyida) ще не реалізований.
