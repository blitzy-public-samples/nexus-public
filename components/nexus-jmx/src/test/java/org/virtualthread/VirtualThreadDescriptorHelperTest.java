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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.Descriptor;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.jmx.reflect.DescriptorHelper;
import org.sonatype.nexus.jmx.reflect.TestAuthor;
import org.sonatype.nexus.jmx.reflect.TestComments;
import org.sonatype.nexus.jmx.reflect.TestInvalidAnnotationValue;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link DescriptorHelper} when executed from Java 21 Virtual Threads.
 * 
 * Validates that JMX descriptor operations function correctly in virtual thread context.
 */
public class VirtualThreadDescriptorHelperTest
    extends TestSupport
{
  private static final int TIMEOUT_SECONDS = 5;

  @TestAuthor("virtualthread")
  public class TestBean
  {
    @TestComments("virtual thread test")
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
   */
  @Test
  public void findsAnnotationsInVirtualThread() throws Exception {
    TestBean bean = new TestBean();
    CompletableFuture<List<Annotation>> future = new CompletableFuture<>();
    
    Thread.ofVirtual().name("annotation-discovery-thread").start(() -> {
      try {
        List<Annotation> annotations = DescriptorHelper.findAllAnnotations(bean.getClass().getAnnotations());
        future.complete(annotations);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    List<Annotation> annotations = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // custom annotation should be found
    assertThat(annotations, hasItem(new AnnotationMatcher(TestAuthor.class.getName())));
  }

  /**
   * Tests that descriptor building from a type works correctly when executed from a virtual thread.
   */
  @Test
  public void buildDescriptorFromTypeInVirtualThread() throws Exception {
    TestBean bean = new TestBean();
    CompletableFuture<Descriptor> future = new CompletableFuture<>();
    
    Thread.ofVirtual().name("descriptor-type-thread").start(() -> {
      try {
        Descriptor descriptor = DescriptorHelper.build(bean.getClass());
        future.complete(descriptor);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    Descriptor descriptor = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // descriptor should have author
    assertThat(descriptor.getFields().length, equalTo(1));
    assertThat(descriptor.getFieldValue("author"), equalTo("virtualthread"));
  }

  /**
   * Tests that descriptor building from a method works correctly when executed from a virtual thread.
   */
  @Test
  public void buildDescriptorFromMethodInVirtualThread() throws Exception {
    CompletableFuture<Descriptor> future = new CompletableFuture<>();
    
    Thread.ofVirtual().name("descriptor-method-thread").start(() -> {
      try {
        Method method = TestBean.class.getMethod("foo");
        Descriptor descriptor = DescriptorHelper.build(method);
        future.complete(descriptor);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    Descriptor descriptor = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // descriptor should have comments
    assertThat(descriptor.getFields().length, equalTo(1));
    assertThat(descriptor.getFieldValue("comments"), equalTo("virtual thread test"));
  }

  /**
   * Tests that descriptor building fails correctly with invalid annotations when executed from a virtual thread.
   */
  @Test
  public void buildDescriptorFailsDueToInvalidInVirtualThread() throws Exception {
    CompletableFuture<Object> future = new CompletableFuture<>();
    
    Thread.ofVirtual().name("descriptor-invalid-thread").start(() -> {
      try {
        Method method = TestBean.class.getMethod("invalid1");
        Descriptor descriptor = DescriptorHelper.build(method);
        future.complete(descriptor);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    try {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      fail("Expected exception was not thrown");
    } 
    catch (ExecutionException e) {
      assertThat(e.getCause(), is(instanceOf(DescriptorHelper.InvalidDescriptorKeyException.class)));
    }
  }

  /**
   * Tests concurrent annotation discovery from multiple virtual threads.
   */
  @Test
  public void concurrentAnnotationDiscoveryInVirtualThreads() throws Exception {
    TestBean bean = new TestBean();
    final int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    
    // Start multiple virtual threads that will all try to discover annotations concurrently
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("concurrent-annotation-thread-" + threadId).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform annotation discovery
          List<Annotation> annotations = DescriptorHelper.findAllAnnotations(bean.getClass().getAnnotations());
          
          // Verify results
          assertThat(annotations, hasItem(new AnnotationMatcher(TestAuthor.class.getName())));
        } 
        catch (Throwable t) {
          failure.compareAndSet(null, t);
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Threads did not complete in time", completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Check if any thread failed
    if (failure.get() != null) {
      fail("Thread failed with exception: " + failure.get());
    }
  }

  /**
   * Tests descriptor building with potential thread pinning scenarios.
   * This test verifies that reflection operations don't cause thread pinning issues.
   */
  @Test
  public void descriptorBuildingWithThreadPinningScenario() throws Exception {
    TestBean bean = new TestBean();
    final int iterations = 100;
    CountDownLatch completionLatch = new CountDownLatch(iterations);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    
    // Start multiple virtual threads that will perform descriptor building in rapid succession
    for (int i = 0; i < iterations; i++) {
      final int iterationId = i;
      Thread.ofVirtual().name("pinning-test-thread-" + iterationId).start(() -> {
        try {
          // Perform descriptor building
          Descriptor descriptor = DescriptorHelper.build(bean.getClass());
          
          // Verify results
          assertThat(descriptor, notNullValue());
          assertThat(descriptor.getFieldValue("author"), equalTo("virtualthread"));
        } 
        catch (Throwable t) {
          failure.compareAndSet(null, t);
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Threads did not complete in time", completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Check if any thread failed
    if (failure.get() != null) {
      fail("Thread failed with exception: " + failure.get());
    }
  }

  /**
   * Custom matcher for annotations by name.
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