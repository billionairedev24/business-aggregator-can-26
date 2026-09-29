#!/usr/bin/env sh
# Creates Kafka topics (one per module aggregate) + DLQs.
for t in identity.user merchants.merchant merchants.storefront catalogue.listing food.menu booking.quote booking.booking availability.availability orders.order fulfilment.delivery payments.payment payments.escrow trust.dispute trust.review messaging.message messaging.ticket; do
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t --partitions 6 --replication-factor 1
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $t.dlq --partitions 1 --replication-factor 1
done
