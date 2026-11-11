#!/usr/bin/env python3
"""Download the raw station data the app ships with: Open Charge Map (needs OCM_API_KEY in the
environment) and OpenStreetMap via Overpass, for the whole of Indonesia (trips leave Jakarta).

    python3 tools/snapshot.py app/src/main/assets

Each source is written only when its download succeeds and parses, so a flaky download in CI keeps
the previous snapshot instead of shipping an empty one. The app parses these raw files itself
(Ocm.kt / Osm.kt), the same code it uses when refreshing on the phone.
"""
import json
import os
import socket
import sys
import urllib.parse
import urllib.request

# GitHub runners have no IPv6 route, and Overpass answers on both: stick to IPv4.
_getaddrinfo = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, *a, **k: _getaddrinfo(host, port, socket.AF_INET, *a, **k)

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets"
UA = "icharge-snapshot/1.0 (personal EV charger app; github.com/eberhard0/icharge)"
# Jakarta centre; the bounding box is Jabodetabek plus a margin.
LAT, LON, KM = -6.25, 106.85, 60
BBOX = "-6.80,106.30,-5.75,107.45"

OCM_URL = ("https://api.openchargemap.io/v3/poi/?output=json&countrycode=ID"
           f"&latitude={LAT}&longitude={LON}&distance={KM}&distanceunit=km"
           "&maxresults=5000&compact=false&verbose=false&client=icharge")
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
QUERY = f"""[out:json][timeout:120];
(
  node["amenity"="charging_station"]({BBOX});
  way["amenity"="charging_station"]({BBOX});
);
out center tags;"""


def fetch(url, headers=None, data=None, timeout=180):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def save(name, text):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {path} ({len(text):,} bytes)")


def trim(pois):
    """Only the fields Ocm.kt reads, in the same shape: a third of the size."""
    out = []
    for p in pois:
        a = p.get("AddressInfo") or {}
        q = {
            "ID": p.get("ID"),
            "AddressInfo": {k: a.get(k) for k in ("Title", "AddressLine1", "AddressLine2", "Town", "StateOrProvince", "Latitude", "Longitude", "AccessComments")},
            "UsageCost": p.get("UsageCost"),
            "DateLastVerified": p.get("DateLastVerified"),
            "DateLastStatusUpdate": p.get("DateLastStatusUpdate"),
            "GeneralComments": p.get("GeneralComments"),
        }
        for k in ("OperatorInfo", "UsageType", "StatusType"):
            if p.get(k):
                q[k] = {"Title": p[k].get("Title")}
        q["Connections"] = [
            {
                "ConnectionType": {"ID": (c.get("ConnectionType") or {}).get("ID"), "Title": (c.get("ConnectionType") or {}).get("Title")},
                "ConnectionTypeID": c.get("ConnectionTypeID"),
                "PowerKW": c.get("PowerKW"),
                "CurrentType": {"ID": (c.get("CurrentType") or {}).get("ID")},
                "Quantity": c.get("Quantity"),
                "Level": {"IsFastChargeCapable": (c.get("Level") or {}).get("IsFastChargeCapable")},
            }
            for c in p.get("Connections") or []
        ]
        out.append(q)
    return out


def main():
    key = os.environ.get("OCM_API_KEY", "").strip()
    if key:
        try:
            text = fetch(OCM_URL, {"X-API-Key": key})
            pois = json.loads(text)
            if not isinstance(pois, list):
                raise ValueError(f"unexpected OCM reply: {text[:200]}")
            print(f"Open Charge Map: {len(pois)} locations")
            save("ocm.json", json.dumps(trim(pois), ensure_ascii=False, separators=(",", ":")))
        except Exception as e:  # keep the committed snapshot
            print(f"Open Charge Map download failed, keeping the old file: {e}")
    else:
        print("OCM_API_KEY not set: keeping the committed ocm.json")

    for endpoint in OVERPASS:
        try:
            text = fetch(endpoint, data=urllib.parse.urlencode({"data": QUERY}).encode())
            elements = json.loads(text).get("elements")
            if elements is None:
                raise ValueError(f"unexpected Overpass reply: {text[:200]}")
            print(f"OpenStreetMap ({endpoint}): {len(elements)} charging stations")
            save("osm.json", text)
            break
        except Exception as e:
            print(f"Overpass {endpoint} failed: {e}")
    else:
        print("All Overpass mirrors failed, keeping the old file")


if __name__ == "__main__":
    main()
