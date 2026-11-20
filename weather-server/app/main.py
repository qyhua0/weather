"""FastAPI 入口：微天气 BFF API 路由（实况/逐时/逐日/AQI/分钟降水/预警/雷达/生活指数/城市）。

作者: zhenhua
版本: 1.2
"""
from fastapi import FastAPI, Query, Body

from . import db, service

app = FastAPI(title="wxapp 天气服务", version="1.0")


@app.on_event("startup")
def _startup():
    db.ensure_schema()


def ok(data, updated_at=None, stale=False):
    return {"code": 0, "msg": "ok", "data": data,
            "updatedAt": str(updated_at) if updated_at else None, "stale": bool(stale)}


def err(msg, code=1):
    return {"code": code, "msg": msg, "data": None, "updatedAt": None, "stale": False}


def _city_or_err(city_id):
    return service.get_city(city_id)


@app.get("/api/v1/health")
def health():
    db.q("SELECT 1")
    return ok({"service": "wxapp", "version": "1.0"})


# ---------- 城市 ----------

@app.get("/api/v1/geo/search")
def geo_search(q: str = Query(..., min_length=1), limit: int = 10):
    rows = service.search_city(q, limit)
    return ok([{ "id": r["id"], "name": r["name"], "nameEn": r["name_en"],
                 "admin1": r["admin1"], "country": r["country"],
                 "lat": float(r["lat"]), "lon": float(r["lon"])} for r in rows])


@app.get("/api/v1/city/nearby")
def city_nearby(lat: float, lon: float):
    r = service.nearby_city(lat, lon)
    if not r:
        return err("no city catalog, seed first")
    return ok({"id": r["id"], "name": r["name"], "admin1": r["admin1"],
               "lat": float(r["lat"]), "lon": float(r["lon"])})


@app.get("/api/v1/user/cities")
def user_cities_get(deviceId: str):
    rows = db.q("""SELECT uc.sort_order, c.* FROM user_city uc JOIN city c ON c.id=uc.city_id
                   WHERE uc.device_id=%s ORDER BY uc.sort_order, uc.added_at""", (deviceId,))
    out = []
    for r in rows:
        rt = service.get_realtime(r["id"])
        out.append({"id": r["id"], "name": r["name"], "admin1": r["admin1"],
                    "lat": float(r["lat"]), "lon": float(r["lon"]),
                    "temp": rt["temp"] if rt else None,
                    "condText": rt["cond_text"] if rt else None})
    return ok(out)


@app.post("/api/v1/user/cities")
def user_cities_add(deviceId: str = Body(...), cityId: int = Body(...)):
    if not _city_or_err(cityId):
        return err("city not found")
    db.x("""INSERT IGNORE user_city(device_id,city_id) VALUES(%s,%s)""", (deviceId, cityId))
    return ok({"added": True})


@app.delete("/api/v1/user/cities")
def user_cities_del(deviceId: str, cityId: int):
    db.x("DELETE FROM user_city WHERE device_id=%s AND city_id=%s", (deviceId, cityId))
    return ok({"deleted": True})


# ---------- 天气 P0 ----------

@app.get("/api/v1/weather/realtime")
def api_realtime(cityId: int):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    r, stale = service.weather_realtime(c)
    if not r:
        return err("realtime unavailable")
    data = {"cityId": cityId, "condCode": r["cond_code"], "condText": r["cond_text"],
            "temp": float(r["temp"]) if r["temp"] is not None else None,
            "feelsLike": float(r["feels"]) if r["feels"] is not None else None,
            "humidity": r["humidity"], "pressure": r["pressure"],
            "windDir": r["wind_dir_text"], "windScale": r["wind_scale"],
            "windSpeed": float(r["wind_speed"]) if r["wind_speed"] is not None else None,
            "precip": float(r["precip"]) if r["precip"] is not None else None,
            "isDay": r["is_day"], "sunrise": r["sunrise"], "sunset": r["sunset"],
            "obsTime": str(r["obs_time"])}
    return ok(data, r["updated_at"], stale)


@app.get("/api/v1/weather/hourly")
def api_hourly(cityId: int, hours: int = Query(24, ge=1, le=48)):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    rows, stale = service.get_hourly(c, hours)
    data = [{"time": str(r["ts"]), "condText": r["cond_text"],
             "temp": float(r["temp"]) if r["temp"] is not None else None,
             "pop": r["pop"], "precipMm": float(r["precip_mm"]) if r["precip_mm"] is not None else 0,
             "windDir": r["wind_dir_text"], "windScale": r["wind_scale"],
             "humidity": r["humidity"], "uv": float(r["uv"]) if r["uv"] is not None else None}
            for r in rows]
    return ok(data, None, stale)


@app.get("/api/v1/weather/daily")
def api_daily(cityId: int, days: int = Query(15, ge=1, le=15)):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    rows, stale = service.get_daily(c, days)
    data = [{"date": str(r["d"]), "condText": r["cond_text"],
             "tmax": float(r["tmax"]) if r["tmax"] is not None else None,
             "tmin": float(r["tmin"]) if r["tmin"] is not None else None,
             "pop": r["pop"], "windDir": r["wind_dir_text"],
             "windSpeed": float(r["wind_speed_day"]) if r["wind_speed_day"] is not None else None,
             "sunrise": r["sunrise"], "sunset": r["sunset"],
             "uvMax": float(r["uv_max"]) if r["uv_max"] is not None else None}
            for r in rows]
    return ok(data, None, stale)


# ---------- 空气质量 P0 ----------

@app.get("/api/v1/air/realtime")
def api_aqi(cityId: int):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    r, stale = service.air_realtime(c)
    if not r:
        return err("aqi unavailable")
    return ok({"cityId": cityId, "aqi": r["aqi"], "level": r["level"],
               "primaryPollutant": r["primary_pollutant"],
               "pm25": float(r["pm25"]) if r["pm25"] is not None else None,
               "pm10": float(r["pm10"]) if r["pm10"] is not None else None,
               "o3": float(r["o3"]) if r["o3"] is not None else None,
               "no2": float(r["no2"]) if r["no2"] is not None else None,
               "so2": float(r["so2"]) if r["so2"] is not None else None,
               "co": float(r["co"]) if r["co"] is not None else None,
               "healthAdvice": r["health_advice"]}, r["updated_at"], stale)


@app.get("/api/v1/air/forecast")
def api_aqi_forecast(cityId: int, days: int = Query(5, ge=1, le=5)):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    rows, stale = service.air_forecast(c, days)
    return ok(rows, None, stale)


# ---------- P1 ----------

@app.get("/api/v1/weather/nowcast")
def api_nowcast(lat: float, lon: float):
    payload, stale = service.nowcast(lat, lon)
    return ok(payload, None, stale)


@app.get("/api/v1/alerts")
def api_alerts(cityId: int):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    rows = service.get_alerts(c)
    return ok([{"alertId": r["alert_id"], "title": r["title"], "type": r["type_code"],
                "level": r["level"], "issuedAt": r["issued_at"],
                "url": ("http://www.nmc.cn" + r["url"]) if r["url"] else None} for r in rows])


@app.get("/api/v1/radar")
def api_radar():
    payload, stale = service.radar()
    return ok(payload, None, stale)


@app.get("/api/v1/index/life")
def api_life_index(cityId: int):
    c = _city_or_err(cityId)
    if not c:
        return err("city not found")
    rows, stale = service.life_indices(c)
    return ok([{"type": r["type"], "score": r["score"], "status": r["status"],
                "desc": r["descr"]} for r in rows], None, stale)
