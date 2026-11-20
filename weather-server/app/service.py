"""缓存感知服务层：城市/实时/逐时/逐日/AQI/分钟降水/预警/雷达/生活指数。

作者: zhenhua
版本: 1.2
"""
import json
import math
import re
import time
from datetime import datetime, timedelta, date

from . import db, upstream, mapping
from .indices import calc_indices

TTL = dict(realtime=600, hourly=3600, daily=10800, aqi=600, aqi_fc=3600,
           nowcast=300, alert=300, radar=600, index=10800)

_alert_sync_state = {"ts": 0}


def _now():
    return datetime.now()


def _parse_dt(s):
    s = str(s).replace(" ", "T")
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        return datetime.strptime(s[:10], "%Y-%m-%d")


# ---------------- city ----------------

def get_city(city_id):
    rows = db.q("SELECT * FROM city WHERE id=%s", (city_id,))
    return rows[0] if rows else None


def _upsert_geo(g):
    code = f"om:{g['id']}"
    pop = g.get("population")
    db.x("""INSERT INTO city(source_code,name,name_en,admin1,admin2,country,province,lat,lon,tz,pop)
            VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON DUPLICATE KEY UPDATE name=VALUES(name),lat=VALUES(lat),lon=VALUES(lon),pop=VALUES(pop)""",
         (code, g.get("name"), g.get("name_en"), g.get("admin1"), g.get("admin2"),
          g.get("country"), (g.get("admin1") or "")[:32],
          g.get("latitude"), g.get("longitude"), g.get("timezone") or "Asia/Shanghai",
          int(pop) if pop else None))
    return code


_SUF = ("特别行政区", "壮族自治区", "回族自治区", "维吾尔自治区", "自治区",
        "自治州", "地区", "省", "市", "县", "区", "州", "盟", "旗")


def _norm_geo(s):
    """归一化地名：循环去行政后缀，用于去重（义乌=义乌市，杭州=杭州市）。"""
    s = (s or "").strip()
    changed = True
    while changed:
        changed = False
        for suf in _SUF:
            if s.endswith(suf) and len(s) > len(suf):
                s = s[: -len(suf)]
                changed = True
                break
    return s


def _dedupe(rows):
    """同名只留一条（按已排序的优先级：人口/行政级别高的在前）；
    中英文同写（Dongguan=东莞）也合并。"""
    ascii_ok = any(not (r["name"] or "").isascii() for r in rows)
    seen, out = set(), []
    for r in rows:
        nm0 = (r["name"] or "").strip()
        if ascii_ok and nm0.isascii() and not (r.get("pop") or 0):
            continue  # 有中文命中时，丢掉无人口的纯英文噪声条目（如浙江某 Dongguan 村）
        keys = set()
        nm = _norm_geo(r["name"])
        if nm:
            keys.add(nm)
        en = (r.get("name_en") or "").strip().lower()
        if en:
            keys.add(en)
        if keys & seen:
            continue
        seen |= keys
        out.append(r)
    return out


def search_city(qs, limit=10):
    existing = _dedupe(db.q(
        "SELECT * FROM city WHERE name LIKE %s OR name_en LIKE %s ORDER BY pop DESC LIMIT %s",
        (f"%{qs}%", f"%{qs}%", limit * 3)))
    if len(existing) >= 3:
        return existing[:limit]
    try:
        queries = [qs]
        if not qs.endswith(("市", "县", "区", "州", "盟", "旗")):
            queries.append(qs + "市")
        codes, gids = [], set()
        for qy in queries:
            for g in upstream.geocode(qy, count=limit).get("results") or []:
                if g.get("id") in gids:
                    continue
                gids.add(g["id"])
                codes.append(_upsert_geo(g))
        rows = db.q("SELECT * FROM city WHERE source_code IN (%s) ORDER BY pop DESC LIMIT %s" %
                    (",".join(["%s"] * len(codes)), limit * 2), codes) if codes else []
        if qs.isascii():
            ql = qs.lower()
            rows = [r for r in rows if (r.get("pop") or 0) >= 10000
                    or (r.get("name_en") or "").strip().lower() == ql]
        exist_keys = set()
        for r in existing:
            if _norm_geo(r["name"]):
                exist_keys.add(_norm_geo(r["name"]))
            if r.get("name_en"):
                exist_keys.add(r["name_en"].strip().lower())
        merged = existing + [r for r in rows
                             if not ({_norm_geo(r["name"]), (r.get("name_en") or "").strip().lower()} & exist_keys)]
        merged.sort(key=lambda r: -(int(r["pop"]) if r.get("pop") else 0))
        return _dedupe(merged)[:limit]
    except Exception:
        return existing


