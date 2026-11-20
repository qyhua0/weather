# -*- coding: utf-8 -*-
"""
数据库访问层：MySQL 连接（环境变量注入凭据）与查询辅助。

作者: zhenhua
版本: 1.2
"""
import os
import pymysql
from pymysql.cursors import DictCursor

DB = dict(
    host=os.environ.get("WX_DB_HOST", "127.0.0.1"),
    port=int(os.environ.get("WX_DB_PORT", "3306")),
    user=os.environ.get("WX_DB_USER", "wxapp"),
    password=os.environ.get("WX_DB_PASS", ""),
    database=os.environ.get("WX_DB_NAME", "wx_app"),
    charset="utf8mb4",
    cursorclass=DictCursor,
    autocommit=True,
)


def conn():
    return pymysql.connect(**DB)


def q(sql, args=None):
    c = conn()
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        c.close()


def x(sql, args=None):
    c = conn()
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.rowcount
    finally:
        c.close()


def ensure_schema():
    import pathlib
    sql = pathlib.Path(__file__).resolve().parent.parent.joinpath("schema.sql").read_text()
    c = conn()
    try:
        with c.cursor() as cur:
            for stmt in [s.strip() for s in sql.split(";") if s.strip()]:
                cur.execute(stmt)
    finally:
        c.close()
