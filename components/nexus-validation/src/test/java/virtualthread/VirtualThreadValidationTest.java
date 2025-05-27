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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintViolation;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.NotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests to validate that Bean Validation framework works correctly with Java 21's Virtual Threads.
 * 
 * These tests verify that validation operations can be efficiently executed on Virtual Threads
 * without thread pinning, and that concurrent validation of multiple objects maintains correctness.
 * 
 * The test suite includes validation of simple constraints, cascading validation with nested objects,
 * and map-based validation, all executed on Virtual Threads. It also tests high-concurrency scenarios
 * to ensure thread safety and correctness under load, which is critical for Nexus Repository's
 * validation framework in a Java 21 environment.
 * 
 * @since 3.60
 */
@EnabledIf("isVirtualThreadSupported")
public class VirtualThreadValidationTest
    extends VirtualThreadTestSupport
{
  private ValidatorFactory factory;

  @BeforeEach
  public void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
  }

  /**
   * Test entity with validation constraints.
   */
  private static class TestEntity
  {
    @NotNull
    private String name;

    public TestEntity(String name) {
      this.name = name;
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
   * Entity with map containing validated objects.
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
   * Tests that simple validation works correctly when executed on a Virtual Thread.
   * This verifies that constraint violations are correctly detected and reported
   * when running on Virtual Threads.
   */
  @Test
  public void testSimpleValidationOnVirtualThread() throws Exception {
    supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertCurrentThreadIsVirtual();
      
      // Perform validation
      TestEntity entity = new TestEntity(null);
      Set<ConstraintViolation<TestEntity>> violations = factory.getValidator().validate(entity);
      
      // Verify violations
      assertThat(violations, hasSize(1));
      ConstraintViolation<TestEntity> violation = violations.iterator().next();
      assertThat(violation.getPropertyPath().toString(), equalTo("name"));
      assertThat(violation.getMessage(), equalTo("must not be null"));
      
      return true;
    });
  }

  /**
   * Tests that cascading validation works correctly when executed on a Virtual Thread.
   * This verifies that constraint violations in nested objects are correctly detected
   * and reported when running on Virtual Threads.
   */
  @Test
  public void testCascadingValidationOnVirtualThread() throws Exception {
    supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertCurrentThreadIsVirtual();
      
      // Perform cascading validation
      Parent parent = new Parent(new Child(new GrandChild(null)));
      Set<ConstraintViolation<Parent>> violations = factory.getValidator().validate(parent);
      
      // Verify violations
      assertThat(violations, hasSize(1));
      ConstraintViolation<Parent> violation = violations.iterator().next();
      assertThat(violation.getPropertyPath().toString(), equalTo("child.grandChild.name"));
      assertThat(violation.getMessage(), equalTo("must not be null"));
      
      return true;
    });
  }

  /**
   * Tests that map validation works correctly when executed on a Virtual Thread.
   * This verifies that constraint violations in objects contained within maps are
   * correctly detected and reported when running on Virtual Threads.
   */
  @Test
  public void testMapValidationOnVirtualThread() throws Exception {
    supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertCurrentThreadIsVirtual();
      
      // Perform validation with map
      Parent parent = new Parent(new Child(new GrandChild(null)));
      WithMap withMap = new WithMap(Map.of("foo", parent));
      Set<ConstraintViolation<WithMap>> violations = factory.getValidator().validate(withMap);
      
      // Verify violations
      assertThat(violations, hasSize(1));
      ConstraintViolation<WithMap> violation = violations.iterator().next();
      assertThat(violation.getPropertyPath().toString(), equalTo("contents[foo].child.grandChild.name"));
      assertThat(violation.getMessage(), equalTo("must not be null"));
      
      return true;
    });
  }

  /**
   * Tests concurrent validation of multiple objects using Virtual Threads.
   * This verifies that the Bean Validation framework works correctly under high concurrency
   * when using Virtual Threads, ensuring that validation operations maintain correctness
   * and thread safety.
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads() throws Exception {
    // Number of concurrent validations to perform
    final int concurrentValidations = 1000;
    
    // Create a countdown latch to synchronize thread start
    final CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Track successful validations
      AtomicInteger successfulValidations = new AtomicInteger(0);
      
      // Submit validation tasks
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < concurrentValidations; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Verify we're running on a Virtual Thread
          assertTrue(Thread.currentThread().isVirtual(), 
              "Task " + index + " not running on a Virtual Thread");
          
          // Perform validation (alternating between valid and invalid entities)
          TestEntity entity = new TestEntity(index % 2 == 0 ? null : "Valid Name");
          Set<ConstraintViolation<TestEntity>> violations = factory.getValidator().validate(entity);
          
          // Verify violations
          if (index % 2 == 0) {
            // Should have a violation (null name)
            assertThat(violations, hasSize(1));
            ConstraintViolation<TestEntity> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString(), equalTo("name"));
            assertThat(violation.getMessage(), equalTo("must not be null"));
          } else {
            // Should have no violations (valid name)
            assertThat(violations, hasSize(0));
          }
          
          successfulValidations.incrementAndGet();
          return true;
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all validations to complete
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "Validation task failed");
      }
      
      // Verify all validations were successful
      assertThat(successfulValidations.get(), equalTo(concurrentValidations));
    }
  }

  /**
   * Tests concurrent cascading validation using Virtual Threads.
   * This verifies that the Bean Validation framework correctly handles cascading validation
   * under high concurrency when using Virtual Threads, ensuring that validation operations
   * maintain correctness and thread safety with complex object hierarchies.
   * 
   * This test is particularly important for Nexus Repository as it validates that the
   * Bean Validation framework can handle complex object hierarchies in a highly concurrent
   * environment, which is common in repository operations like uploads and metadata processing.
   */
  @Test
  public void testConcurrentCascadingValidationWithVirtualThreads() throws Exception {
    // Number of concurrent validations to perform
    final int concurrentValidations = 500;
    
    // Create a countdown latch to synchronize thread start
    final CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Track successful validations
      AtomicInteger successfulValidations = new AtomicInteger(0);
      
      // Submit validation tasks
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < concurrentValidations; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Verify we're running on a Virtual Thread
          assertTrue(Thread.currentThread().isVirtual(), 
              "Task " + index + " not running on a Virtual Thread");
          
          // Create parent with child and grandchild (alternating between valid and invalid)
          GrandChild grandChild = new GrandChild(index % 2 == 0 ? null : "Valid Name");
          Parent parent = new Parent(new Child(grandChild));
          
          // Perform cascading validation
          Set<ConstraintViolation<Parent>> violations = factory.getValidator().validate(parent);
          
          // Verify violations
          if (index % 2 == 0) {
            // Should have a violation (null name in grandchild)
            assertThat(violations, hasSize(1));
            ConstraintViolation<Parent> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString(), equalTo("child.grandChild.name"));
            assertThat(violation.getMessage(), equalTo("must not be null"));
          } else {
            // Should have no violations (valid name in grandchild)
            assertThat(violations, hasSize(0));
          }
          
          successfulValidations.incrementAndGet();
          return true;
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all validations to complete
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "Validation task failed");
      }
      
      // Verify all validations were successful
      assertThat(successfulValidations.get(), equalTo(concurrentValidations));
    }
  }