def nearby_city(lat, lon):
    rows = db.q("SELECT id,name,admin1,lat,lon FROM city")
    if not rows:
        return None
    def d2(r):
        return (float(r["lat"]) - float(lat)) ** 2 + (float(r["lon"]) - float(lon)) ** 2
    best = min(rows, key=d2)
    if d2(best) <= 0.36 ** 2:  # 约 40km 内直接用库内城市
        return best
    # 库内最近城市太远：逆地理拿城市名→回源收录，解决「定位到非种子城市不准」
    for name in (reverse := upstream.reverse_geo(lat, lon), reverse.rstrip("市盟旗")):
        if not name:
            continue
        try:
            hits = search_city(name, 8)
        except Exception:
            hits = []
        if hits:
            h = min(hits, key=d2)
            if d2(h) < d2(best):
                return h
    return best


# ---------------- weather (Open-Meteo) ----------------

def refresh_realtime(city):
    data = upstream.om_forecast(city["lat"], city["lon"], city["tz"],
                                forecast_days=2, hours=False)
    cur = data.get("current") or {}
    if not cur:
        raise RuntimeError("empty current")
    daily = data.get("daily") or {}
    sunrise = (daily.get("sunrise") or [""])[0][-5:]
    sunset = (daily.get("sunset") or [""])[0][-5:]
    code = cur.get("weather_code")
    is_day = int(cur.get("is_day") or 1)
    db.x("""INSERT INTO realtime(city_id,cond_code,cond_text,temp,feels,humidity,pressure,
            wind_dir,wind_speed,wind_gust,wind_scale,precip,uv,is_day,sunrise,sunset,obs_time,updated_at)
            VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON DUPLICATE KEY UPDATE cond_code=VALUES(cond_code),cond_text=VALUES(cond_text),
            temp=VALUES(temp),feels=VALUES(feels),humidity=VALUES(humidity),pressure=VALUES(pressure),
            wind_dir=VALUES(wind_dir),wind_speed=VALUES(wind_speed),wind_gust=VALUES(wind_gust),
            wind_scale=VALUES(wind_scale),precip=VALUES(precip),uv=VALUES(uv),is_day=VALUES(is_day),
            sunrise=VALUES(sunrise),sunset=VALUES(sunset),obs_time=VALUES(obs_time),updated_at=VALUES(updated_at)""",
         (city["id"], code, mapping.cond_text(code, is_day), cur.get("temperature_2m"),
          cur.get("apparent_temperature"), cur.get("relative_humidity_2m"),
          round(cur.get("pressure_msl") or 0), cur.get("wind_direction_10m"),
          cur.get("wind_speed_10m"), cur.get("wind_gusts_10m"),
          mapping.wind_scale(cur.get("wind_speed_10m")), cur.get("precipitation"),
          None, is_day, sunrise, sunset, _parse_dt(cur.get("time")), _now()))
    return get_realtime(city["id"], fresh=True)


def get_realtime(city_id, fresh=False):
    rows = db.q("SELECT * FROM realtime WHERE city_id=%s", (city_id,))
    if not rows:
        return None
    r = rows[0]
    r["wind_dir_text"] = mapping.wind_dir_text(r["wind_dir"])
    return r


def weather_realtime(city):
    r = get_realtime(city["id"])
    if r and (r["updated_at"] and (_now() - r["updated_at"]).total_seconds() < TTL["realtime"]):
        return r, False
    try:
        return refresh_realtime(city), False
    except Exception:
        return r, True


