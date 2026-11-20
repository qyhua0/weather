"""WMO 天气码映射、风力等级、AQI 等级。

作者: zhenhua
版本: 1.2
"""

WMO = {
    0: "晴", 1: "晴间多云", 2: "多云", 3: "阴",
    45: "雾", 48: "雾凇", 51: "毛毛雨", 53: "毛毛雨", 55: "毛毛雨",
    56: "冻毛毛雨", 57: "冻毛毛雨",
    61: "小雨", 63: "中雨", 65: "大雨", 66: "冻雨", 67: "冻雨",
    71: "小雪", 73: "中雪", 75: "大雪", 77: "雪粒",
    80: "小阵雨", 81: "阵雨", 82: "强阵雨", 85: "阵雪", 86: "强阵雪",
    95: "雷阵雨", 96: "强雷阵雨", 99: "强雷阵雨伴冰雹",
}


def cond_text(code, is_day=1):
    t = WMO.get(int(code) if code is not None else -1, "未知")
    if code in (0, 1) and not is_day:
        t = "晴夜" if code == 0 else "多云夜"
    return t


def wind_scale(speed_kmh) -> str:
    """蒲福风级（km/h）。"""
    th = [1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118]
    s = float(speed_kmh or 0)
    for i, t in enumerate(th):
        if s < t:
            return f"{i}级"
    return "12级"


def wind_dir_text(deg) -> str:
    dirs = ["北", "东北偏北", "东北", "东北偏东", "东", "东南偏东", "东南", "东南偏南",
            "南", "西南偏南", "西南", "西南偏西", "西", "西北偏西", "西北", "西北偏北"]
    return dirs[int(round(float(deg or 0) / 22.5)) % 16] + "风"


def aqi_level(aqi) -> tuple:
    """返回 (等级, 健康建议)，US AQI 口径。"""
    a = int(aqi) if aqi is not None else 0
    if a <= 50:
        return "优", "空气质量令人满意，可正常户外活动"
    if a <= 100:
        return "良", "极少数敏感人群应减少户外活动"
    if a <= 150:
        return "轻度污染", "儿童、老人及呼吸道疾病人群减少长时间户外剧烈运动"
    if a <= 200:
        return "中度污染", "避免长时间户外活动，外出佩戴口罩"
    if a <= 300:
        return "重度污染", "停止户外活动，关闭门窗开启净化设备"
    return "严重污染", "留室内并做好防护，必要时就医"


def primary_pollutant(pm25, pm10, o3, no2):
    """按浓度粗判首要污染物（近似，US 口径以分项指数为准）。"""
    candidates = [("PM2.5", float(pm25 or 0)), ("PM10", float(pm10 or 0)),
                  ("臭氧", float(o3 or 0) * 0.4), ("二氧化氮", float(no2 or 0) * 0.5)]
    candidates.sort(key=lambda x: -x[1])
    top = candidates[0]
    return top[0] if top[1] > 0 else "-"


def precip_intensity_mm_per_min(mm15):
    """15 分钟降水量(mm) → mm/h 强度分级。"""
    rate = float(mm15 or 0) * 4
    if rate < 0.1:
        return 0, "无降水"
    if rate < 2.5:
        return 1, "小雨"
    if rate < 8:
        return 2, "中雨"
    if rate < 16:
        return 3, "大雨"
    return 4, "暴雨"
