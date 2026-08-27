#!/usr/bin/env bash
set -euo pipefail

END="${END_TIME:-2000000000000}"
URL="http://localhost:8888/api/1/overview?exclude_noise=false&end=$END"

echo "Fetching diff overview from Diffy..."
echo ""

JSON=$(curl -s "$URL")
python3 -c "
import json, sys
data = json.loads(sys.argv[1])
if not data:
    print('No diffs found. Check Diffy is running and end= time range includes now.')
    sys.exit(0)

print(f\"{'Endpoint':<35} {'Type':<6} {'Total':>5}  Regression fields\")
print('-' * 85)

for endpoint, info in sorted(data.items()):
    etype = 'PROTO' if 'proto' in endpoint else ('JSON' if 'json' in endpoint else 'OTHER')
    total = info.get('endpoint', {}).get('total', 0)
    fields = info.get('fields', {})
    regressions = sorted({
        k.replace('response.body.value.', '').rsplit('.', 1)[0]
        for k in fields if 'PrimitiveDifference' in k and 'response.body.value.' in k
    })
    print(f'{endpoint:<35} {etype:<6} {total:>5}  {\", \".join(regressions) or \"none\"}')

print()
print('Expected similar behavior for PROTO and JSON:')
print('  - age differs (candidate = request age + 1)')
print('  - email differs (candidate modifies email)')
print('  - name matches (NoDifference)')
" "$JSON"
