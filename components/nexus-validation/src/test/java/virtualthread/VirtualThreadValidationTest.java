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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintViolation;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests that validate the Bean Validation framework works correctly with Java 21's Virtual Threads.
 * 
 * @since 3.60
 */
public class VirtualThreadValidationTest
    extends VirtualThreadTestSupport
{
  private ValidatorFactory factory;

  @BeforeEach
  public void setUp() {
    assumeVirtualThreadSupported();
    factory = Validation.buildDefaultValidatorFactory();
  }

  /**
   * Simple entity with validation constraints.
   */
  private static class SimpleEntity
  {
    @NotNull
    private String name;

    @Size(min = 3, max = 50)
    private String description;

    public SimpleEntity(String name, String description) {
      this.name = name;
      this.description = description;
    }
  }

  /**
   * Parent entity with cascading validation.
   */
  private static class Parent
  {
    @Valid
    private Child child;

    public Parent(Child child) {
      this.child = child;
    }
  }

  /**
   * Child entity with cascading validation.
   */
  private static class Child
  {
    @Valid
    private GrandChild grandChild;

    public Child(GrandChild grandChild) {
      this.grandChild = grandChild;
    }
  }

  /**
   * GrandChild entity with validation constraints.
   */
  private static class GrandChild
  {
    @NotNull
    private String name;

    public GrandChild(String name) {
      this.name = name;
    }
  }

  /**
   * Entity with map containing validatable objects.
   */
  private static class WithMap
  {
    @Valid
    private Map<String, Object> contents;

    public WithMap(Map<String, Object> contents) {
      this.contents = contents;
    }
  }

  /**
   * Tests that basic validation works correctly on a virtual thread.
   */
  @Test
  public void testBasicValidationOnVirtualThread() throws Exception {
    Callable<Set<ConstraintViolation<SimpleEntity>>> validationTask = () -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Create an entity with validation errors
      SimpleEntity entity = new SimpleEntity(null, "ab");
      
      // Validate the entity
      return factory.getValidator().validate(entity);
    };
    
    // Execute the validation on a virtual thread
    Set<ConstraintViolation<SimpleEntity>> violations = callVirtual(validationTask);
    
    // Verify the validation results
    assertThat(violations, hasSize(2));
    
    // Check for the expected constraint violations
    boolean foundNameViolation = false;
    boolean foundDescriptionViolation = false;
    
    for (ConstraintViolation<SimpleEntity> violation : violations) {
      if (violation.getPropertyPath().toString().equals("name")) {
        assertThat(violation.getMessage(), equalTo("must not be null"));
        foundNameViolation = true;
      }
      else if (violation.getPropertyPath().toString().equals("description")) {
        assertThat(violation.getMessage(), equalTo("size must be between 3 and 50"));
        foundDescriptionViolation = true;
      }
    }
    
    assertThat("Name violation should be found", foundNameViolation, is(true));
    assertThat("Description violation should be found", foundDescriptionViolation, is(true));
  }

  /**
   * Tests that cascading validation works correctly on a virtual thread.
   */
  @Test
  public void testCascadingValidationOnVirtualThread() throws Exception {
    Callable<Set<ConstraintViolation<Parent>>> validationTask = () -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Create a parent with a child and grandchild that has a validation error
      Parent parent = new Parent(new Child(new GrandChild(null)));
      
      // Validate the parent, which should cascade to child and grandchild
      return factory.getValidator().validate(parent);
    };
    
    // Execute the validation on a virtual thread
    Set<ConstraintViolation<Parent>> violations = callVirtual(validationTask);
    
    // Verify the validation results
    assertThat(violations, hasSize(1));
    ConstraintViolation<Parent> violation = violations.iterator().next();
    assertThat(violation.getPropertyPath().toString(), equalTo("child.grandChild.name"));
    assertThat(violation.getMessage(), equalTo("must not be null"));
  }

  /**
   * Tests that map-based cascading validation works correctly on a virtual thread.
   */
  @Test
  public void testMapValidationOnVirtualThread() throws Exception {
    Callable<Set<ConstraintViolation<WithMap>>> validationTask = () -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Create a parent with a child and grandchild that has a validation error
      Parent parent = new Parent(new Child(new GrandChild(null)));
      WithMap withMap = new WithMap(Map.of("foo", parent));
      
      // Validate the map container, which should cascade to its contents
      return factory.getValidator().validate(withMap);
    };
    
    // Execute the validation on a virtual thread
    Set<ConstraintViolation<WithMap>> violations = callVirtual(validationTask);
    
    // Verify the validation results
    assertThat(violations, hasSize(1));
    ConstraintViolation<WithMap> violation = violations.iterator().next();
    assertThat(violation.getPropertyPath().toString(), equalTo("contents[foo].child.grandChild.name"));
    assertThat(violation.getMessage(), equalTo("must not be null"));
  }

  /**
   * Tests concurrent validation of multiple objects on virtual threads.
   */
  @Test
  public void testConcurrentValidationOnVirtualThreads() throws Exception {
    // Number of concurrent validations to perform
    final int concurrentValidations = 1000;
    
    // Create a list of entities to validate
    List<SimpleEntity> entities = new ArrayList<>(concurrentValidations);
    for (int i = 0; i < concurrentValidations; i++) {
      // Every other entity has validation errors
      if (i % 2 == 0) {
        entities.add(new SimpleEntity(null, "ab"));
      } else {
        entities.add(new SimpleEntity("Valid Name " + i, "Valid Description " + i));
      }
    }
    
    // Counter for tracking validation results
    AtomicInteger validEntities = new AtomicInteger(0);
    AtomicInteger invalidEntities = new AtomicInteger(0);
    
    // Create a latch to wait for all validations to complete
    CountDownLatch latch = new CountDownLatch(concurrentValidations);
    
    // Create an executor service with virtual threads
    ExecutorService executor = newVirtualThreadExecutor("validation-test-");
    
    try {
      // Submit validation tasks for each entity
      for (SimpleEntity entity : entities) {
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            assertThat(Thread.currentThread().getName(), is(notNullValue()));
            
            // Validate the entity
            Set<ConstraintViolation<SimpleEntity>> violations = factory.getValidator().validate(entity);
            
            // Update counters based on validation result
            if (violations.isEmpty()) {
              validEntities.incrementAndGet();
            } else {
              invalidEntities.incrementAndGet();
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all validations to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete within the timeout", completed, is(true));
      
      // Verify the validation results
      assertThat("Half of the entities should be valid", validEntities.get(), equalTo(concurrentValidations / 2));
      assertThat("Half of the entities should be invalid", invalidEntities.get(), equalTo(concurrentValidations / 2));
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent cascading validation with complex object hierarchies on virtual threads.
   */
  @Test
  public void testConcurrentCascadingValidationOnVirtualThreads() throws Exception {
    // Number of concurrent validations to perform
    final int concurrentValidations = 500;
    
    // Create a list of parent entities to validate
    List<Parent> parents = new ArrayList<>(concurrentValidations);
    for (int i = 0; i < concurrentValidations; i++) {
      // Every other entity has validation errors
      if (i % 2 == 0) {
        parents.add(new Parent(new Child(new GrandChild(null))));
      } else {
        parents.add(new Parent(new Child(new GrandChild("Valid Name " + i))));
      }
    }
    
    // Counter for tracking validation results
    AtomicInteger validEntities = new AtomicInteger(0);
    AtomicInteger invalidEntities = new AtomicInteger(0);
    
    // Submit validation tasks for each parent entity
    Future<?>[] futures = callConcurrently(concurrentValidations, () -> {
      // Get the parent entity for this task
      int index = (int) (Thread.currentThread().threadId() % concurrentValidations);
      Parent parent = parents.get(index);
      
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isNotPinned());
      
      // Validate the parent entity (with cascading validation)
      Set<ConstraintViolation<Parent>> violations = factory.getValidator().validate(parent);
      
      // Update counters based on validation result
      if (violations.isEmpty()) {
        validEntities.incrementAndGet();
      } else {
        invalidEntities.incrementAndGet();
        
        // Verify the violation details for invalid entities
        assertThat(violations, hasSize(1));
        ConstraintViolation<Parent> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString(), equalTo("child.grandChild.name"));
        assertThat(violation.getMessage(), equalTo("must not be null"));
      }
      
      return violations.size();
    });
    
    // Wait for all futures to complete
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    
    // Verify the validation results
    assertThat("Half of the entities should be valid", validEntities.get(), equalTo(concurrentValidations / 2));
    assertThat("Half of the entities should be invalid", invalidEntities.get(), equalTo(concurrentValidations / 2));
  }

  /**
   * Tests that validation operations do not cause thread pinning.
   */
  @Test
  public void testValidationDoesNotCauseThreadPinning() throws Exception {
    // Create a complex entity with cascading validation
    Parent parent = new Parent(new Child(new GrandChild(null)));
    
    // Check if validation causes thread pinning
    boolean pinningDetected = detectThreadPinning(() -> {
      // Perform validation in a loop to increase chances of detecting pinning
      for (int i = 0; i < 100; i++) {
        factory.getValidator().validate(parent);
      }
    });
    
    // Verify that no thread pinning was detected
    assertThat("Validation should not cause thread pinning", pinningDetected, is(false));
  }

  /**
   * Tests the performance of validation on virtual threads compared to a baseline.
   */
  @Test
  public void testValidationPerformanceOnVirtualThreads() throws Exception {
    // Number of concurrent validations to perform
    final int concurrentValidations = 10000;
    
    // Create a validation task
    Callable<Long> validationTask = () -> {
      long startTime = System.nanoTime();
      
      // Create and validate a complex entity
      Parent parent = new Parent(new Child(new GrandChild("Name")));
      Set<ConstraintViolation<Parent>> violations = factory.getValidator().validate(parent);
      assertThat(violations, hasSize(0));
      
      return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startTime);
    };
    
    // Measure the execution time of a single validation on the current thread (warm-up)
    long singleThreadTime = validationTask.call();
    log.info("Single thread validation time: {} µs", singleThreadTime);
    
    // Execute the validation task concurrently on multiple virtual threads
    long startTime = System.nanoTime();
    Future<Long>[] futures = callConcurrently(concurrentValidations, () -> validationTask.call());
    
    // Calculate statistics from the results
    long totalTime = 0;
    long maxTime = 0;
    long minTime = Long.MAX_VALUE;
    
    for (Future<Long> future : futures) {
      long time = future.get(30, TimeUnit.SECONDS);
      totalTime += time;
      maxTime = Math.max(maxTime, time);
      minTime = Math.min(minTime, time);
    }
    
    long avgTime = totalTime / concurrentValidations;
    long totalExecutionTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    
    log.info("Concurrent validation statistics for {} validations:", concurrentValidations);
    log.info("  Total execution time: {} ms", totalExecutionTime);
    log.info("  Average validation time: {} µs", avgTime);
    log.info("  Min validation time: {} µs", minTime);
    log.info("  Max validation time: {} µs", maxTime);
    
    // Verify that concurrent validation on virtual threads is efficient
    // The total time should be much less than singleThreadTime * concurrentValidations
    // because virtual threads allow for efficient concurrent execution
    long serialExecutionEstimate = singleThreadTime * concurrentValidations / 1000; // Convert to ms
    
    assertThat("Total execution time should be significantly less than serial execution time",
        totalExecutionTime, lessThan(serialExecutionEstimate / 10));
    
    // Verify that individual validation operations have reasonable performance
    assertThat("Average validation time should be reasonable", 
        avgTime, lessThan(singleThreadTime * 10));
    
    // Verify that we achieved a high level of concurrency
    double concurrencyLevel = (double) concurrentValidations / (totalExecutionTime / 1000.0 * 1000000.0 / avgTime);
    log.info("  Estimated concurrency level: {}", concurrencyLevel);
    
    assertThat("Should achieve a reasonable level of concurrency", 
        concurrencyLevel, greaterThanOrEqualTo(10.0));
  }
}