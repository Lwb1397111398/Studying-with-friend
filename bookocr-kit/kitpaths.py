#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
kitpaths — bookocr-kit 三个工作目录的唯一定义处。

  待识别/  老板把扫描版 PDF 丢进来（scan/auto 从这里取书）
  识别中/  每本书一个识别工程（原 jobs/；manifest.json 单文件状态机，断点续跑）
  已识别/  打包归档完成的书（每书一个文件夹：成品三件套 + 原书 PDF + 工程/）

目录名可在 kit 目录 config.json 用 dir_inbox / dir_jobs / dir_done 覆盖；
旧版工程目录 jobs/ 在首次运行任何命令时自动改名为 识别中/（工程内容不变）。
"""
from __future__ import annotations

import json
import time
from pathlib import Path

KIT = Path(__file__).resolve().parent

_DIR_DEFAULTS = {"dir_inbox": "待识别", "dir_jobs": "识别中", "dir_done": "已识别"}


def _dir_names() -> dict[str, str]:
    names = dict(_DIR_DEFAULTS)
    p = KIT / "config.json"
    if p.exists():
        try:
            user = json.loads(p.read_text(encoding="utf-8"))
            if isinstance(user, dict):
                for k in _DIR_DEFAULTS:
                    v = user.get(k)
                    if isinstance(v, str) and v.strip():
                        names[k] = v.strip()
        except (json.JSONDecodeError, OSError):
            pass  # 坏 config 用默认目录名，不阻塞
    return names


_NAMES = _dir_names()
INBOX = KIT / _NAMES["dir_inbox"]
JOBS = KIT / _NAMES["dir_jobs"]
DONE = KIT / _NAMES["dir_done"]


def migrate_legacy_jobs() -> str | None:
    """旧版工程目录 jobs/ → 识别中/ 一次性改名。返回提示文案；无需迁移返回 None。
    Windows 下刚写完的目录可能被索引/杀毒瞬时占用，rename 撞上就重试几轮；
    全失败则放弃本轮（返回 None），下次运行任何命令时再试，不阻塞。"""
    old = KIT / "jobs"
    if old.is_dir() and not JOBS.exists():
        for i in range(5):
            try:
                old.rename(JOBS)
                return f"检测到旧版工程目录 jobs/，已自动改名为「{_NAMES['dir_jobs']}」（工程内容原样保留）"
            except PermissionError:
                if i == 4:
                    return None
                time.sleep(0.5)
    return None


def ensure_dirs() -> None:
    for d in (INBOX, JOBS, DONE):
        d.mkdir(exist_ok=True)