def refresh_hourly(city, hours=48):
    data = upstream.om_forecast(city["lat"], city["lon"], city["tz"],
                                forecast_days=3, hours=True)
    h = data.get("hourly") or {}
    times = h.get("time") or []
    now_local = _now()
    rows = []
    for i, t in enumerate(times):
        ts = _parse_dt(t)
        if ts < now_local - timedelta(hours=1):
            continue
        code = (h.get("weather_code") or [None] * len(times))[i]
        rows.append((city["id"], ts, code, mapping.cond_text(code),
                     (h.get("temperature_2m") or [None] * len(times))[i],
                     (h.get("precipitation_probability") or [None] * len(times))[i],
                     (h.get("precipitation") or [None] * len(times))[i],
                     (h.get("wind_direction_10m") or [None] * len(times))[i],
                     (h.get("wind_speed_10m") or [None] * len(times))[i],
                     mapping.wind_scale((h.get("wind_speed_10m") or [None] * len(times))[i]),
                     (h.get("relative_humidity_2m") or [None] * len(times))[i],
                     (h.get("uv_index") or [None] * len(times))[i]))
        if len(rows) >= hours:
            break
    if rows:
        db.x("DELETE FROM hourly WHERE city_id=%s AND ts < %s", (city["id"], now_local - timedelta(days=2)))
        c = db.conn()
        try:
            with c.cursor() as cur:
                cur.executemany("""INSERT INTO hourly(city_id,ts,cond_code,cond_text,temp,pop,precip_mm,
                        wind_dir,wind_speed,wind_scale,humidity,uv)
                        VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        ON DUPLICATE KEY UPDATE cond_code=VALUES(cond_code),cond_text=VALUES(cond_text),
                        temp=VALUES(temp),pop=VALUES(pop),precip_mm=VALUES(precip_mm),
                        wind_dir=VALUES(wind_dir),wind_speed=VALUES(wind_speed),
                        wind_scale=VALUES(wind_scale),humidity=VALUES(humidity),uv=VALUES(uv)""", rows)
        finally:
            c.close()
    return len(rows)


def get_hourly(city, hours=24):
    stale = False
    last = db.q("""SELECT COUNT(*) AS n, MAX(ts) AS newest FROM hourly
                   WHERE city_id=%s AND ts>=%s""", (city["id"], _now()))[0]
    n_now, newest = last["n"], last["newest"]
    expired = newest is None or (
        _now() + timedelta(hours=hours) - newest).total_seconds() > TTL["hourly"]
    if n_now < max(6, int(hours * 0.8)) or expired:
        try:
            refresh_hourly(city, max(48, hours))
        except Exception:
            stale = n_now == 0
    rows = db.q("""SELECT ts,cond_code,cond_text,temp,pop,precip_mm,wind_dir,wind_speed,
                   wind_scale,humidity,uv FROM hourly
                   WHERE city_id=%s AND ts>=%s ORDER BY ts LIMIT %s""",
                (city["id"], _now(), hours))
    for r in rows:
        r["wind_dir_text"] = mapping.wind_dir_text(r["wind_dir"])
    return rows, stale


def refresh_daily(city, days=16):
    data = upstream.om_forecast(city["lat"], city["lon"], city["tz"],
                                forecast_days=days, hours=False)
    d = data.get("daily") or {}
    dates = d.get("time") or []
    rows = []
    for i, ds in enumerate(dates):
        def g(k):
            return (d.get(k) or [None] * len(dates))[i]
        rows.append((city["id"], ds[:10], g("weather_code"), g("weather_code"),
                     mapping.cond_text(g("weather_code")),
                     g("temperature_2m_max"), g("temperature_2m_min"),
                     g("precipitation_probability_max"), g("wind_direction_10m_dominant"),
                     g("wind_speed_10m_max"), (g("sunrise") or "")[-5:], (g("sunset") or "")[-5:],
                     g("uv_index_max")))
    if rows:
        c = db.conn()
        try:
            with c.cursor() as cur:
                cur.executemany("""INSERT INTO daily(city_id,d,cond_day,cond_night,cond_text,tmax,tmin,
                        pop,wind_dir_day,wind_speed_day,sunrise,sunset,uv_max)
                        VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        ON DUPLICATE KEY UPDATE cond_day=VALUES(cond_day),cond_night=VALUES(cond_night),
                        cond_text=VALUES(cond_text),tmax=VALUES(tmax),tmin=VALUES(tmin),pop=VALUES(pop),
                        wind_dir_day=VALUES(wind_dir_day),wind_speed_day=VALUES(wind_speed_day),
                        sunrise=VALUES(sunrise),sunset=VALUES(sunset),uv_max=VALUES(uv_max)""", rows)
        finally:
            c.close()
    return len(rows)


