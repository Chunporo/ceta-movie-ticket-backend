#!/usr/bin/env python3
"""Imports real Vietnamese now-playing / upcoming movies from TMDB into a running backend through its admin API.

    TMDB_API_KEY=... ADMIN_EMAIL=... ADMIN_PASSWORD=... python3 scripts/import_tmdb.py [--base http://localhost:8080] [--now 12] [--soon 6]

Movies already present (same slug) are skipped, so it can be re-run. Posters stay on TMDB's CDN (only the URL is stored).
New NOW_SHOWING movies also get a week of showtimes so they can be booked. Data (c) TMDB; this product uses the TMDB API
but is not endorsed or certified by TMDB.
"""
import argparse, json, os, re, sys, unicodedata, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TMDB = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p/w500"
VN = timezone(timedelta(hours=7))

# TMDB genre id -> our genre slug.
GENRE = {28: "hanh-dong", 12: "hanh-dong", 80: "hanh-dong", 10752: "hanh-dong", 37: "hanh-dong", 16: "hoat-hinh", 10751: "hoat-hinh",
         35: "hai", 27: "kinh-di", 53: "kinh-di", 9648: "kinh-di", 878: "sci-fi", 14: "sci-fi", 18: "tam-ly", 36: "tam-ly",
         99: "tam-ly", 10402: "tam-ly", 10770: "tam-ly", 10749: "tinh-cam"}
AGE = {"P": "P", "K": "K", "T13": "T13", "T16": "T16", "T18": "T18", "C": "T18", "C18": "T18", "C16": "T16", "C13": "T13"}


def http(method, url, body=None, token=None):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {url.split('?')[0]} -> {e.code} {e.read()[:200].decode(errors='replace')}")


def tmdb(path, key, **params):
    params.update(api_key=key, language="vi-VN")
    return http("GET", f"{TMDB}{path}?{urllib.parse.urlencode(params)}")


def slugify(text):
    text = unicodedata.normalize("NFKD", text.replace("đ", "d").replace("Đ", "D")).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:140] or "movie"


def age_rating(details):
    for country in details.get("release_dates", {}).get("results", []):
        if country["iso_3166_1"] == "VN":
            for rd in country["release_dates"]:
                if rd.get("certification") in AGE:
                    return AGE[rd["certification"]]
    return "T18" if details.get("adult") else "T13"


