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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
@org.junit.jupiter.api.Category(VirtualThreadTestGroup.class)
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

  static Stream<Arguments> testScenarioParams() {
    return Stream.of(
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, PUT, OK , "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),

        // GET should return NOT_FOUND
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),

        // HEAD should return NOT_FOUND
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true)
    );
  }

  @ParameterizedTest
  @MethodSource("testScenarioParams")
  public void testScenario(VersionPolicy policy, String httpMethod, int status, String path, boolean shouldProceed) throws Exception {
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

    Response response = underTest.handle(context);
    if (shouldProceed) {
      assertThat(response, is(proceeded));
    }
    else {
      assertThat(response, not(proceeded));
      assertThat(response.getStatus().getCode(), is(status));
    }
  }

  /**
   * Tests that the VersionPolicyHandler correctly handles concurrent requests using Virtual Threads.
   * This test creates multiple virtual threads that simultaneously attempt to handle requests with
   * different version policies and paths.
   */
  @Test
  public void testConcurrentHandlingWithVirtualThreads() throws Exception {
    // Select a subset of test scenarios for concurrent testing
    List<Arguments> testCases = Arrays.asList(
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true)
    );
    
    int threadCount = testCases.size();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks for each test case
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to start simultaneously
            startLatch.await();
            
            Arguments args = testCases.get(index);
            VersionPolicy policy = (VersionPolicy) args.get()[0];
            String httpMethod = (String) args.get()[1];
            int status = (int) args.get()[2];
            String path = (String) args.get()[3];
            boolean shouldProceed = (boolean) args.get()[4];
            
            // Create new mocks for each thread to avoid interference
            Context threadContext = createContextMock(policy, httpMethod, path, shouldProceed);
            
            // Execute the handler
            Response response = underTest.handle(threadContext);
            
            // Verify the response
            boolean success = false;
            if (shouldProceed) {
              success = response == threadProceeded;
            } else {
              success = response != threadProceeded && response.getStatus().getCode() == status;
            }
            
            if (success) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Error in virtual thread test", e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual threads completed in time");
      
      // Verify all tests passed
      assertEquals(threadCount, successCount.get(), "Some concurrent tests failed");
      assertEquals(0, failureCount.get(), "Some concurrent tests failed");
    }
  }
  
  /**
   * Tests that the VersionPolicyHandler correctly handles a high number of concurrent requests
   * using Virtual Threads, verifying scalability under load.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a single test case but with high concurrency
    VersionPolicy policy = MIXED;
    String httpMethod = GET;
    int status = OK;
    String path = "org/sonatype/foo/maven-metadata.xml";
    boolean shouldProceed = true;
    
    int threadCount = 1000; // High number of virtual threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks for each virtual thread
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to start simultaneously
            startLatch.await();
            
            // Create new mocks for each thread to avoid interference
            Context threadContext = createContextMock(policy, httpMethod, path, shouldProceed);
            
            // Execute the handler
            Response response = underTest.handle(threadContext);
            
            // Verify the response
            if (response == threadProceeded) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Error in high concurrency virtual thread test", e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual threads completed in time");
      
      // Verify all tests passed
      assertEquals(threadCount, successCount.get(), "Some concurrent tests failed");
      assertEquals(0, failureCount.get(), "Some concurrent tests failed");
    }
  }
  
  /**
   * Helper method to create a context mock with the specified parameters.
   * This is used to create independent mocks for each virtual thread.
   */
  private Context createContextMock(VersionPolicy policy, String httpMethod, String path, boolean shouldProceed) {
    Context threadContext = mock(Context.class);
    Request threadRequest = mock(Request.class);
    Repository threadRepository = mock(Repository.class);
    MavenFacet threadMavenFacet = mock(MavenFacet.class);
    Response threadProceeded = mock(Response.class);
    
    when(threadContext.getRequest()).thenReturn(threadRequest);
    when(threadRequest.getAction()).thenReturn(httpMethod);
    when(threadContext.getRepository()).thenReturn(threadRepository);
    when(threadRepository.facet(MavenFacet.class)).thenReturn(threadMavenFacet);
    when(threadMavenFacet.getVersionPolicy()).thenReturn(policy);
    
    AttributesMap attributes = new AttributesMap();
    attributes.set(MavenPath.class, mavenPathParser.parsePath(path));
    when(threadContext.getAttributes()).thenReturn(attributes);
    
    if (shouldProceed) {
      when(threadContext.proceed()).thenReturn(threadProceeded);
    }
    
    return threadContext;
  }
}