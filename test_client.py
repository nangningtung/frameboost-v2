import socket
import json

def query(cmd):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(("127.0.0.1", 9999))
    s.sendall(cmd.encode('utf-8') + b'\n')
    data = b""
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        data += chunk
        if b'\n' in chunk:
            break
    s.close()
    return data.decode('utf-8').strip()

print("--- Testing 'ping' ---")
print(query("ping"))

print("\n--- Testing 'snapshot' ---")
snap = json.loads(query("snapshot"))
print(f"daemon_state: {snap.get('daemon_state')}, is_game: {snap.get('is_game')}, battery: {snap.get('battery')}")

print("\n--- Testing 'net_ping 8.8.8.8 3' ---")
p = json.loads(query("net_ping 8.8.8.8 3"))
print(json.dumps(p, indent=2))

print("\n--- Testing 'net_multi_ping 2' ---")
mp = json.loads(query("net_multi_ping 2"))
print(json.dumps(mp, indent=2))

print("\n--- Testing 'net_bufferbloat 1.1.1.1' ---")
bb = json.loads(query("net_bufferbloat 1.1.1.1"))
print(json.dumps(bb, indent=2))