def to_request(d, status, genre_ids):
    overview = (d.get("overview") or "").strip()
    if not overview:
        return None  # a movie without a synopsis is not worth listing
    crew = d.get("credits", {}).get("crew", [])
    cast = [c["name"] for c in d.get("credits", {}).get("cast", [])[:4]]
    trailer = next((f"https://www.youtube.com/watch?v={v['key']}" for v in d.get("videos", {}).get("results", [])
                    if v["site"] == "YouTube" and v["type"] == "Trailer"), None)
    ids = sorted({genre_ids[GENRE[g["id"]]] for g in d.get("genres", []) if GENRE.get(g["id"]) in genre_ids})
    return {
        "title": d["title"], "slug": f"{slugify(d['title'])}-{d['id']}"[:150], "posterUrl": IMG + d["poster_path"],
        "ageRating": age_rating(d), "status": status, "duration": d.get("runtime") or 105, "releaseDate": d.get("release_date") or None,
        "trailerUrl": trailer, "director": next((c["name"] for c in crew if c["job"] == "Director"), None),
        "language": (d.get("original_language") or "").upper()[:10] or None, "description": overview,
        "ratingScore": round(min(5.0, d.get("vote_average", 0) / 2), 1) if d.get("vote_count", 0) >= 20 else None,
        "cast": cast, "formats": ["TWO_D"], "genreIds": ids,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--now", type=int, default=12)
    ap.add_argument("--soon", type=int, default=6)
    ap.add_argument("--showtimes-for-existing", action="store_true", help="also create showtimes for movies imported earlier")
    ap.add_argument("--showtimes-only", action="store_true", help="skip TMDB (no key needed): only create showtimes for imported now-showing movies")
    args = ap.parse_args()
    if args.showtimes_only:
        args.showtimes_for_existing = True
    key = os.environ.get("TMDB_API_KEY") or (None if args.showtimes_only else sys.exit("Set TMDB_API_KEY"))
    email, password = os.environ.get("ADMIN_EMAIL", "admin@gmail.com"), os.environ.get("ADMIN_PASSWORD", "admin@123")
    api = args.base + "/api/v1"

    token = http("POST", args.base + "/api/auth/login", {"email": email, "password": password})["accessToken"]
    genre_ids = {g["slug"]: g["id"] for g in http("GET", api + "/genres")}
    existing = {m["slug"] for m in http("GET", api + "/movies")}
    rooms = [r for r in http("GET", args.base + "/api/rooms", token=token)]

    wanted = []
    for kind, status, limit in (() if args.showtimes_only else (("now_playing", "NOW_SHOWING", args.now), ("upcoming", "COMING_SOON", args.soon))):
        for page in (1, 2, 3):
            for m in tmdb(f"/movie/{kind}", key, region="VN", page=page)["results"]:
                if m.get("poster_path") and m["id"] not in {w[0] for w in wanted} and sum(1 for w in wanted if w[1] == status) < limit:
                    wanted.append((m["id"], status))

    created = []
    for tmdb_id, status in wanted:
        d = tmdb(f"/movie/{tmdb_id}", key, append_to_response="credits,videos,release_dates")
        body = to_request(d, status, genre_ids)
        if body is None or body["slug"] in existing:
            print("skip", d.get("title")); continue
        movie = http("POST", api + "/movies", body, token)
        created.append((movie, status)); print("added", status, body["title"])

    # A week of showtimes for the TMDB NOW_SHOWING movies (new ones, or with --showtimes-for-existing every imported one):
    # a room that is already busy answers 409, so the next room is tried.
    movies = [m for m, st in created if st == "NOW_SHOWING"]
    if args.showtimes_for_existing:
        movies = [m for m in http("GET", api + "/movies") if m["status"] == "NOW_SHOWING" and re.search(r"-\d+$", m["slug"])]
    # Never add a second showing of the same movie at the same minute (another room would accept it).
    taken = {(st["movie"]["id"], st["startTime"]) for st in http("GET", api + "/showtimes")}
    slots, made, refused = [(9, 15), (11, 45), (14, 30), (17, 20), (20, 50), (22, 40)], 0, 0
    for day in range(7):
        for i, movie in enumerate(movies):
            for s_idx in range(len(slots)):
                if (i + s_idx + day) % 3:  # each movie takes about a third of the slots of a day
                    continue
                h, mi = slots[s_idx]
                start = (datetime.now(VN) + timedelta(days=day)).replace(hour=h, minute=mi, second=0, microsecond=0)
                if start < datetime.now(VN) + timedelta(hours=1):
                    continue
                if (movie["id"], start.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")) in taken:
                    continue
                end = start + timedelta(minutes=(movie.get("duration") or 105) + 15)
                for k in range(len(rooms)):
                    room = rooms[(i + s_idx + k) % len(rooms)]
                    try:
                        # An IMAX room shows IMAX; elsewhere about one show in five is 3D and one in four is dubbed.
                        fmt = "IMAX" if "IMAX" in (room.get("name") or "").upper() else ("THREE_D" if (i + day + s_idx) % 5 == 0 else "TWO_D")
                        http("POST", api + "/showtimes", {"movieId": movie["id"], "roomId": room["id"],
                             "startTime": start.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                             "endTime": end.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "basePrice": 85000,
                             "format": fmt, "languageMode": "DUB" if (i + s_idx) % 4 == 0 else "SUB"}, token)
                        made += 1
                        break
                    except RuntimeError as e:
                        if "409" not in str(e):
                            raise
                else:
                    refused += 1
    n = refused
    print(f"done: {len(created)} movies added, {made} showtimes created, {n} slots found no free room")


if __name__ == "__main__":
    main()
