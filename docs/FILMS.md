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
- Це список, який складає адмін; автопошук (TMDB + Eneyida) поки не реалізований.
