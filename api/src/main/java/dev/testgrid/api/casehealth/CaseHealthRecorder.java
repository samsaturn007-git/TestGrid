package dev.testgrid.api.casehealth;

import dev.testgrid.api.casehealth.CaseHealthRepository.Key;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-07 FR-4, "recomputed incrementally on TARGET_FINISHED": the completion transaction appends
 * each case's outcome to its window. Rows are locked in key order, after any failure-group rows, so
 * concurrent completions always lock in the same global order and can't deadlock.
 */
@Service
public class CaseHealthRecorder {

  private final CaseHealthRepository repository;
  private final CaseHealthProperties properties;

  public CaseHealthRecorder(CaseHealthRepository repository, CaseHealthProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  /** {@code outcomes}: one window letter per case of this attempt (SKIPPED left out). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Map<Key, Character> outcomes) {
    new TreeMap<>(outcomes)
        .forEach((key, outcome) -> {
          String window = repository.lockWindow(key, properties.windowSize());
          String next = CaseHealthWindow.append(window, outcome, properties.windowSize());
          repository.store(key, CaseHealthWindow.of(next, properties));
        });
  }
}
