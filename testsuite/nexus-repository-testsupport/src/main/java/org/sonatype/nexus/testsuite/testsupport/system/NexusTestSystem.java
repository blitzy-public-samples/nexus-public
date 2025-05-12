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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.testsuite.testsupport.fixtures.CapabilitiesRule;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Main test system for Nexus Repository Manager integration tests.
 * <p>
 * This class provides access to various test fixtures and utilities for testing Nexus Repository Manager.
 * It has been updated to leverage Java 21 features, particularly virtual threads for improved concurrency
 * in repository and capability orchestration.
 * <p>
 * For JUnit Jupiter tests, use {@link NexusTestSystemExtension} with the {@code @ExtendWith} annotation:
 * <pre>
 * {@code
 * @ExtendWith(NexusTestSystem.NexusTestSystemExtension.class)
 * class MyTest {
 *     @Inject
 *     private Provider<NexusTestSystem> nexusProvider;
 *     
 *     // Test methods...
 * }
 * }
 * </pre>
 *
 * @since 3.60
 * @see NexusTestSystemSupport
 * @see RepositoryTestSystem
 * @see CapabilitiesRule
 */
@FeatureFlag(name = "nexus.test.base")
@Named
@Singleton
public class NexusTestSystem
    extends NexusTestSystemSupport<RepositoryTestSystem, CapabilitiesRule>
{
  /**
   * Creates a new NexusTestSystem with the specified repository test system and capability registry.
   *
   * @param repositoryTestSystem the repository test system
   * @param capabilityRegistry the capability registry
   */
  @Inject
  public NexusTestSystem(final RepositoryTestSystem repositoryTestSystem, final CapabilityRegistry capabilityRegistry)
  {
    super(repositoryTestSystem, new CapabilitiesRule(() -> capabilityRegistry));
  }
  
  /**
   * Creates a virtual thread executor optimized for repository operations.
   * <p>
   * This executor is suitable for I/O-bound repository operations like creating, updating,
   * or deleting repositories, which can benefit from the lightweight threading model of
   * virtual threads in Java 21.
   *
   * @return an executor service using virtual threads for repository operations
   * @since Java 21
   */
  public ExecutorService createRepositoryExecutor() {
    return createNamedVirtualThreadExecutor("nexus-repository-ops");
  }
  
  /**
   * Creates a virtual thread executor optimized for capability operations.
   * <p>
   * This executor is suitable for capability management operations like creating, enabling,
   * disabling, or removing capabilities, which can benefit from the lightweight threading model
   * of virtual threads in Java 21.
   *
   * @return an executor service using virtual threads for capability operations
   * @since Java 21
   */
  public ExecutorService createCapabilityExecutor() {
    return createNamedVirtualThreadExecutor("nexus-capability-ops");
  }
  
  @Override
  protected void beforeAllWithVirtualThreads(ExtensionContext context) throws Exception {
    log.info("Setting up test environment with Java 21 virtual threads");
    super.beforeAllWithVirtualThreads(context);
  }
}
