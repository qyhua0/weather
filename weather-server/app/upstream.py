"""上游数据源访问：默认直连（绕过环境代理）；Open-Meteo 可选走 WX_UPSTREAM_PROXY
（本机出口 IP 被 Open-Meteo 限流时，通过 SSH 隧道到海外 VPS 的白名单代理）。
带超时与重试。

作者: zhenhua
版本: 1.2
"""
import json
import os
import urllib.request
import urllib.parse
import urllib.error
import gzip
import io

_UA = {"User-Agent": "wxapp/1.2 (weather app backend)", "Accept-Encoding": "gzip"}

_OPEN_METEO_HOSTS = ("api.open-meteo.com", "air-quality-api.open-meteo.com",
                     "geocoding-api.open-meteo.com")


def _openers(url):
    """返回按优先级尝试的 opener 列表。"""
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    proxy = os.environ.get("WX_UPSTREAM_PROXY", "").strip()
    host = urllib.parse.urlsplit(url).hostname or ""
    if proxy and host in _OPEN_METEO_HOSTS:
        via = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
        return [via, direct]
    return [direct]


def fetch_json(url: str, timeout: int = 12, retries: int = 2):
    openers = _openers(url)
    last = None
    for attempt in range(retries + len(openers)):
        op = openers[attempt // retries] if len(openers) > 1 else openers[0]
        try:
            req = urllib.request.Request(url, headers=_UA)
            with op.open(req, timeout=timeout) as r:
                data = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    data = gzip.GzipFile(fileobj=io.BytesIO(data)).read()
                obj = json.loads(data.decode("utf-8"))
            if _looks_throttled(obj):
                last = RuntimeError("throttled null payload")
                continue
            return obj
        except Exception as e:  # noqa
            last = e
    raise RuntimeError(f"upstream failed: {url} ({last})")


def _looks_throttled(obj):
    """Open-Meteo 被限流时返回 200 但数值全 null（仅 is_day 有值），识别为失败。"""
    if not isinstance(obj, dict):
        return False
    cur = obj.get("current")
    if isinstance(cur, dict) and cur and "temperature_2m" in cur:
        return cur.get("temperature_2m") is None
    return False


# ---------- Open-Meteo ----------
OM = "https://api.open-meteo.com/v1/forecast"
AQ = "https://air-quality-api.open-meteo.com/v1/air-quality"
GEO = "https://geocoding-api.open-meteo.com/v1/search"

HOURLY_VARS = ("temperature_2m,weather_code,precipitation_probability,precipitation,"
               "wind_direction_10m,wind_speed_10m,relative_humidity_2m,uv_index,visibility")
CURRENT_VARS = ("temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
                "precipitation,weather_code,pressure_msl,wind_speed_10m,"
                "wind_direction_10m,wind_gusts_10m")
DAILY_VARS = ("weather_code,temperature_2m_max,temperature_2m_min,"
              "precipitation_probability_max,wind_direction_10m_dominant,"
              "wind_speed_10m_max,sunrise,sunset,uv_index_max")


def om_forecast(lat, lon, tz="Asia/Shanghai", forecast_days=16, hours=True, minutely=False):
    params = {
        "latitude": round(float(lat), 4), "longitude": round(float(lon), 4),
        "current": CURRENT_VARS, "timezone": tz or "auto", "forecast_days": forecast_days,
        "daily": DAILY_VARS,
    }
    if hours:
        params["hourly"] = HOURLY_VARS
    if minutely:
        params["minutely_15"] = "precipitation,temperature_2m"
        params.setdefault("hourly", HOURLY_VARS)
    return fetch_json(OM + "?" + urllib.parse.urlencode(params))


def aqi_current_hourly(lat, lon, tz="Asia/Shanghai", days=5):
    params = {
        "latitude": round(float(lat), 4), "longitude": round(float(lon), 4),
        "current": "us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,carbon_monoxide,sulphur_dioxide",
        "hourly": "us_aqi", "timezone": tz or "auto", "forecast_days": days,
    }
    return fetch_json(AQ + "?" + urllib.parse.urlencode(params))


def geocode(q: str, count=10):
    params = {"name": q, "count": count, "language": "zh", "format": "json"}
    return fetch_json(GEO + "?" + urllib.parse.urlencode(params))


BGC = "https://api.bigdatacloud.net/data/reverse-geocode-client"


def reverse_geo(lat, lon):
    """免 key 逆地理：返回中文城市名（如「东莞市」），失败返回空串。"""
    params = {"latitude": round(float(lat), 4), "longitude": round(float(lon), 4),
              "localityLanguage": "zh"}
    try:
        r = fetch_json(BGC + "?" + urllib.parse.urlencode(params), timeout=8)
        return r.get("city") or r.get("locality") or ""
    except Exception:
        return ""


# ---------- NMC 预警 ----------
NMC_ALARM = "http://www.nmc.cn/rest/findAlarm"


def nmc_alarms(province: str = "", page_size=100):
    params = {"pageNo": 1, "pageSize": page_size, "signaltype": "",
              "signallevel": "", "province": province}
    return fetch_json(NMC_ALARM + "?" + urllib.parse.urlencode(params), timeout=10)


# ---------- RainViewer 雷达 ----------
RV_META = "https://api.rainviewer.com/public/weather-maps.json"


def rainviewer_meta():
    return fetch_json(RV_META, timeout=10)
