# Diffy Custom — Proto Setup Guide

**Server:** `10.65.47.2`  
**Install path:** `/home/promise_dev/diffy-custom`  
**Standard process (VM, GoR):** https://flipkart.atlassian.net/wiki/x/J4BJD

---

## What Diffy Does

Diffy proxies shadow traffic to three backends and compares responses:

| Role | What it is |
|---|---|
| Candidate | New code under test |
| Primary | Known-good baseline |
| Secondary | Same as primary (noise detection) |
| Proxy `18882` | Send shadow traffic here |
| UI `18889` | Dashboard + API |

---

## 1. SSH to Diffy Box

```bash
ssh <your-user>@10.65.47.2
```

---

## 2. Configure Proto Mappings

```bash
cd ~/diffy-custom/schemas
sudo vi proto-config.json
```

Example (single API):

```json
{
  "mappings": [
    {
      "uri": "/postal_codes/v4/",
      "jarLocation": "https://jfrog.fkinternal.com/artifactory/maven_virtual/com/flipkart/omniscient/proto-models/4.4.639/proto-models-4.4.639.jar",
      "requestType": "com.flipkart.omniscient.proto.OfferingRequestV4Proto",
      "responseType": "com.flipkart.omniscient.proto.OfferingResponseProto"
    }
  ]
}
```

| Field | What to put |
|---|---|
| `uri` | API path (must match incoming request) |
| `jarLocation` | JFrog URL or local JAR path |
| `requestType` | Full proto request class name |
| `responseType` | Full proto response class name |

Use JDK 17 JARs with JDK 17. Use JDK 21 JARs with JDK 21.

---

## 3. Start Diffy

### Omniscient (JDK 21)

```bash
~/jdk/jdk-21.0.7-oracle-x64/bin/java -jar ~/diffy-custom/diffy-proto.jar \
  --candidate=10.69.122.95:27750 \
  --master.primary=10.69.166.215:27750 \
  --master.secondary=10.69.166.215:27750 \
  --allowHttpSideEffects=true \
  --responseMode=primary \
  --service.protocol=http \
  --serviceName=Omniscient \
  --proxy.port=18882 \
  --http.port=18889 \
  --proto.config=/home/promise_dev/diffy-custom/schemas/proto-config.json \
  --diffy.retention.days=7 \
  --diffy.retention.include-config=false \
  --logging.file.name=/home/promise_dev/diffy-custom/logs/diffy2.log \
  > ~/diffy-custom/logs/diffy2-stdout.log 2>&1 &
```

### Locus Transact (JDK 17)

```bash
~/jdk/jdk-17.0.13+11-jre/bin/java -jar ~/diffy-custom/diffy-proto.jar \
  --candidate=10.65.64.146:30303 \
  --master.primary=10.64.213.215:30303 \
  --master.secondary=10.69.116.124:30303 \
  --allowHttpSideEffects=true \
  --responseMode=primary \
  --service.protocol=http \
  --serviceName=LocusTransact2 \
  --proxy.port=18882 \
  --http.port=18889 \
  --proto.config=/home/promise_dev/diffy-custom/schemas/proto-config.json \
  --diffy.retention.days=7 \
  --diffy.retention.include-config=false \
  --logging.file.name=/home/promise_dev/diffy-custom/logs/diffy2.log \
  > ~/diffy-custom/logs/diffy2-stdout.log 2>&1 &
```

---

## 4. Verify Startup

```bash
tail -50 ~/diffy-custom/logs/diffy2-stdout.log
```

Look for:

```
Loaded proto config from ...
Mongo TTL active: differenceResult expires after 7 day(s)
Starting Proxy server on port 18882
```

```bash
curl http://localhost:18889/api/1/info
curl http://localhost:18889/actuator/health
```

---

## 5. Open UI (port forward from laptop)

```bash
ssh -L 18889:localhost:18889 <your-user>@10.65.47.2
```

Open: http://localhost:18889

---

## 6. Check Diffs

Traffic must hit proxy port **18882** (via GoR). Then:

```bash
curl http://localhost:18889/api/1/endpoints
curl "http://localhost:18889/api/1/overview?exclude_noise=false&start=0&end=9999999999999"
```

---

## 7. Mongo Auto-Cleanup (TTL)

Old diff data is deleted automatically. No cron jobs needed.

| Setting | Meaning |
|---|---|
| `--diffy.retention.days=7` | Delete diff records after 7 days |
| `--diffy.retention.include-config=false` | Keep noise rules & transforms (recommended) |
| `--diffy.retention.include-config=true` | Also delete noise rules & transforms |

| Collection | What it stores | Auto-deleted? |
|---|---|---|
| `differenceResult` | Request + response diffs | Yes (always) |
| `noise` | Noise cancellation rules | Only if `include-config=true` |
| `transformation` | JS transform scripts | Only if `include-config=true` |

### Verify TTL from logs

```bash
grep "Mongo TTL" ~/diffy-custom/logs/diffy2-stdout.log
```

### Verify TTL in Mongo

Diffy uses **embedded Mongo** by default. Database is **`test`**, port is **random** (find it in logs).

```bash
# Find Mongo port
MONGO_PORT=$(grep -o '"port":[0-9]*' ~/diffy-custom/logs/diffy2-stdout.log | head -1 | grep -o '[0-9]*')
echo "Mongo port: $MONGO_PORT"

# Check TTL index
~/jdk/mongosh-2.3.8-linux-x64/bin/mongosh mongodb://127.0.0.1:$MONGO_PORT/test --quiet --eval '
  const ttl = db.differenceResult.getIndexes().find(i => i.expireAfterSeconds != null);
  print(ttl ? "TTL OK: " + ttl.expireAfterSeconds + "s (" + (ttl.expireAfterSeconds/86400) + " days)" : "TTL MISSING");
  print("Docs:", db.differenceResult.countDocuments());
'
```

Expected: `TTL OK: 604800s (7 days)` for 7-day retention.

> `Docs: 0` is fine if no diffs yet. Index is created at startup; docs appear after traffic with diffs.

### Clear everything manually

```bash
curl http://localhost:18889/api/1/clear
```

---

## 8. Stop / Restart

```bash
# Stop
pkill -f diffy-proto.jar

# Check ports are free
lsof -i :18882 -i :18889

# Then start again (step 3)
```

---

## Quick Troubleshooting

| Problem | Fix |
|---|---|
| No diffs in UI | Traffic must go to port **18882**, not 18889 |
| Proto shows raw bytes | Check `uri` in proto-config.json matches request path |
| `mongosh: command not found` | Use `~/jdk/mongosh-2.3.8-linux-x64/bin/mongosh` |
| `ECONNREFUSED :27017` | Embedded Mongo — use port from logs, DB `test` |
| Port in use | `pkill -f diffy-proto.jar` |

---

## Port Reference

| Port | Service |
|---|---|
| 18882 | Diffy proxy |
| 18889 | Diffy UI |
| 43xxx | Embedded Mongo (check logs) |
| 27750 | Omniscient backend |
| 30303 | Locus backend |

---

*Last updated: July 2026*
