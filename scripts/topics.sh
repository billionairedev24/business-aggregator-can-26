#!/usr/bin/env sh
# Creates Kafka topics (one per module aggregate) + DLQs.
for t in identity.user merchants.merchant merchants.storefront catalogue.listing food.menu booking.quote booking.booking availability.availability orders.order fulfilment.delivery payments.payment payments.escrow payments.refund payments.dispute payments.payout payments.payout_account trust.dispute trust.review messaging.message messaging.ticket food.kitchen; do
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t --partitions 6 --replication-factor 1
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t.dlq --partitions 1 --replication-factor 1
done
# Settings & compliance workstream: team changes, verification renewals, API keys, webhook endpoints.
for t in merchants.member merchants.verification developer.api_key developer.webhook_endpoint; do
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t --partitions 6 --replication-factor 1
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t.dlq --partitions 1 --replication-factor 1
done
