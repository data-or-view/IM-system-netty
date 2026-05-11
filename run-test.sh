#!/bin/bash
# 启动 IMServer → 启动两个客户端 → 自动测试收发消息
set -e

BASE=/home/admin/openclaw/workspace/im-system

cd "$BASE"
mvn compile -q 2>&1

# 构建 classpath
MODULES="im-api im-codec im-core im-client im-bootstrap"
CP=""
for m in $MODULES; do
    CP="$CP:$BASE/$m/target/classes"
done

MAVEN_REPO="${HOME}/.m2/repository"
# Netty 核心
for jar in netty-buffer netty-common netty-codec netty-codec-base netty-codec-http netty-handler netty-resolver netty-transport netty-transport-classes-epoll netty-transport-native-unix-common; do
    CP="$CP:$MAVEN_REPO/io/netty/$jar/4.2.0.Final/$jar-4.2.0.Final.jar"
done
CP="$CP:$MAVEN_REPO/com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar"
CP="$CP:$MAVEN_REPO/com/fasterxml/jackson/core/jackson-core/2.18.2/jackson-core-2.18.2.jar"
CP="$CP:$MAVEN_REPO/com/fasterxml/jackson/core/jackson-annotations/2.18.2/jackson-annotations-2.18.2.jar"
CP="$CP:$MAVEN_REPO/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
CP="$CP:$MAVEN_REPO/ch/qos/logback/logback-classic/1.5.13/logback-classic-1.5.13.jar"
CP="$CP:$MAVEN_REPO/ch/qos/logback/logback-core/1.5.13/logback-core-1.5.13.jar"

echo "=== Starting IMServer ==="
java --enable-preview -cp "$CP" com.im.bootstrap.IMServer &
SERVER_PID=$!
sleep 2

echo ""
echo "=== Alice sends message to Bob ==="
echo -e "hello bob, this is alice\n/pull\n" | timeout 8 java --enable-preview -cp "$CP" com.im.client.QuickStart alice bob 127.0.0.1 8080 2>&1

echo ""
echo "=== Bob sends message to Alice ==="
echo -e "hi alice, got your message\n/pull\n" | timeout 8 java --enable-preview -cp "$CP" com.im.client.QuickStart bob alice 127.0.0.1 8080 2>&1

echo ""
echo "=== Test complete ==="
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true
