#!/usr/bin/env python3
"""Echo HTTP server for ZCSLIB network smoke tests."""
import http.server
import json
import sys
import os

HOST = "127.0.0.1"
PORT = 19998

class EchoHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self._reply({"echo": "GET " + self.path, "method": "GET"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else ""
        resp = {"echo": "POST " + self.path, "method": "POST", "body": body}
        # If body looks like JSON, include parsed version
        try:
            resp["parsed"] = json.loads(body)
        except (json.JSONDecodeError, TypeError):
            pass
        self._reply(resp)

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else ""
        self._reply({"echo": "PUT " + self.path, "body": body})

    def _reply(self, data):
        payload = json.dumps(data, indent=2, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print(f"[server] {args[0]}", flush=True)

if __name__ == "__main__":
    print(f"ZCSLIB smoke echo server on {HOST}:{PORT}", flush=True)
    httpd = http.server.HTTPServer((HOST, PORT), EchoHandler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[server] stopped", flush=True)
