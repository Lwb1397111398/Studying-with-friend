# -*- coding: utf-8 -*-
"""探针本地 server：GET 静态文件 + POST /report 落盘 results/。

用法：python server.py [port]（默认 8123，Ctrl-C 退出）
模拟器访问 http://10.0.2.2:8123/（10.0.2.2 = 宿主机回环）。
"""
import json
import os
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8123


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=HERE, **kwargs)

    def do_POST(self):
        if self.path != "/report":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            payload = json.loads(body)
        except Exception as e:
            self.send_error(400, str(e))
            return
        os.makedirs(RESULTS, exist_ok=True)
        name = "report_%s_%s_n%s_f%s.json" % (
            time.strftime("%H%M%S"),
            payload.get("lib", "?"),
            payload.get("nodes", "?"),
            payload.get("fold", "?"),
        )
        path = os.path.join(RESULTS, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=1)
        print("[report] %s (phase=%s)" % (name, payload.get("phase", "?")))
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        # GET 日志落到文件（取证用：区分「请求没到 server」vs「到了但响应失败」）
        try:
            with open(os.path.join(HERE, "server_access.log"), "a", encoding="utf-8") as f:
                f.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))
        except Exception:
            pass


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("serving %s at http://0.0.0.0:%d (模拟器走 http://10.0.2.2:%d)" % (HERE, PORT, PORT))
    srv.serve_forever()
