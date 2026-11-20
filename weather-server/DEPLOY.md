# 后端部署

FastAPI + MySQL，Python ≥ 3.8。

## 依赖

```
fastapi<0.116
uvicorn[standard]<0.34
pymysql
```

## 数据库

```bash
mysql -uroot -p < schema.sql        # 建库 wx_app / 表结构与 city 表
python3 seed_all_cities.py          # 导入全国 3272 个行政区划城市
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `WX_DB_HOST` | `127.0.0.1` | MySQL 地址 |
| `WX_DB_PORT` | `3306` | 端口 |
| `WX_DB_USER` | `wxapp` | 用户 |
| `WX_DB_PASS` | （必填） | 密码 |
| `WX_DB_NAME` | `wx_app` | 库名 |
| `WX_PORT` | `8097` | 服务监听端口 |

## 启动

```bash
python3 -m venv venv && ./venv/bin/pip install 'fastapi<0.116' 'uvicorn[standard]<0.34' pymysql
WX_DB_PASS=*** ./venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8097
```

生产建议 systemd：

```ini
[Unit]
Description=wxapp weather api
After=network.target mysql.service

[Service]
User=wxapp
WorkingDirectory=/opt/wxapp
EnvironmentFile=/opt/wxapp/env      # 存放 WX_DB_PASS 等，chmod 600
ExecStart=/opt/wxapp/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8097
Restart=always

[Install]
WantedBy=multi-user.target
```

## Nginx

```nginx
server {
    listen 443 ssl;
    server_name weather.example.com;
    root /var/www/weather;
    # ssl_certificate ...;

    location /api/ {
        proxy_pass http://127.0.0.1:8097;
        proxy_set_header Host $host;
        proxy_read_timeout 60s;
    }

    location /download/ {
        types { application/vnd.android.package-archive apk; }
        add_header Content-Disposition "attachment";
    }
}
```

## 备注

- 上游（Open-Meteo / NMC / RainViewer）请求绕过环境代理直连。
- 国内服务器如 DNS 解析到的 Open-Meteo 节点返回空数据（HTTP 200 但字段全 null），可在 `/etc/hosts` 钉可用 IP，并清空缓存表强制回源。

## 上游代理（可选）

服务器出口 IP 可能被 Open-Meteo 软限流（200 响应但数值全 null）。此时可设置
`WX_UPSTREAM_PROXY=http://127.0.0.1:<port>`，仅 `*.open-meteo.com` 三个域名经该
HTTP/CONNECT 代理转发（建议放在白名单代理 + SSH 隧道之后），失败自动降级直连重试。
