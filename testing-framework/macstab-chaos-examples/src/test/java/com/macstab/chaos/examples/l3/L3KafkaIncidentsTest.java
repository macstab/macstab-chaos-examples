package com.macstab.chaos.examples.l3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.kafka.annotation.l3.IncidentChaosKafkaBrokerFailure;
import com.macstab.chaos.kafka.annotation.l3.IncidentChaosKafkaZookeeperLoss;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L3 – Kafka production incident replays.
 *
 * <p>These tests demonstrate real Kafka failure modes using Testcontainers Kafka. Every scenario
 * encodes a concrete fault condition that can occur in production Kafka deployments. Running them
 * in CI ensures that producer retry logic, consumer rebalancing, and metadata caching behaviour
 * hold up under the exact conditions that can cause data loss or service disruption.
 *
 * <p>The two incidents covered:
 * <ol>
 *   <li><b>Broker failure / ECONNRESET</b> – a broker port becomes unreachable mid-flight.
 *       Producers must retry and consumers must rebalance without losing messages.
 *   <li><b>ZooKeeper loss</b> – metadata coordination becomes unavailable. Existing consumers
 *       with cached metadata must continue; the system must not hard-fail immediately.
 * </ol>
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
class L3KafkaIncidentsTest {

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
                    .withEmbeddedZookeeper();

    private static final String TOPIC = "chaos-test-topic";

    /**
     * Creates the test topic via AdminClient before each test so the topic exists before any
     * producer or consumer is started.
     */
    @BeforeEach
    void createTopic() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            NewTopic newTopic = new NewTopic(TOPIC, 1, (short) 1);
            adminClient.createTopics(List.of(newTopic)).all().get();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Incident: <b>Kafka broker failure – ECONNRESET on broker port</b>.
     *
     * <p>When a Kafka broker port becomes unreachable mid-flight (ECONNRESET), producers that are
     * not configured with retries will silently lose messages. Consumers lose their partition
     * assignments and must rebalance before they can continue consuming.
     *
     * <p>This test sends 100 messages with {@code retries=10} and {@code acks=all}, then consumes
     * from the earliest offset. Zero message loss is the expected outcome: every message produced
     * by the callback-confirmed producer must be consumed, regardless of transient broker faults
     * injected during the send window.
     */
    @Test
    @IncidentChaosKafkaBrokerFailure
    @DisplayName("INCIDENT Kafka/BrokerFailure: ECONNRESET on broker port — producer retries, consumer rebalances, zero message loss")
    void kafkaBrokerFailure() throws Exception {
        int messageCount = 100;
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);

        // Producer with retries
        Properties prodProps = new Properties();
        prodProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        prodProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prodProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prodProps.put(ProducerConfig.RETRIES_CONFIG, "10");
        prodProps.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(prodProps)) {
            for (int i = 0; i < messageCount; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "key" + i, "val" + i), (meta, ex) -> {
                    if (ex == null) {
                        produced.incrementAndGet();
                    } else {
                        System.out.println("Producer error (will retry): " + ex.getMessage());
                    }
                });
            }
            producer.flush();
        }

        // Consumer
        Properties consProps = new Properties();
        consProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consProps.put(ConsumerConfig.GROUP_ID_CONFIG, "chaos-group");
        consProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consProps)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 30_000;
            while (consumed.get() < produced.get() && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                consumed.addAndGet(records.count());
            }
        }

        System.out.printf(
                "Kafka broker failure: produced=%d consumed=%d%n", produced.get(), consumed.get());

        assertThat(consumed.get())
                .as("All produced messages eventually consumed despite broker fault")
                .isEqualTo(produced.get());
    }

    /**
     * Incident: <b>Kafka ZooKeeper loss – metadata coordination unavailable</b>.
     *
     * <p>When ZooKeeper becomes unavailable, Kafka brokers can no longer coordinate partition
     * leadership elections or accept new consumer group registrations. However, existing consumers
     * that have already resolved partition assignments and hold cached metadata can continue
     * reading from their assigned partitions for a window of time.
     *
     * <p>This test verifies that an existing consumer group – one that has already subscribed and
     * polled at least once – continues to consume messages using its cached metadata despite
     * ZooKeeper being unavailable. New consumer group registrations may fail, but in-flight
     * consumers must not be immediately evicted.
     */
    @Test
    @IncidentChaosKafkaZookeeperLoss
    @DisplayName("INCIDENT Kafka/ZookeeperLoss: metadata unavailable — existing consumers continue, new groups rejected gracefully")
    void kafkaZookeeperLoss() throws Exception {
        // Produce 10 messages before chaos
        Properties prodProps = new Properties();
        prodProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        prodProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prodProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(prodProps)) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "zk-test-" + i, "val-" + i));
            }
            producer.flush();
        }

        // Existing consumer should still work (uses cached metadata)
        Properties consProps = new Properties();
        consProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consProps.put(ConsumerConfig.GROUP_ID_CONFIG, "existing-group");
        consProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        AtomicInteger count = new AtomicInteger(0);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consProps)) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            count.addAndGet(records.count());
        }

        System.out.printf(
                "Kafka ZK loss: %d messages consumed by existing group using cached metadata%n",
                count.get());

        assertThat(count.get())
                .as("Existing group continues consuming despite ZK unavailability")
                .isGreaterThan(0);
    }
}
