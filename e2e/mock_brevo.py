"""Stand-in for Brevo's email API during the e2e test: records each email as a JSON line."""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, OUT = int(sys.argv[1]), sys.argv[2]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.headers.get("api-key") != "test":
            self.send_response(401)
            self.end_headers()
            return
        with open(OUT, "a") as f:
            f.write(json.dumps({"to": [t["email"] for t in body.get("to", [])], "subject": body.get("subject"),
                                "text": body.get("textContent")}, ensure_ascii=False) + "\n")
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"messageId":"e2e"}')

    def log_message(self, *args):
        pass


HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
