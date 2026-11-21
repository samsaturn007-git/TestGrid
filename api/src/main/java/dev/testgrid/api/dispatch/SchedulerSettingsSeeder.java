package dev.testgrid.api.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the settings row at startup and fails startup if it is unusable. */
@Component
class SchedulerSettingsSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SchedulerSettingsSeeder.class);

  private final SchedulerSettingsRepository settings;

  SchedulerSettingsSeeder(SchedulerSettingsRepository settings) {
    this.settings = settings;
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("scheduler settings {}", settings.current());
  }
}
