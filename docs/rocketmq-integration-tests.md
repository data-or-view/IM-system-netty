# RocketMQ Integration Tests

This project keeps normal unit tests independent from Docker. Real RocketMQ
broker checks run only when the `rocketmq-it` Maven profile is enabled.

## Local Broker

The tests use `IM_ROCKETMQ_IT_NAME_SERVER` when it is set, otherwise they use
`127.0.0.1:9876`.

Example:

```bash
export IM_ROCKETMQ_IT_NAME_SERVER=127.0.0.1:9876
```

The MySQL business-DLQ tests use these variables when set:

```bash
export IM_IT_MYSQL_JDBC_URL='jdbc:mysql://127.0.0.1:3306/im_system_rocketmq_it?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
export IM_IT_MYSQL_USER=root
export IM_IT_MYSQL_PASSWORD=123456
```

When `IM_IT_MYSQL_JDBC_URL` is not set, the test creates and uses the isolated
database `im_system_rocketmq_it`.

The broker must expose both the name-server port and the broker listen port to
the test JVM. A quick local check:

```bash
nc -vz 127.0.0.1 9876
nc -vz 127.0.0.1 10911
```

## Commands

RocketMQ infrastructure broker tests:

```bash
mvn -pl im-infrastructure/im-infrastructure-message-rocketmq -am -Procketmq-it -Dtest=RocketMqMessageQueueIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Business DLQ compensation through real MySQL and RocketMQ:

```bash
mvn -pl im-server -am -Procketmq-it -Dtest=DbBusinessMessageDlqStoreIT -Dsurefire.failIfNoSpecifiedTests=false test
```

## Isolation

Integration tests generate unique `topicPrefix`, `producerGroup`, and
`consumerGroupPrefix` values for each run. RocketMQ topic names do not allow
dot characters, so test prefixes use hyphen-separated names such as
`im-it-roundtrip-...-`.
