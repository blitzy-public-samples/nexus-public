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
package org.virtualthread;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.Descriptor;

import org.sonatype.nexus.jmx.reflect.DescriptorHelper;
import org.sonatype.nexus.jmx.reflect.TestAuthor;
import org.sonatype.nexus.jmx.reflect.TestComments;
import org.sonatype.nexus.jmx.reflect.TestInvalidAnnotationValue;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that verify the {@link DescriptorHelper} class functions correctly when executed from Java 21 Virtual Threads.
 * <p>
 * These tests validate that annotation scanning, descriptor building, and error handling work properly
 * within virtual thread context, ensuring JMX reflection capabilities remain functional with the new
 * lightweight threading model.
 *
 * @since 3.60
 */
public class VirtualThreadDescriptorHelperTest
    extends VirtualThreadTestSupport
{
  @BeforeEach
  void assumeVirtualThreads() {
    // Skip tests if virtual threads are not supported
    assumeVirtualThreadSupported();
  }

  @TestAuthor("virtual-thread-tester")
  public class TestBean
  {
    @TestComments("virtual thread test comment")
    public void foo() {
      // empty
    }

    @TestInvalidAnnotationValue(@TestComments("invalid"))
    public void invalid1() {
      // empty
    }
  }

  /**
   * Tests that annotation discovery works correctly when executed from a virtual thread.
   * <p>
   * This test verifies that the DescriptorHelper can properly scan and find annotations
   * when the operation is performed within a virtual thread context.
   */
  @Test
  void findsAnnotationsInVirtualThread() throws InterruptedException {
    TestBean bean = new TestBean();
    AtomicReference<List<Annotation>> result = new AtomicReference<>();
    
    Thread.ofVirtual().start(() -> {
      result.set(DescriptorHelper.findAllAnnotations(bean.getClass().getAnnotations()));
    }).join();

    // Verify that the custom annotation was found
    assertThat(result.get(), hasItem(new AnnotationMatcher(TestAuthor.class.getName())));
  }

  /**
   * Tests that descriptor building from a type works correctly when executed from a virtual thread.
   * <p>
   * This test verifies that the DescriptorHelper can properly build descriptors from class types
   * when the operation is performed within a virtual thread context.
   */
  @Test
  void buildDescriptorFromTypeInVirtualThread() throws InterruptedException {
    TestBean bean = new TestBean();
    AtomicReference<Descriptor> result = new AtomicReference<>();
    
    Thread.ofVirtual().start(() -> {
      result.set(DescriptorHelper.build(bean.getClass()));
    }).join();

    // Verify that the descriptor has the expected author field
    assertThat(result.get().getFields().length, equalTo(1));
    assertThat(result.get().getFieldValue("author"), equalTo("virtual-thread-tester"));
  }

  /**
   * Tests that descriptor building from a method works correctly when executed from a virtual thread.
   * <p>
   * This test verifies that the DescriptorHelper can properly build descriptors from method objects
   * when the operation is performed within a virtual thread context.
   */
  @Test
  void buildDescriptorFromMethodInVirtualThread() throws InterruptedException, NoSuchMethodException {
    Method method = TestBean.class.getMethod("foo");
    AtomicReference<Descriptor> result = new AtomicReference<>();
    
    Thread.ofVirtual().start(() -> {
      result.set(DescriptorHelper.build(method));
    }).join();

    // Verify that the descriptor has the expected comments field
    assertThat(result.get().getFields().length, equalTo(1));
    assertThat(result.get().getFieldValue("comments"), equalTo("virtual thread test comment"));
  }

  /**
   * Tests that descriptor building fails correctly for invalid annotations when executed from a virtual thread.
   * <p>
   * This test verifies that the DescriptorHelper properly throws InvalidDescriptorKeyException
   * when encountering invalid descriptor keys within a virtual thread context.
   */
  @Test
  void buildDescriptorFailsDueToInvalidInVirtualThread() throws NoSuchMethodException, InterruptedException {
    Method method = TestBean.class.getMethod("invalid1");
    AtomicReference<Exception> caughtException = new AtomicReference<>();
    
    Thread.ofVirtual().start(() -> {
      try {
        DescriptorHelper.build(method);
      }
      catch (Exception e) {
        caughtException.set(e);
      }
    }).join();

    // Verify that the expected exception was thrown
    assertTrue(caughtException.get() instanceof DescriptorHelper.InvalidDescriptorKeyException,
        "Expected InvalidDescriptorKeyException but got: " + 
        (caughtException.get() != null ? caughtException.get().getClass().getName() : "null"));
  }

  /**
   * Tests that multiple concurrent virtual threads can safely use DescriptorHelper.
   * <p>
   * This test verifies that the DescriptorHelper can handle concurrent access from multiple
   * virtual threads without interference or corruption of results.
   */
  @Test
  void concurrentVirtualThreadAccess() throws InterruptedException {
    TestBean bean = new TestBean();
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start multiple virtual threads that will all try to use DescriptorHelper concurrently
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform various DescriptorHelper operations
          DescriptorHelper.findAllAnnotations(bean.getClass().getAnnotations());
          DescriptorHelper.build(bean.getClass());
          DescriptorHelper.build(TestBean.class.getMethod("foo"));
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Release all threads to start concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify that all threads completed successfully
    assertTrue(completed, "Not all virtual threads completed in time");
    assertThat("No exceptions should have been thrown", threadException.get(), equalTo(null));
  }

  /**
   * Tests that DescriptorHelper operations do not cause thread pinning when executed in virtual threads.
   * <p>
   * Thread pinning occurs when a virtual thread is forced to run on its carrier thread for an extended period,
   * preventing the carrier thread from executing other virtual threads. This can happen with synchronized blocks
   * or when calling certain blocking operations.
   * <p>
   * This test verifies that DescriptorHelper operations don't cause thread pinning, which would reduce
   * the scalability benefits of virtual threads.
   */
  @Test
  void noThreadPinningDuringReflectionOperations() throws InterruptedException {
    TestBean bean = new TestBean();
    
    // Check if finding annotations causes thread pinning
    boolean pinningDetected = detectThreadPinning(() -> {
      DescriptorHelper.findAllAnnotations(bean.getClass().getAnnotations());
    });
    assertFalse(pinningDetected, "Thread pinning detected during annotation scanning");
    
    // Check if building descriptors from types causes thread pinning
    pinningDetected = detectThreadPinning(() -> {
      DescriptorHelper.build(bean.getClass());
    });
    assertFalse(pinningDetected, "Thread pinning detected during descriptor building from type");
    
    // Check if building descriptors from methods causes thread pinning
    pinningDetected = detectThreadPinning(() -> {
      try {
        DescriptorHelper.build(TestBean.class.getMethod("foo"));
      }
      catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    });
    assertFalse(pinningDetected, "Thread pinning detected during descriptor building from method");
  }

  /**
   * Custom matcher for annotations that matches by annotation type name.
   */
  private static class AnnotationMatcher
      extends CustomTypeSafeMatcher<Annotation>
  {
    private final String annotationName;

    public AnnotationMatcher(final String annotationName) {
      super("Matches: " + annotationName);
      this.annotationName = annotationName;
    }

    @Override
    protected boolean matchesSafely(final Annotation annotation) {
      return annotation.annotationType().getName().equals(annotationName);
    }
  }
}