def get_daily(city, days=15):
    stale = False
    today = date.today()
    n = db.q("SELECT COUNT(*) AS n FROM daily WHERE city_id=%s AND d>=%s AND d<%s",
             (city["id"], today, today + timedelta(days=days)))[0]["n"]
    if n < days * 0.8:
        try:
            refresh_daily(city, days + 1)
        except Exception:
            stale = n == 0
    rows = db.q("""SELECT d,cond_text,tmax,tmin,pop,wind_dir_day,wind_speed_day,sunrise,sunset,uv_max
                   FROM daily WHERE city_id=%s AND d>=%s ORDER BY d LIMIT %s""",
                (city["id"], today, days))
    for r in rows:
        r["wind_dir_text"] = mapping.wind_dir_text(r["wind_dir_day"])
    return rows, stale


# ---------------- air quality ----------------

def refresh_aqi(city):
    data = upstream.aqi_current_hourly(city["lat"], city["lon"], city["tz"], days=5)
    cur = data.get("current") or {}
    if not cur:
        raise RuntimeError("empty aqi")
    lvl, advice = mapping.aqi_level(cur.get("us_aqi"))
    pp = mapping.primary_pollutant(cur.get("pm2_5"), cur.get("pm10"),
                                   cur.get("ozone"), cur.get("nitrogen_dioxide"))
    db.x("""INSERT INTO aqi_realtime(city_id,aqi,level,primary_pollutant,pm25,pm10,o3,no2,so2,co,
            health_advice,updated_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON DUPLICATE KEY UPDATE aqi=VALUES(aqi),level=VALUES(level),
            primary_pollutant=VALUES(primary_pollutant),pm25=VALUES(pm25),pm10=VALUES(pm10),
            o3=VALUES(o3),no2=VALUES(no2),so2=VALUES(so2),co=VALUES(co),
            health_advice=VALUES(health_advice),updated_at=VALUES(updated_at)""",
         (city["id"], cur.get("us_aqi"), lvl, pp, cur.get("pm2_5"), cur.get("pm10"),
          cur.get("ozone"), cur.get("nitrogen_dioxide"), cur.get("sulphur_dioxide"),
          cur.get("carbon_monoxide"), advice, _now()))
    h = data.get("hourly") or {}
    buckets = {}
    for i, t in enumerate(h.get("time") or []):
        v = (h.get("us_aqi") or [None] * len(h.get("time", [])))[i]
        if v is None:
            continue
        d = t[:10]
        buckets[d] = max(buckets.get(d, 0), int(v))
    if buckets:
        c = db.conn()
        try:
            with c.cursor() as cur2:
                cur2.executemany("""INSERT INTO aqi_forecast(city_id,d,aqi_max) VALUES(%s,%s,%s)
                                   ON DUPLICATE KEY UPDATE aqi_max=VALUES(aqi_max)""",
                                 [(city["id"], d, v) for d, v in buckets.items()])
        finally:
            c.close()
    return get_aqi_realtime(city["id"])


def get_aqi_realtime(city_id):
    rows = db.q("SELECT * FROM aqi_realtime WHERE city_id=%s", (city_id,))
    return rows[0] if rows else None


def air_realtime(city):
    r = get_aqi_realtime(city["id"])
    if r and (_now() - r["updated_at"]).total_seconds() < TTL["aqi"]:
        return r, False
    try:
        return refresh_aqi(city), False
    except Exception:
        return r, True


