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
package org.sonatype.nexus.testsuite.testsupport;

import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.pax.exam.NexusPaxExamSupport;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.tools.DeadBlobFinder;
import org.sonatype.nexus.repository.tools.DeadBlobResult;
import org.sonatype.nexus.testsuite.testsupport.system.NexusTestSystemSupport;
import org.sonatype.nexus.testsuite.testsupport.system.NexusTestSystemSupport.NexusTestSystemRule;

import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFileExtend;
import static org.ops4j.pax.exam.options.WrappedUrlProvisionOption.OverwriteMode.MERGE;

/**
 * Base class for integration tests that provides common functionality and lifecycle hooks.
 * <p>
 * This class has been updated for Java 21 compatibility, including support for virtual threads
 * and JUnit Jupiter (JUnit 5) test lifecycle management.
 * <p>
 * Requirements:
 * - Java 21 or higher runtime
 * - JUnit Jupiter 5.10.1 or higher
 * - OSGi/Karaf 4.4.4 or higher for container tests
 *
 * @since 3.60.0 Updated for Java 21 compatibility
 */
@ExamReactorStrategy(PerSuite.class)
public abstract class ITSupport
    extends NexusPaxExamSupport
{
  // JUnit Jupiter extensions
  private TestInfo testInfo;

  @RegisterExtension
  public final NexusTestSystemRule nexusTestSystemRule = new NexusTestSystemRule(this::nexusTestSystem);

  @Inject
  private PoolingHttpClientConnectionManager connectionManager;

  @Inject
  private DeadBlobFinder<?> deadBlobFinder;

  @Inject
  private RepositoryManager repositoryManager;

  @Inject
  @Named("http://localhost:${application-port}${nexus-context-path}")
  private URL nexusUrl;

  @Inject
  @Named("https://localhost:${application-port-ssl}${nexus-context-path}")
  private URL nexusSecureUrl;

  /**
   * Configures Nexus for testing with Java 21 compatibility.
   * <p>
   * This method sets up the necessary OSGi bundles and configuration for testing
   * with Java 21 features including virtual threads.
   *
   * @param distribution the base distribution option
   * @return configured options array
   */
  public static Option[] configureNexus(final Option distribution) {
    return NexusPaxExamSupport.options(
        distribution,

        editConfigurationFileExtend(SYSTEM_PROPERTIES_FILE, "nexus.security.randompassword", "false"),
        editConfigurationFileExtend(NEXUS_PROPERTIES_FILE, "nexus.scripts.allowCreation", "true"),
        editConfigurationFileExtend(NEXUS_PROPERTIES_FILE, "nexus.search.event.handler.flushOnCount", "1"),
        // Enable virtual threads for testing
        editConfigurationFileExtend(NEXUS_PROPERTIES_FILE, "nexus.thread.virtual.enabled", "true"),

        // install common test-support features
        nexusFeature("org.sonatype.nexus.testsuite", "nexus-repository-testsupport"),
        wrappedBundle(maven("org.awaitility", "awaitility").versionAsInProject()).overwriteManifest(MERGE).imports("*")
    );
  }

  /**
   * Make sure Nexus is responding on the standard base URL before continuing.
   * <p>
   * This method uses Awaitility to poll the Nexus URL until it responds or times out.
   */
  @BeforeEach
  public void waitForNexus() {
    await().atMost(30, TimeUnit.SECONDS)
        .ignoreExceptionsMatching(exception -> !(exception instanceof InterruptedException))
        .until(responseFrom(nexusUrl));
  }

  /**
   * Verifies there are no unreleased HTTP connections in Nexus. This check runs automatically after each test but tests
   * may as well run this check manually at suitable points during their execution.
   * <p>
   * With Java 21 virtual threads, connection management becomes even more important as the number
   * of concurrent operations can be significantly higher.
   */
  @AfterEach
  public void verifyNoConnectionLeak() {
    // Some proxy repos directly serve upstream content, i.e. the connection to the upstream repo is actively used while
    // streaming out the response to the client. An HTTP client considers a response done when the content length has
    // been reached at which point the client/test can continue while NX still has to release the upstream connection
    // (cf. ResponseEntityProxy which releases a connection after the last byte has been handed out to the client).
    // So allow for some delay when checking the connection pool.
    await().atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(connectionManager.getTotalStats().getLeased(), is(0)));
  }

  /**
   * Verifies that no dead blobs exist in the repository after test execution.
   * <p>
   * This method is automatically called after each test but can be disabled by overriding
   * {@link #shouldVerifyNoDeadBlobs()}.
   */
  @AfterEach
  public void verifyNoDeadBlobs() {
    if (shouldVerifyNoDeadBlobs()) {
      doVerifyNoDeadBlobs();
    }
  }

  /**
   * Determines whether dead blob verification should be performed.
   * <p>
   * Subclasses can override this method to disable dead blob verification when needed.
   *
   * @return true if dead blob verification should be performed, false otherwise
   */
  protected boolean shouldVerifyNoDeadBlobs() {
    return true;
  }

  /**
   * Performs the actual verification of dead blobs.
   * <p>
   * Left protected to allow specific subclasses to override where this behaviour is expected due to minimal test setup.
   * <p>
   * This implementation uses parallel stream processing which benefits from Java 21's virtual threads
   * for improved concurrency when processing large repositories.
   */
  protected void doVerifyNoDeadBlobs() {
    Map<String, List<DeadBlobResult<?>>> badRepos = StreamSupport.stream(repositoryManager.browse().spliterator(), true)
        .map(repository -> deadBlobFinder.find(repository, shouldIgnoreMissingBlobRefs()))
        .flatMap(Collection::stream)
        .collect(Collectors.groupingBy(DeadBlobResult::getRepositoryName));

    if (!badRepos.isEmpty()) {
      log.error("Detected dead blobs: {}", badRepos);
      throw new IllegalStateException("Dead blobs detected!");
    }
  }

  /**
   * Allow specific tests to override this behaviour where "missing" blobs are valid due to the test setup.
   *
   * @return true if missing blob references should be ignored, false otherwise
   */
  protected boolean shouldIgnoreMissingBlobRefs() {
    return false;
  }

  /**
   * Returns the base Nexus URL for testing.
   *
   * @return the Nexus URL
   */
  protected URL nexusUrl() {
    // eventually this might switch to nexusSecurUrl based on a property
    return nexusUrl;
  }

  /**
   * Returns the secure Nexus URL for testing.
   *
   * @return the secure Nexus URL
   */
  protected URL nexusSecureUrl() {
    return nexusSecureUrl;
  }

  /**
   * Creates a virtual thread executor service for concurrent test operations.
   * <p>
   * This method leverages Java 21's virtual threads for highly concurrent operations
   * with minimal resource overhead. Virtual threads are managed by the JVM and don't
   * require a large thread pool.
   *
   * @return an executor service that creates a new virtual thread for each task
   * @since 3.60.0
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates a thread factory that produces virtual threads.
   * <p>
   * This is useful for integration tests that need to create custom thread pools
   * with virtual threads.
   *
   * @return a thread factory that creates virtual threads
   * @since 3.60.0
   */
  protected ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a thread factory that produces platform threads.
   * <p>
   * This is useful for comparison testing between virtual and platform threads.
   *
   * @return a thread factory that creates platform threads
   * @since 3.60.0
   */
  protected ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Returns the current test name from JUnit Jupiter's TestInfo.
   * <p>
   * This replaces the JUnit 4 TestName rule functionality.
   *
   * @return the current test method name
   * @since 3.60.0
   */
  protected String getTestMethodName() {
    return testInfo != null ? testInfo.getTestMethod().map(method -> method.getName()).orElse("unknown") : "unknown";
  }

  /**
   * Sets the TestInfo for this test instance.
   * <p>
   * This method is automatically called by JUnit Jupiter's dependency injection.
   *
   * @param testInfo the TestInfo for the current test
   * @since 3.60.0
   */
  @BeforeEach
  public void setTestInfo(TestInfo testInfo) {
    this.testInfo = testInfo;
  }

  /**
   * Returns the test system to use for this integration test.
   * <p>
   * Subclasses must implement this method to provide the appropriate test system.
   *
   * @return the test system support instance
   */
  protected abstract NexusTestSystemSupport<?,?> nexusTestSystem();
}
