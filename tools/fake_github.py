# E2E 测试用假 GitHub Releases 服务器（本机 8765 端口，模拟器经 10.0.2.2 访问）
# /repos/.../releases/latest → versionCode=14 的发布 JSON（比模拟器里装的 13 新一版）
# /download/app.apk → 本地构建的 release APK 真实字节
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

APK = os.path.join(os.path.dirname(__file__), "app-release.apk")
META = "<!-- studyfriend-update versionCode=14 versionName=0.1.14 -->"

RELEASE = json.dumps({
    "tag_name": "latest",
    "name": "学伴 App 0.1.14",
    "body": META + "\n自动构建 · E2E 测试假发布\n\n更新内容：\n- 应用自更新（M7）端到端验证",
    "assets": [{
        "name": "StudyingWithFriend-latest.apk",
        "browser_download_url": "http://10.0.2.2:8765/download/app.apk",
        "size": os.path.getsize(APK),
    }],
}).encode()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.endswith("/releases/latest"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(RELEASE)))
            self.end_headers()
            self.wfile.write(RELEASE)
        elif self.path.endswith("/download/app.apk"):
            with open(APK, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, *a):
        pass


HTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