def air_forecast(city, days=5):
    r, stale = air_realtime(city)
    today = date.today()
    rows = db.q("SELECT d,aqi_max FROM aqi_forecast WHERE city_id=%s AND d>=%s ORDER BY d LIMIT %s",
                (city["id"], today, days))
    out = []
    for row in rows:
        lvl, _ = mapping.aqi_level(row["aqi_max"])
        out.append({"date": str(row["d"]), "aqiMax": row["aqi_max"], "level": lvl})
    return out, stale


# ---------------- nowcast (分钟级降水) ----------------

def _geo_key(lat, lon):
    return f"nc:{round(float(lat), 2):.2f},{round(float(lon), 2):.2f}"


def nowcast(lat, lon):
    k = _geo_key(lat, lon)
    row = db.q("SELECT payload,updated_at FROM geo_cache WHERE k=%s", (k,))
    if row and (_now() - row[0]["updated_at"]).total_seconds() < TTL["nowcast"]:
        return json.loads(row[0]["payload"]), False
    data = upstream.fetch_json(
        upstream.OM + "?latitude=%.4f&longitude=%.4f&minutely_15=precipitation"
        "&timezone=Asia/Shanghai&forecast_days=1" % (float(lat), float(lon)), timeout=12)
    m = data.get("minutely_15") or {}
    times = m.get("time") or []
    ppps = m.get("precipitation") or []
    now = _now()
    slots = []
    for i, t in enumerate(times):
        ts = _parse_dt(t)
        if ts < now - timedelta(minutes=15):
            continue
        off = int((ts - now).total_seconds() // 60)
        if off >= 120:
            break
        mm = ppps[i] if i < len(ppps) else 0
        lv, txt = mapping.precip_intensity_mm_per_min(mm)
        slots.append({"minuteOffset": max(off, 0), "time": ts.strftime("%H:%M"),
                      "precipMm15": mm, "level": lv, "text": txt})
    desc = _nowcast_desc(slots)
    payload = {"lat": float(lat), "lon": float(lon), "resolutionMin": 15,
               "windowMin": 120, "slots": slots, "summary": desc}
    db.x("""INSERT INTO geo_cache(k,payload,updated_at) VALUES(%s,%s,%s)
            ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=VALUES(updated_at)""",
         (k, json.dumps(payload, ensure_ascii=False), _now()))
    return payload, False


def _nowcast_desc(slots):
    first_rain = next((s for s in slots if (s["precipMm15"] or 0) > 0.05), None)
    if not slots:
        return "暂无分钟级降水数据"
    if first_rain is None:
        return "未来 2 小时无降水"
    cur_rain = (slots[0]["precipMm15"] or 0) > 0.05
    if cur_rain:
        stop = next((s for s in slots if (s["precipMm15"] or 0) <= 0.05), None)
        if stop and stop["minuteOffset"] < 120:
            return "当前有%s，预计约 %d 分钟后停" % (slots[0]["text"], stop["minuteOffset"])
        return "当前%s，未来 2 小时持续" % slots[0]["text"]
    return "约 %d 分钟后开始%s" % (first_rain["minuteOffset"], first_rain["text"])


# ---------------- alerts (NMC) ----------------

_LEVEL_RE = re.compile(r"(红|橙|黄|蓝)色预警")


def _sync_alarms():
    page, got, total = 1, 0, None
    c = db.conn()
    try:
        with c.cursor() as cur:
            while True:
                res = upstream.nmc_alarms(page_size=200)
                d = (res.get("data") or {}).get("page") or {}
                total = d.get("count") or 0
                lst = d.get("list") or []
                ids = []
                for it in lst:
                    title = it.get("title") or ""
                    lv = _LEVEL_RE.search(title)
                    atype = title.split("发布")[-1].replace("预警信号", "").strip()[:16]
                    prov = re.split(r"[省市]", title.split("气象台")[0])[0][:16] if "气象台" in title else ""
                    cur.execute("""INSERT INTO alert(alert_id,province,title,type_code,level,issued_at,url,updated_at)
                                   VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
                                   ON DUPLICATE KEY UPDATE title=VALUES(title),
                                   level=VALUES(level),issued_at=VALUES(issued_at),updated_at=VALUES(updated_at)""",
                                (it.get("alertid"), prov, title, atype,
                                 {"红": "red", "橙": "orange", "黄": "yellow", "蓝": "blue"}.get(
                                     lv.group(1) if lv else "", ""),
                                 it.get("issuetype") or it.get("issuetime"),
                                 it.get("url") or "", _now()))
                    ids.append(it.get("alertid"))
                got += len(lst)
                if got >= total or not lst:
                    break
                page += 1
                if page > 8:
                    break
            if ids:
                ph = ",".join(["%s"] * len(ids))
                cur.execute(f"DELETE FROM alert WHERE updated_at < %s AND alert_id NOT IN ({ph})",
                            [_now() - timedelta(hours=6), *ids])
    finally:
        c.close()
    _alert_sync_state["ts"] = time.time()


def get_alerts(city):
    if time.time() - _alert_sync_state["ts"] > TTL["alert"]:
        try:
            _sync_alarms()
        except Exception:
            pass
    rows = []
    if city.get("province"):
        rows = db.q("""SELECT alert_id,province,title,type_code,level,issued_at,url FROM alert
                       WHERE title LIKE %s ORDER BY issued_at DESC LIMIT 20""",
                    (f"%{city['province']}%",))
    return rows


# ---------------- radar (RainViewer) ----------------

def radar():
    row = db.q("SELECT payload,updated_at FROM geo_cache WHERE k=%s", ("radar",))
    if row and (_now() - row[0]["updated_at"]).total_seconds() < TTL["radar"]:
        return json.loads(row[0]["payload"]), False
    meta = upstream.rainviewer_meta()
    host = meta.get("host")
    frames = meta.get("radar") or {}
    past = frames.get("past") or []
    nowc = frames.get("nowcast") or []
    frames_out = []
    for f in past + nowc:
        frames_out.append({"time": datetime.utcfromtimestamp(f["time"]).strftime("%Y-%m-%d %H:%M"),
                           "epoch": f["time"], "forecast": f in nowc,
                           "tileUrl": f"{host}{f['path']}/256/{{z}}/{{x}}/{{y}}/4/1_1.png"})
    payload = {"frames": frames_out, "pastCount": len(past),
               "frameIntervalSec": frames.get("frame_interval", 600)}
    db.x("""INSERT INTO geo_cache(k,payload,updated_at) VALUES(%s,%s,%s)
            ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=VALUES(updated_at)""",
         ("radar", json.dumps(payload), _now()))
    return payload, False


# ---------------- life index ----------------

def life_indices(city):
    today = date.today()
    marker = db.q("SELECT MAX(updated_at) AS u FROM life_index WHERE city_id=%s AND d=%s",
                  (city["id"], today))[0]["u"]
    if marker and (_now() - marker).total_seconds() < TTL["index"]:
        rows = db.q("SELECT type,score,status,descr FROM life_index WHERE city_id=%s AND d=%s",
                    (city["id"], today))
        return rows, False
    r = get_realtime(city["id"])
    if not r:
        r, _ = weather_realtime(city)
    if not r:
        return [], True  # 实况冷启动失败：不阻塞其它卡片，下次重试
    hours, _ = get_hourly(city, 24)
    d0 = db.q("SELECT * FROM daily WHERE city_id=%s AND d=%s", (city["id"], today))
    items = calc_indices(r, hours, d0[0] if d0 else None)
    c = db.conn()
    try:
        with c.cursor() as cur:
            cur.executemany("""INSERT INTO life_index(city_id,d,type,score,status,descr,updated_at)
                               VALUES(%s,%s,%s,%s,%s,%s,%s)
                               ON DUPLICATE KEY UPDATE score=VALUES(score),status=VALUES(status),
                               descr=VALUES(descr),updated_at=VALUES(updated_at)""",
                            [(city["id"], today, t, sc, st, de, _now())
                             for t, sc, st, de in items])
    finally:
        c.close()
    return [dict(type=t, score=sc, status=st, descr=de) for t, sc, st, de in items], False
