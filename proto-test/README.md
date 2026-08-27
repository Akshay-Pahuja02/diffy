# Local Proto Testing

Small end-to-end setup to verify proto response decoding in Diffy.

## Prerequisites

- Java 21
- `protoc` installed (`brew install protobuf`)
- MongoDB running locally:

```bash
docker run -d -p 27017:27017 --name diffy-mongo mongo:7.0
```

- Diffy jar built:

```bash
cd /Users/akshay.pahuja/promise/diffy
mvn clean package -DskipTests -DskipFrontend=true
```

## What gets tested

Three mock servers return proto `UserResponse`:

| Backend   | Port | name  | age | email            |
|-----------|------|-------|-----|------------------|
| Primary   | 9100 | Alice | 30  | alice@example.com |
| Secondary | 9200 | Alice | 30  | alice@example.com |
| Candidate | 9000 | Alice | 31  | alice@new.com     |

Diffy should decode proto → JSON and detect diffs on `age` and `email`.

## Step 1: Generate `.desc`

```bash
bash proto-test/generate-desc.sh
```

Creates `schemas/service.desc`.

## Step 2: Start mock proto backends (terminal 1)

```bash
bash proto-test/run-mocks.sh
```

## Step 3: Start Diffy (terminal 2)

```bash
bash proto-test/run-diffy.sh
```

## Step 4: Send traffic (terminal 3)

```bash
bash proto-test/send-traffic.sh
```

## Step 5: Verify

```bash
curl http://localhost:8888/api/1/info
curl http://localhost:8888/api/1/endpoints
```

Expected: endpoint shows diffs on response body fields (`age`, `email`).

## Unit test only (no servers)

```bash
bash proto-test/generate-desc.sh
mvn test -Dtest=ProtoLifterTest -DskipFrontend=true
```

## Files

- `proto-test/user.proto` — sample schema
- `schemas/service.desc` — generated descriptor (after step 1)
- `proto-test/ProtoMockServer.java` — three HTTP servers returning proto bytes
- `ProtoLifter.java` — proto → JSON decoder used by Diffy
