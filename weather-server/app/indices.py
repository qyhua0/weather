"""生活指数：基于实况+逐时数据本地计算（P1）。

作者: zhenhua
版本: 1.2
"""
from datetime import date


def _clamp(v, lo=0, hi=100):
    return max(lo, min(hi, int(v)))


def calc_indices(rt, hours, d0):
    """rt: realtime dict; hours: 未来24h list; d0: 今日 daily dict。返回 list[dict]"""
    feels = float(rt.get("feats") or rt.get("feels") or rt.get("temp") or 10)
    temp = float(rt.get("temp") or 10)
    tmax = float((d0 or {}).get("tmax") or temp)
    tmin = float((d0 or {}).get("tmin") or temp)
    uv_max = float((d0 or {}).get("uv_max") or 0)
    pop24 = max([int(h.get("pop") or 0) for h in hours] or [0])
    precip24 = sum(float(h.get("precip_mm") or 0) for h in hours)
    wind = float(rt.get("wind_speed") or 0)
    hum = int(rt.get("humidity") or 50)

    out = []

    # 穿衣
    if feels < 0:
        out.append(("dressing", 30, "寒冷", "气温很低，建议羽绒服、棉帽手套等保暖装备"))
    elif feels < 10:
        out.append(("dressing", 50, "冷", "建议大衣、毛衣等厚外套，注意保暖"))
    elif feels < 18:
        out.append(("dressing", 75, "微凉", "建议夹克、针织衫等外套，早晚加衣"))
    elif feels < 24:
        out.append(("dressing", 90, "舒适", "长袖衬衫、薄外套即可"))
    elif feels < 28:
        out.append(("dressing", 90, "温暖", "短袖为主，注意防晒补水"))
    else:
        out.append(("dressing", 85, "炎热", "清凉透气短袖，注意防暑降温"))

    # 紫外线
    if uv_max < 3:
        out.append(("uv", 90, "弱", "紫外线较弱，无需特别防护"))
    elif uv_max < 6:
        out.append(("uv", 65, "中等", "外出建议涂抹 SPF15+ 防晒霜"))
    elif uv_max < 8:
        out.append(("uv", 40, "强", "建议 SPF30+ 防晒霜、遮阳帽太阳镜"))
    else:
        out.append(("uv", 20, "很强", "尽量避免 10-16 点长时间暴晒，做好硬防晒"))

    # 运动
    if pop24 >= 60 or precip24 > 5:
        out.append(("sport", 25, "不宜", "有降水风险，建议改为室内运动"))
    elif tmax > 33 or tmin < -5 or wind > 40:
        out.append(("sport", 45, "较差", "气温或风力不利，户外活动注意休息"))
    else:
        score = _clamp(100 - pop24 - max(0, tmax - 30) * 4 - max(0, -5 - tmin) * 3)
        out.append(("sport", score, "适宜", "天气条件适合跑步、骑行等户外运动"))

    # 洗车
    if precip24 > 0.5 or pop24 >= 50:
        out.append(("carwash", 25, "不宜", "未来 24 小时有降水，洗车易脏"))
    elif wind > 35:
        out.append(("carwash", 50, "较不宜", "风力较大，易沾染灰尘"))
    else:
        out.append(("carwash", 90, "适宜", "未来 24 小时无明显降水，适宜洗车"))

    # 感冒
    diff = tmax - tmin
    if (diff > 10 and tmin < 8) or tmin < -10 or (hum < 30 and wind > 25):
        out.append(("cold", 30, "易发", "温差大/干冷，注意及时添衣，多饮水"))
    elif diff > 8 or tmin < 3:
        out.append(("cold", 55, "较易发", "早晚偏凉，敏感人群注意保暖"))
    else:
        out.append(("cold", 85, "不易发", "气温平稳，感冒风险低"))

    # 舒适度
    score = 100 - abs(temp - 22) * 2.5 - max(0, hum - 70) * 0.5 - wind * 0.4
    if score >= 80:
        out.append(("comfort", _clamp(score), "舒适", "体感舒适，适合开窗通风与户外活动"))
    elif score >= 55:
        out.append(("comfort", _clamp(score), "较舒适", "体感基本舒适"))
    else:
        out.append(("comfort", _clamp(score), "不舒适", "体感欠佳，建议调整衣着或减少外出"))

    return out
