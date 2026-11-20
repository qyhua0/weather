"""全国城市目录种子：DataV 行政区划（省→地级→县级），免 key。
重复跑安全（ON DUPLICATE KEY）；库内已有同名城市跳过。

作者: zhenhua
版本: 1.2
"""
import json
import time
import urllib.request
from app import db

OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}_full.json"


def get(adcode):
    req = urllib.request.Request(BASE.format(adcode), headers={"User-Agent": "wxapp/1.0"})
    return json.loads(OP.open(req, timeout=15).read().decode("utf-8"))


names = {r["name"] for r in db.q("SELECT name FROM city")}
cnt = 0
fail = 0


def ins(adcode, name, center, admin1, admin2):
    global cnt, fail
    if not center or not name or name in names:
        return
    try:
        db.x("""INSERT INTO city(source_code,name,admin1,admin2,country,province,lat,lon,tz)
                VALUES(%s,%s,%s,%s,'中国',%s,%s,%s,'Asia/Shanghai')
                ON DUPLICATE KEY UPDATE name=VALUES(name)""",
             (f"ad:{adcode}", name.strip()[:64], (admin1 or "")[:64], (admin2 or "")[:64],
              (admin1 or "")[:32], float(center[1]), float(center[0])))
        names.add(name)
        cnt += 1
    except Exception as e:
        fail += 1
        print("fail", adcode, name, repr(e)[:80])


def main():
    cn = get(100000)
    provs = [(f["properties"]["adcode"], f["properties"]["name"]) for f in cn["features"]]
    for pac, pname in provs:
        if pac == 100000:
            continue
        try:
            pf = get(pac)
        except Exception as e:
            print("prov fail", pname, repr(e)[:80])
            continue
        cities = []
        for f in pf["features"]:
            p = f["properties"]
            ins(p["adcode"], p["name"], p.get("center") or p.get("centroid"), pname, None)
            cities.append(p)
        for p in cities:
            try:
                cf = get(p["adcode"])
            except Exception:
                continue
            for g in cf["features"]:
                q = g["properties"]
                ins(q["adcode"], q["name"], q.get("center") or q.get("centroid"),
                    pname, p["name"])
            time.sleep(0.03)
        print(pname, "done, total", cnt, flush=True)
        time.sleep(0.05)
    print("INSERTED", cnt, "FAIL", fail)


if __name__ == "__main__":
    main()
