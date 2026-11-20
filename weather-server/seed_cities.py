"""种子城市：通过 Open-Meteo geocoding 拉取国内主要城市入库。

作者: zhenhua
版本: 1.2
"""
import sys
import time
sys.path.insert(0, ".")
from app import db, upstream

CITIES = ["北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆",
          "天津", "苏州", "郑州", "长沙", "青岛", "沈阳", "宁波", "昆明", "合肥", "福州",
          "济南", "大连", "哈尔滨", "乌鲁木齐", "拉萨", "西宁", "兰州", "银川", "呼和浩特",
          "太原", "石家庄", "南昌", "南宁", "海口", "贵阳", "台北", "香港", "澳门"]


def main():
    db.ensure_schema()
    ok = 0
    for name in CITIES:
        try:
            res = upstream.geocode(name, count=1)
            g = (res.get("results") or [None])[0]
            if not g:
                print("miss", name)
                continue
            db.x("""INSERT INTO city(source_code,name,name_en,admin1,admin2,country,province,lat,lon,tz)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    ON DUPLICATE KEY UPDATE name=VALUES(name),lat=VALUES(lat),lon=VALUES(lon),
                    province=VALUES(province)""",
                 (f"om:{g['id']}", g.get("name"), g.get("name_en"), g.get("admin1"),
                  g.get("admin2"), g.get("country"), (g.get("admin1") or "")[:32],
                  g.get("latitude"), g.get("longitude"), g.get("timezone") or "Asia/Shanghai"))
            ok += 1
            time.sleep(0.15)
        except Exception as e:
            print("fail", name, e)
    n = db.q("SELECT COUNT(*) AS n FROM city")[0]["n"]
    print(f"seeded {ok}/{len(CITIES)}, city rows={n}")


if __name__ == "__main__":
    main()
