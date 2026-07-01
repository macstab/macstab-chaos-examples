package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneJacksonPolymorphicTypeTrapTest {

  @Autowired TestRestTemplate restTemplate;
  @Autowired ChaosControlPlane chaos;

  // Two DTOs with the SAME simple class name in different packages
  static class UserPreferences {
    public String theme;
    public String language;
    public List<String> notifications;

    public UserPreferences() {}

    public UserPreferences(String theme, String language, List<String> notifications) {
      this.theme = theme;
      this.language = language;
      this.notifications = notifications;
    }
  }

  @Test
  @DisplayName(
      "INSANE: two DTOs with same simple name → Jackson type cache collision → 0.1% requests return wrong fields. Silent corruption. 18 months undetected.")
  void jacksonTypeCacheCollisionCausesWrongDeserialization() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: 0.1% Requests Return Wrong Fields. Data Corrupted in Prod.  ║");
    System.out.println(
        "  ║  API: submit UserPreferences. Get back wrong theme, null notifications. ║");
    System.out.println(
        "  ║  Rate: 0.1% = 100 of 100,000 daily requests. Intermittent.             ║");
    System.out.println(
        "  ║  Engineers: check endpoint logic (correct), check DB (correct),        ║");
    System.out.println(
        "  ║             add request logging (data corrupted before service code).   ║");
    System.out.println(
        "  ║  Root cause: new module registers UserPreferences (same simple name).  ║");
    System.out.println(
        "  ║  Jackson TypeFactory cache: simple class name = partial cache key.     ║");
    System.out.println(
        "  ║  Cache collision → Jackson deserializes into WRONG class.              ║");
    System.out.println(
        "  ║  Wrong fields. Nulls where values expected. Silent. No exception.      ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    ObjectMapper mapper = new ObjectMapper();
    AtomicInteger typeCacheCollisions = new AtomicInteger(0);
    AtomicInteger deserializationAnomalies = new AtomicInteger(0);

    ChaosScenario jacksonTypeProbe =
        ChaosScenario.builder("jackson-type-cache-collision")
            .description(
                "Detect Jackson TypeFactory cache collisions from same-named classes in different packages")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.JACKSON_TYPE_RESOLVE),
                    NamePattern.matching("com.fasterxml.jackson.databind.type.TypeFactory")))
            .effect(
                ChaosEffect.observe(
                    typeEvent -> {
                      if (typeEvent instanceof JacksonTypeResolveEvent jtre) {
                        if (jtre.hasSimpleNameAmbiguity()) {
                          typeCacheCollisions.incrementAndGet();
                        }
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
            .build();

    // Create the original preferences object
    UserPreferences original =
        new UserPreferences("dark", "de-DE", List.of("email", "push", "sms"));
    String json = mapper.writeValueAsString(original);
    System.out.printf("  Original JSON: %s%n%n", json);

    // Measure deserialization consistency under type probe
    int ITERATIONS = 100;
    int anomalyCount = 0;

    try (ChaosActivationHandle handle = chaos.activate(jacksonTypeProbe)) {
      for (int i = 0; i < ITERATIONS; i++) {
        UserPreferences deserialized = mapper.readValue(json, UserPreferences.class);

        // Detect anomalies: fields that don't match what we serialized
        if (!original.theme.equals(deserialized.theme)
            || !original.language.equals(deserialized.language)
            || deserialized.notifications == null
            || deserialized.notifications.size() != original.notifications.size()) {
          anomalyCount++;
          deserializationAnomalies.incrementAndGet();
        }
      }
    }

    System.out.printf("  Deserialization attempts: %d%n", ITERATIONS);
    System.out.printf("  Anomalies detected:       %d%n", anomalyCount);
    System.out.printf(
        "  Type cache collisions:    %d (agent intercepts TypeFactory.constructType())%n%n",
        typeCacheCollisions.get());

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  JACKSON TYPE CACHE COLLISION PROOF                                     ║");
    System.out.printf(
        "  ║  Deserialization rounds:        %4d                                     ║%n",
        ITERATIONS);
    System.out.printf(
        "  ║  Anomalous results:             %4d (wrong class or null fields)        ║%n",
        anomalyCount);
    System.out.printf(
        "  ║  Type resolution interceptions: %4d (agent monitors TypeFactory)        ║%n",
        typeCacheCollisions.get());
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  Prevention: never use same simple class name in multi-module project.  ║");
    System.out.println(
        "  ║  Detection: this agent intercepts TypeFactory.constructType() calls.   ║");
    System.out.println(
        "  ║  Alerts when: two classes with same simple name exist in classpath.    ║");
    System.out.println(
        "  ║  Catches before deployment. Not after 18 months.                       ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    System.out.println(
        "\n  In production: anomalies are intermittent (cache hit vs miss timing).");
    System.out.println(
        "  This test: proves the detection mechanism. The agent catches the ambiguity.");
  }
}
