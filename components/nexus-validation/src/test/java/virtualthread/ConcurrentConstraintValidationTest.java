/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package virtualthread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.Constraint;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.ConstraintViolation;
import javax.validation.Payload;
import javax.validation.Validator;

import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.HibernateValidatorConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Tests that custom constraint validators work correctly with Java 21's Virtual Threads.
 * <p>
 * This test validates that custom constraint validators remain thread-safe under high concurrency
 * when executed on Virtual Threads, and that no thread pinning occurs during validation operations.
 *
 * @since 3.60
 */
public class ConcurrentConstraintValidationTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_VALIDATIONS = 1000;
  private static final int VALIDATION_TIMEOUT_SECONDS = 10;

  private Validator validator;

  @BeforeEach
  void setUp() {
    // Ensure we're running on a JVM that supports Virtual Threads
    assumeVirtualThreadSupported();

    // Create a validator instance
    HibernateValidatorConfiguration configuration = org.hibernate.validator.internal.engine.configurationimpl.HibernateValidatorConfigurationImpl
        .forAllValidatorEngines();
    validator = configuration.buildValidatorFactory().getValidator();
  }

  /**
   * Tests that a custom constraint validator works correctly when executed concurrently
   * on many Virtual Threads.
   */
  @Test
  void testConcurrentValidationWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate the concurrent validations
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_VALIDATIONS);

    // Track validation results
    AtomicInteger validCount = new AtomicInteger(0);
    AtomicInteger invalidCount = new AtomicInteger(0);
    AtomicBoolean hasErrors = new AtomicBoolean(false);

    // Create an executor with Virtual Threads
    ExecutorService executor = newVirtualThreadExecutor("validation-test-");

    try {
      // Submit validation tasks
      for (int i = 0; i < CONCURRENT_VALIDATIONS; i++) {
        final int value = i;
        executor.submit(() -> {
          try {
            // Wait for the start signal
            startLatch.await();

            // Create a test entity - even numbers are valid, odd numbers are invalid
            TestEntity entity = new TestEntity(value);

            // Validate the entity
            Set<ConstraintViolation<TestEntity>> violations = validator.validate(entity);

            // Check the validation result
            if (value % 2 == 0) {
              // Even numbers should be valid
              if (!violations.isEmpty()) {
                hasErrors.set(true);
                log.error("Validation error: even number {} was incorrectly marked as invalid", value);
              }
              else {
                validCount.incrementAndGet();
              }
            }
            else {
              // Odd numbers should be invalid
              if (violations.isEmpty()) {
                hasErrors.set(true);
                log.error("Validation error: odd number {} was incorrectly marked as valid", value);
              }
              else {
                invalidCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            hasErrors.set(true);
            log.error("Error during validation", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all validations simultaneously
      startLatch.countDown();

      // Wait for all validations to complete
      boolean completed = completionLatch.await(VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All validations should complete within the timeout", completed, is(true));

      // Verify no errors occurred
      assertThat("No validation errors should occur", hasErrors.get(), is(false));

      // Verify the expected counts
      int expectedValidCount = CONCURRENT_VALIDATIONS / 2;
      int expectedInvalidCount = CONCURRENT_VALIDATIONS - expectedValidCount;

      assertThat("Valid count should match expected", validCount.get(), equalTo(expectedValidCount));
      assertThat("Invalid count should match expected", invalidCount.get(), equalTo(expectedInvalidCount));
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that no thread pinning occurs during constraint validation with Virtual Threads.
   * <p>
   * Thread pinning would negate the performance benefits of Virtual Threads by preventing
   * the carrier thread from executing other Virtual Threads while the validation is in progress.
   */
  @Test
  void testNoThreadPinningDuringValidation() throws Exception {
    // Enable thread pinning detection
    ThreadPinningDetector.enableJdkPinningDetection();

    try {
      // Create a test entity
      TestEntity entity = new TestEntity(42);

      // Check if thread pinning occurs during validation
      boolean pinningDetected = detectThreadPinning(() -> {
        // Perform validation multiple times to increase chance of detecting pinning
        for (int i = 0; i < 100; i++) {
          validator.validate(entity);
        }
      });

      // Verify no thread pinning was detected
      assertThat("No thread pinning should occur during validation", pinningDetected, is(false));

      // Validate that the constraint validator works correctly
      Set<ConstraintViolation<TestEntity>> violations = validator.validate(entity);
      assertThat("Entity with even value should be valid", violations, is(empty()));

      // Validate an invalid entity
      TestEntity invalidEntity = new TestEntity(43);
      violations = validator.validate(invalidEntity);
      assertThat("Entity with odd value should be invalid", violations, is(not(empty())));
      assertThat("Should have one violation", violations, hasSize(1));
      assertThat("Violation message should match", 
          violations.iterator().next().getMessage(), 
          equalTo("Value must be even"));
    }
    finally {
      // Clean up
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Custom constraint annotation that requires a value to be even.
   */
  @Documented
  @Constraint(validatedBy = TestConstraintValidator.class)
  @Target({ElementType.FIELD, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface TestConstraint {
    String message() default "Value must be even";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
  }

  /**
   * Custom constraint validator that validates whether a number is even.
   */
  public static class TestConstraintValidator implements ConstraintValidator<TestConstraint, Integer> {
    @Override
    public void initialize(TestConstraint constraintAnnotation) {
      // No initialization needed
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
      if (value == null) {
        return true; // null values are considered valid
      }
      
      // Simulate some validation work
      try {
        Thread.sleep(1);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Even numbers are valid, odd numbers are invalid
      return value % 2 == 0;
    }
  }

  /**
   * Test entity class with a custom constraint.
   */
  public static class TestEntity {
    @TestConstraint
    private final Integer value;

    public TestEntity(Integer value) {
      this.value = value;
    }

    public Integer getValue() {
      return value;
    }
  }
}