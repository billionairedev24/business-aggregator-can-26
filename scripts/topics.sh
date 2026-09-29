#!/usr/bin/env sh
# Creates the Kafka topics (one per module aggregate, `<module>.<aggregate>`) and their `.dlq`. Idempotent.
#
#   scripts/topics.sh                      # through the compose `kafka` container (--profile events)
#   KAFKA_TOPICS_CMD=kafka-topics.sh KAFKA_TOPICS_BOOTSTRAP=localhost:9092 scripts/topics.sh   # your own Kafka
#
# The compose `kafka-topics` one-shot service runs this automatically with `--profile events`.
# Cloud topics (replication 3, retention, ACLs) are provisioned per environment — backlog S-25.
set -e
CMD=${KAFKA_TOPICS_CMD:-"docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh"}
BOOTSTRAP=${KAFKA_TOPICS_BOOTSTRAP:-kafka:29092}
RF=${KAFKA_REPLICATION_FACTOR:-1}

create() {
  $CMD --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$1" --partitions 6 --replication-factor "$RF"
  $CMD --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$1.dlq" --partitions 1 --replication-factor "$RF"
}

for t in identity.user merchants.merchant merchants.storefront catalogue.listing food.menu booking.quote booking.booking availability.availability orders.order fulfilment.delivery payments.payment payments.escrow payments.refund payments.dispute payments.payout payments.payout_account trust.dispute trust.review messaging.message messaging.ticket food.kitchen; do
  create "$t"
done
# Settings & compliance workstream: team changes, verification renewals, API keys, webhook endpoints.
for t in merchants.member merchants.verification developer.api_key developer.webhook_endpoint; do
  create "$t"
done
