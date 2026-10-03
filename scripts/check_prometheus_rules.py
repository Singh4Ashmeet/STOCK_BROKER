#!/usr/bin/env python3
import urllib.request, json
with urllib.request.urlopen("http://127.0.0.1:9090/api/v1/rules") as r:
    data = json.load(r)
    print(json.dumps(data, indent=2))