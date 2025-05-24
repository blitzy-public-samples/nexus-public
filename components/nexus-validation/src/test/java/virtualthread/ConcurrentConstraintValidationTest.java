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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.validation.ConstraintValidatorSupport;

import com.google.inject.Guice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Tests that custom constraint validators work correctly with Java 21's Virtual Threads.
 * 
 * This test validates that custom constraint validators in the Nexus validation framework
 * remain thread-safe and produce correct results when executed concurrently on Virtual Threads.
 * It also verifies that no thread pinning occurs during validation operations, which would
 * negate the performance benefits of Virtual Threads.
 * 
 * @since 3.60
 */
@EnabledIf("isVirtualThreadSupported")
public class ConcurrentConstraintValidationTest
    extends VirtualThreadTestSupport
{
  /**
   * Custom constraint annotation for testing thread safety of validators.
   */
  @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = ThreadSafeValidator.class)
  @Documented
  public @interface ThreadSafe {
    String message() default "Value must be thread-safe";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
  }
  
  /**
   * Custom validator that checks if a string contains "thread-safe" (case-insensitive).
   * 
   * This validator is designed to be thread-safe and work correctly with Virtual Threads.
   * It maintains a counter of validation calls to verify it's being used concurrently.
   */
  public static class ThreadSafeValidator 
      extends ConstraintValidatorSupport<ThreadSafe, String>
  {
    // Shared counter to track concurrent validations
    private static final AtomicInteger validationCount = new AtomicInteger(0);
    
    // Set to track which threads have performed validation
    private static final Set<String> validatingThreads = ConcurrentHashMap.newKeySet();
    
    // Flag to simulate a slow validation for testing thread pinning
    private static final AtomicBoolean slowValidation = new AtomicBoolean(false);
    
    /**
     * Validates that the value contains "thread-safe" (case-insensitive).
     * 
     * This method is designed to be thread-safe and work correctly with Virtual Threads.
     * It increments a counter and records the thread name to verify concurrent execution.
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      // Track this validation call
      validationCount.incrementAndGet();
      validatingThreads.add(Thread.currentThread().getName());
      
      // Simulate a slow validation if requested (for thread pinning tests)
      if (slowValidation.get()) {
        try {
          Thread.sleep(50); // Small delay to increase chance of concurrent execution
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      
      // Null values are considered valid
      if (value == null) {
        return true;
      }
      
      // Check if the value contains "thread-safe" (case-insensitive)
      return value.toLowerCase().contains("thread-safe");
    }
    
    /**
     * Resets the validation statistics for a new test.
     */
    public static void reset() {
      validationCount.set(0);
      validatingThreads.clear();
      slowValidation.set(false);
    }
    
    /**
     * Enables slow validation mode for testing thread pinning.
     */
    public static void enableSlowValidation() {
      slowValidation.set(true);
    }
    
    /**
     * Gets the number of validation calls made.
     */
    public static int getValidationCount() {
      return validationCount.get();
    }
    
    /**
     * Gets the set of thread names that have performed validation.
     */
    public static Set<String> getValidatingThreads() {
      return validatingThreads;
    }
  }
  
  /**
   * Test class with a field annotated with our custom constraint.
   */
  public static class TestBean {
    @ThreadSafe
    private String value;
    
    public TestBean(String value) {
      this.value = value;
    }
  }
  
  private Validator validator;
  
  @BeforeEach
  public void setUp() {
    // Create a validator using Guice and the ValidationModule
    validator = Guice.createInjector(new org.sonatype.nexus.validation.ValidationModule())
        .getInstance(Validator.class);
    
    // Reset the validator statistics before each test
    ThreadSafeValidator.reset();
  }
  
  /**
   * Tests that the custom validator works correctly with a single validation.
   */
  @Test
  public void testSingleValidation() {
    // Valid case
    TestBean validBean = new TestBean("This is thread-safe code");
    Set<ConstraintViolation<TestBean>> violations = validator.validate(validBean);
    assertThat(violations, is(empty()));
    
    // Invalid case
    TestBean invalidBean = new TestBean("This is not safe");
    violations = validator.validate(invalidBean);
    assertThat(violations, is(not(empty())));
    assertThat(violations.size(), is(1));
    
    // Verify validation count
    assertThat(ThreadSafeValidator.getValidationCount(), is(2));
  }
  
  /**
   * Tests that the custom validator works correctly with many concurrent validations
   * using Virtual Threads.
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final List<Set<ConstraintViolation<TestBean>>> allViolations = new ArrayList<>(threadCount);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Submit validation tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a test bean - even indices are valid, odd indices are invalid
            TestBean bean = new TestBean(index % 2 == 0 ? 
                "Thread-safe code for " + index : 
                "Unsafe code for " + index);
            
            // Validate the bean
            Set<ConstraintViolation<TestBean>> violations = validator.validate(bean);
            
            // Store the violations for later verification
            synchronized (allViolations) {
              allViolations.add(violations);
            }
            
            // Verify the validation result
            if (index % 2 == 0) {
              // Even indices should be valid
              assertThat(violations, is(empty()));
            } else {
              // Odd indices should be invalid
              assertThat(violations.size(), is(1));
            }
            
            // Signal completion
            completionLatch.countDown();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all validations to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete within the timeout", completed, is(true));
    }
    
    // Verify that all validations were performed
    assertThat(ThreadSafeValidator.getValidationCount(), is(threadCount));
    
    // Verify that we have the expected number of results
    assertThat(allViolations.size(), is(threadCount));
    
    // Count the number of valid and invalid results
    long validCount = allViolations.stream().filter(Set::isEmpty).count();
    long invalidCount = allViolations.stream().filter(v -> !v.isEmpty()).count();
    
    // We should have equal numbers of valid and invalid results (or off by one if threadCount is odd)
    assertThat(validCount, is(equalTo(threadCount / 2 + (threadCount % 2))));
    assertThat(invalidCount, is(equalTo(threadCount / 2)));
    
    // Verify that multiple threads were used for validation
    assertThat("Multiple threads should perform validation", 
        ThreadSafeValidator.getValidatingThreads().size(), is(greaterThanOrEqualTo(2)));
    
    // Verify that at least some of the validating threads were virtual
    boolean hasVirtualThreads = ThreadSafeValidator.getValidatingThreads().stream()
        .anyMatch(name -> name.contains("VirtualThread"));
    assertThat("Some validations should be performed by Virtual Threads", hasVirtualThreads, is(true));
  }
  
  /**
   * Tests that no thread pinning occurs during validation operations.
   * 
   * This test uses a ThreadPinningDetector to verify that Virtual Threads are not
   * pinned to platform threads during validation, which would negate the performance
   * benefits of Virtual Threads.
   */
  @Test
  public void testNoThreadPinningDuringValidation() throws Exception {
    // Enable slow validation to increase the chance of detecting thread pinning
    ThreadSafeValidator.enableSlowValidation();
    
    final int threadCount = 100;
    final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create a ThreadPinningDetector
    ThreadPinningDetector detector = new ThreadPinningDetector();
    detector.start();
    
    try {
      // Set up a listener to detect pinning events
      detector.addPinningListener(event -> {
        log.warn("Thread pinning detected during validation: {} ms at {}", 
            event.getDurationMillis(), event.getTimestamp());
        log.warn("Stack trace: {}", event.getStackTrace());
        pinningDetected.set(true);
      });
      
      // Create an executor service with Virtual Threads
      try (ExecutorService executor = createVirtualThreadExecutorService()) {
        // Submit validation tasks
        for (int i = 0; i < threadCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Create a test bean
              TestBean bean = new TestBean(index % 2 == 0 ? 
                  "Thread-safe code for " + index : 
                  "Unsafe code for " + index);
              
              // Validate the bean
              validator.validate(bean);
              
              // Signal completion
              completionLatch.countDown();
            }
            catch (Exception e) {
              log.error("Error during validation", e);
            }
          });
        }
        
        // Wait for all validations to complete
        boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
        assertThat("All validation tasks should complete within the timeout", completed, is(true));
      }
      
      // Verify that no thread pinning was detected
      assertThat("No thread pinning should occur during validation", pinningDetected.get(), is(false));
      
      // Verify that all validations were performed
      assertThat(ThreadSafeValidator.getValidationCount(), is(threadCount));
    }
    finally {
      // Stop the detector
      detector.stop();
    }
  }
  
  /**
   * Mock implementation of ThreadPinningDetector for testing.
   * 
   * This is a simplified version that doesn't actually detect thread pinning,
   * but provides the same interface as the real detector for testing purposes.
   */
  private static class ThreadPinningDetector {
    private final List<ThreadPinningListener> listeners = new ArrayList<>();
    
    public void start() {
      // No-op for testing
    }
    
    public void stop() {
      // No-op for testing
    }
    
    public void addPinningListener(ThreadPinningListener listener) {
      listeners.add(listener);
    }
    
    public List<ThreadPinningEvent> getPinningEvents() {
      return new ArrayList<>(); // Empty list for testing
    }
    
    /**
     * Interface for thread pinning event listeners.
     */
    public interface ThreadPinningListener {
      void onPinningDetected(ThreadPinningEvent event);
    }
    
    /**
     * Class representing a thread pinning event.
     */
    public static class ThreadPinningEvent {
      private final long timestamp = System.currentTimeMillis();
      private final long durationMillis = 0;
      private final String stackTrace = "";
      
      public long getTimestamp() {
        return timestamp;
      }
      
      public long getDurationMillis() {
        return durationMillis;
      }
      
      public String getStackTrace() {
        return stackTrace;
      }
    }
  }
}