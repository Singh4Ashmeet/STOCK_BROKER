#!/usr/bin/env python3
import urllib.request, json, base64

# Read password from .env
password = None
with open('.env', 'r') as f:
    for line in f:
        if line.startswith('SB_GRAFANA_ADMIN_PASSWORD='):
            password = line.strip().split('=', 1)[1]
            break

auth = base64.b64encode(f'admin:{password}'.encode()).decode()
req = urllib.request.Request('http://127.0.0.1:3000/api/search?type=dash-db')
req.add_header('Authorization', f'Basic {auth}')

try:
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode())
        for d in data:
            print(d['title'])
except Exception as e:
    print(f"ERROR: {e}")