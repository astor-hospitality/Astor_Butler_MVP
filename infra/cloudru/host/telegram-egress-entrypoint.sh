#!/bin/sh
set -e
apk add --no-cache tinyproxy iproute2 >/dev/null
GW=$(ip route | awk '/default/{print $3; exit}')
EP=$(sed -n 's/^\s*Endpoint\s*=\s*//p' /etc/amnezia/amneziawg/awg0.conf | cut -d: -f1)
ip route replace "$EP/32" via "$GW"
awg-quick up awg0
ip route replace default dev awg0
printf 'Port 8888\nListen 0.0.0.0\nTimeout 120\nMaxClients 50\nAllow 172.16.0.0/12\nAllow 10.0.0.0/8\nConnectPort 443\nConnectPort 80\nLogLevel Warning\n' > /etc/tinyproxy/tinyproxy.conf
echo "tunnel up: endpoint $EP via $GW; starting tinyproxy"
exec tinyproxy -d -c /etc/tinyproxy/tinyproxy.conf
