#!/usr/bin/env python3
"""A second SyncPlay participant for testing Novafin's "Watch together" by hand.

  NOVAFIN_BASE=http://host:8096 NOVAFIN_TOKEN=... tools/syncplay_peer.py list
  ... new NAME | join GROUP_ID | queue ITEM_ID [START_SECONDS] | pause | unpause | seek SECONDS | leave
  ... ready [SECONDS]   tell a waiting group this peer has loaded (asks the server which entry over the socket)
  ... listen [SECONDS]  print what the server sends this peer (default 30 s)

It is its own session (DeviceId novafin-peer). Prints the HTTP status and body, never the token.
"""
import base64, datetime, json, os, socket, ssl, sys, time, urllib.error, urllib.parse, urllib.request

BASE = os.environ["NOVAFIN_BASE"].rstrip("/")
AUTH = 'MediaBrowser Client="peer", Device="peer", DeviceId="novafin-peer", Version="1", Token="%s"' % os.environ["NOVAFIN_TOKEN"]
TICKS = 10_000_000
EMPTY = "00000000-0000-0000-0000-000000000000"


def call(method, path, body=None):
    data = None if method == "GET" else json.dumps(body if body is not None else {}).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Authorization": AUTH, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    except urllib.error.URLError as e:
        sys.exit("can't reach the server: %s" % e.reason)
    print(method, path, "->", status, text)
    return status


def post(action, body=None):
    return call("POST", "/SyncPlay/" + action, body)


def ready(playlist_item_id, ticks):
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    return post("Ready", {"When": now, "PositionTicks": int(ticks), "IsPlaying": False, "PlaylistItemId": playlist_item_id})


class Socket:
    """Just enough WebSocket to read the server's text messages."""

    def __init__(self):
        u = urllib.parse.urlsplit(BASE)
        self.s = socket.create_connection((u.hostname, u.port or (443 if u.scheme == "https" else 80)), timeout=10)
        if u.scheme == "https":
            self.s = ssl.create_default_context().wrap_socket(self.s, server_hostname=u.hostname)
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall((
            "GET %s/socket HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nAuthorization: %s\r\n\r\n" % (u.path.rstrip("/"), u.netloc, key, AUTH)
        ).encode())
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            self._fill()
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n")[0].decode()
        if " 101 " not in status:
            sys.exit("socket refused: " + status)

    def _fill(self):
        chunk = self.s.recv(65536)
        if not chunk:
            raise ConnectionError("socket closed by the server")
        self.buf += chunk

    def _frame(self):
        """(first byte, payload) of the next frame. Nothing is consumed until it is whole, so a read timeout loses nothing."""
        while True:
            b = self.buf
            if len(b) >= 2:
                n, off = b[1] & 0x7F, 2
                if n == 126:
                    n, off = (int.from_bytes(b[2:4], "big"), 4) if len(b) >= 4 else (None, 0)
                elif n == 127:
                    n, off = (int.from_bytes(b[2:10], "big"), 10) if len(b) >= 10 else (None, 0)
                if n is not None and len(b) >= off + n:
                    self.buf = b[off + n:]
                    return b[0], b[off:off + n]
            self._fill()

    def _send(self, opcode, payload=b""):
        mask = os.urandom(4)
        self.s.sendall(bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def messages(self, seconds):
        """Yields parsed messages until `seconds` have passed; keeps the session alive meanwhile."""
        end, text = time.time() + seconds, b""
        self.s.settimeout(1)
        while time.time() < end:
            try:
                b0, payload = self._frame()
            except socket.timeout:
                continue
            except ConnectionError:
                return
            op = b0 & 0x0F
            if op == 8:
                return
            if op == 9:
                self._send(10, payload[:125])
            if op in (0, 1):
                text += payload
                if b0 & 0x80:
                    m, text = json.loads(text), b""
                    if m.get("MessageType") == "ForceKeepAlive":
                        self._send(1, b'{"MessageType":"KeepAlive"}')
                    yield m


def cmd_ready(seconds=None):
    ws = Socket()
    # An entry that can't match makes the server say what the group is on (a queue update while it waits, its last command otherwise).
    ready(EMPTY, 0)
    for m in ws.messages(4):
        d = m.get("Data")
        if m.get("MessageType") == "SyncPlayGroupUpdate" and d["Type"] == "PlayQueue":
            q = d["Data"]
            print("<-", "PlayQueue", json.dumps(q))
            if 0 <= q["PlayingItemIndex"] < len(q["Playlist"]):
                ready(q["Playlist"][q["PlayingItemIndex"]]["PlaylistItemId"], seconds * TICKS if seconds is not None else q["StartPositionTicks"])
        elif m.get("MessageType") == "SyncPlayCommand":
            print("<-", d["Command"], json.dumps(d))
            # The server asks for the exact spot before it counts this peer as ready.
            if d["Command"] == "Seek":
                ready(d["PlaylistItemId"], d.get("PositionTicks") or 0)
        elif m.get("MessageType") == "SyncPlayGroupUpdate":
            print("<-", d["Type"], json.dumps(d.get("Data")))


def cmd_listen(seconds=30):
    for m in Socket().messages(seconds):
        if m.get("MessageType") not in ("ForceKeepAlive", "KeepAlive"):
            print(time.strftime("%H:%M:%S"), m.get("MessageType"), json.dumps(m.get("Data")))


COMMANDS = {
    "list": lambda: call("GET", "/SyncPlay/List"),
    "new": lambda name: post("New", {"GroupName": name}),
    "join": lambda group_id: post("Join", {"GroupId": group_id}),
    "queue": lambda item_id, start="0": post("SetNewQueue", {"PlayingQueue": [item_id], "PlayingItemPosition": 0, "StartPositionTicks": int(float(start) * TICKS)}),
    "pause": lambda: post("Pause"),
    "unpause": lambda: post("Unpause"),
    "seek": lambda seconds: post("Seek", {"PositionTicks": int(float(seconds) * TICKS)}),
    "leave": lambda: post("Leave"),
    "ready": lambda seconds=None: cmd_ready(None if seconds is None else float(seconds)),
    "listen": lambda seconds="30": cmd_listen(float(seconds)),
}

if __name__ == "__main__":
    try:
        command = COMMANDS[sys.argv[1]]
    except (IndexError, KeyError):
        sys.exit(__doc__)
    command(*sys.argv[2:])
