package dev.testgrid.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.run.CreateRunRequest;
import dev.testgrid.api.run.RunCreationService;
import dev.testgrid.api.support.PostgresTestcontainer;
import dev.testgrid.api.support.RabbitTestcontainer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The background loops end to end: a committed run reaches the broker with no explicit tick. The
 * nudge on RUN_CREATED and the timers both lead there; the test only bounds the total time.
 */
@SpringBootTest(properties = {"testgrid.profile=local", "testgrid.dispatch.enabled=true"})
@Import({PostgresTestcontainer.class, RabbitTestcontainer.class})
class DispatchLoopTest {

  @Autowired JdbcClient jdbc;
  @Autowired RunCreationService runCreation;
  @Autowired RabbitTemplate rabbit;
  @Autowired DispatchLoops loops;

  @Test
  void aCreatedRunIsDispatchedAndPublishedByTheLoops() {
    assertThat(loops.isRunning()).isTrue();
    var request =
        new CreateRunRequest(
            "checkout-smoke",
            List.of(new CreateRunRequest.Target(Executor.LOCAL, Engine.CHROMIUM)),
            Trigger.MANUAL,
            UUID.randomUUID().toString(),
            null,
            null,
            null);
    UUID runId = runCreation.create(LocalProject.ID, request).run().id();

    Message message = rabbit.receive("tg.targets.chromium", 10_000);

    assertThat(message).as("message published within 10 s").isNotNull();
    assertThat(message.getMessageProperties().<String>getHeader(Topology.RUN_ID_HEADER))
        .isEqualTo(runId.toString());
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox_events o JOIN run_targets t"
                        + " ON t.id = o.aggregate_id WHERE t.run_id = :r"
                        + " AND o.published_at IS NOT NULL")
                .param("r", runId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
