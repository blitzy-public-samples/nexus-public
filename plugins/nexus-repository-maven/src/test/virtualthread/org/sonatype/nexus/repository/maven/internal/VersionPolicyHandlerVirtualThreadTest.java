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
package org.sonatype.nexus.repository.maven.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_FOUND;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.repository.maven.VersionPolicy.MIXED;
import static org.sonatype.nexus.repository.maven.VersionPolicy.RELEASE;
import static org.sonatype.nexus.repository.maven.VersionPolicy.SNAPSHOT;

/**
 * Tests {@link VersionPolicyHandler} with Java 21 Virtual Threads
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class VersionPolicyHandlerVirtualThreadTest
    extends TestSupport
{
  @Mock
  private Context context;

  @Mock
  private Repository repository;

  @Mock
  private MavenFacet mavenFacet;

  @Mock
  private Response proceeded;

  @Mock
  private Request request;

  private VersionPolicyValidator versionPolicyValidator = new VersionPolicyValidator();

  private MavenPathParser mavenPathParser = new Maven2MavenPathParser();

  private VersionPolicyHandler underTest;

  @BeforeEach
  public void setup() {
    underTest = new VersionPolicyHandler(versionPolicyValidator);
  }

  /**
   * Provides test parameters for concurrent policy validation tests.
   */
  static Stream<Arguments> policyTestParameters() {
    return Stream.of(
        // Format: VersionPolicy, HTTP Method, Expected Status, Path, Should Proceed
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true)
    );
  }

  /**
   * Tests that the VersionPolicyHandler correctly enforces version policies when accessed concurrently
   * using Java 21 Virtual Threads.
   */
  @ParameterizedTest
  @MethodSource("policyTestParameters")
  void testPolicyEnforcementWithVirtualThreads(VersionPolicy policy, String httpMethod, int status, String path, boolean shouldProceed) throws Exception {
    // Setup mocks
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(httpMethod);
    when(context.getRepository()).thenReturn(repository);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(mavenFacet.getVersionPolicy()).thenReturn(policy);
    AttributesMap attributes = new AttributesMap();
    attributes.set(MavenPath.class, mavenPathParser.parsePath(path));
    when(context.getAttributes()).thenReturn(attributes);
    if (shouldProceed) {
      when(context.proceed()).thenReturn(proceeded);
    }

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute the policy check in a virtual thread
      Response response = executor.submit(() -> underTest.handle(context)).get();
      
      // Verify the result
      if (shouldProceed) {
        assertThat(response, is(proceeded));
      } else {
        assertThat(response, not(proceeded));
        assertThat(response.getStatus().getCode(), is(status));
      }
    }
  }

  /**
   * Tests concurrent access to the VersionPolicyHandler with multiple virtual threads.
   * This verifies that the handler is thread-safe when used with virtual threads.
   */
  @Test
  void testConcurrentPolicyEnforcementWithVirtualThreads() throws Exception {
    // Setup for RELEASE policy with a release artifact (should proceed)
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(PUT);
    when(context.getRepository()).thenReturn(repository);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(mavenFacet.getVersionPolicy()).thenReturn(RELEASE);
    AttributesMap attributes = new AttributesMap();
    attributes.set(MavenPath.class, mavenPathParser.parsePath("org/sonatype/foo/1.0.0/foo-1.0.0.jar"));
    when(context.getAttributes()).thenReturn(attributes);
    when(context.proceed()).thenReturn(proceeded);

    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Response response = underTest.handle(context);
            if (response == proceeded) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "All virtual threads should have succeeded");
    }
  }

  /**
   * Tests that the VersionPolicyHandler correctly rejects invalid version policies
   * when accessed concurrently using virtual threads.
   */
  @Test
  void testConcurrentPolicyRejectionWithVirtualThreads() throws Exception {
    // Setup for SNAPSHOT policy with a release artifact (should be rejected)
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(PUT);
    when(context.getRepository()).thenReturn(repository);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(mavenFacet.getVersionPolicy()).thenReturn(SNAPSHOT);
    AttributesMap attributes = new AttributesMap();
    attributes.set(MavenPath.class, mavenPathParser.parsePath("org/sonatype/foo/1.0.0/foo-1.0.0.jar"));
    when(context.getAttributes()).thenReturn(attributes);

    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger rejectionCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Response response = underTest.handle(context);
            if (response.getStatus().getCode() == BAD_REQUEST) {
              rejectionCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify all threads were rejected
      assertEquals(threadCount, rejectionCount.get(), "All virtual threads should have been rejected");
    }
  }

  /**
   * Tests that the VersionPolicyHandler correctly handles mixed version policies
   * when accessed concurrently using virtual threads.
   */
  @Test
  void testConcurrentMixedPolicyWithVirtualThreads() throws Exception {
    // Setup for MIXED policy (should accept both release and snapshot artifacts)
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(PUT);
    when(context.getRepository()).thenReturn(repository);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(mavenFacet.getVersionPolicy()).thenReturn(MIXED);
    when(context.proceed()).thenReturn(proceeded);

    // Number of concurrent threads to use
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks with alternating release and snapshot paths
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            AttributesMap attributes = new AttributesMap();
            // Alternate between release and snapshot paths
            String path = (index % 2 == 0) ?
                "org/sonatype/foo/1.0.0/foo-1.0.0.jar" :
                "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar";
            attributes.set(MavenPath.class, mavenPathParser.parsePath(path));
            when(context.getAttributes()).thenReturn(attributes);

            Response response = underTest.handle(context);
            if (response == proceeded) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "All virtual threads should have succeeded with MIXED policy");
    }
  }
}