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
package org.sonatype.nexus.testsuite.testsupport.maven;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.sonatype.goodies.common.ComponentSupport;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;

/**
 * Exercise maven goals against a project using Java 21 virtual threads for concurrent build/test operations.
 * 
 * @since 3.60
 */
public class MavenRunner
  extends ComponentSupport
{
  /**
   * Runs Maven goals against the specified deployment.
   *
   * @param deployment the Maven deployment to run against
   * @param goals the Maven goals to execute
   * @throws VerificationException if Maven execution fails
   */
  public void run(final MavenDeployment deployment, final String... goals) throws VerificationException {
    doRun(deployment, goals);
  }

  /**
   * Runs Maven goals against the specified deployment with retry capability.
   *
   * @param shouldRetry supplier that determines if execution should be retried on failure
   * @param deployment the Maven deployment to run against
   * @param goals the Maven goals to execute
   * @throws VerificationException if Maven execution fails and retry conditions are not met
   */
  public void run(final Supplier<Boolean> shouldRetry, final MavenDeployment deployment, final String... goals) throws VerificationException {
    do {
      try {
        doRun(deployment, goals);
        //once successful no need to bother retrying
        return;
      }
      catch (Throwable t) {
        log.error("Maven execution failed", t);
      }
    }
    while (shouldRetry.get());
  }

  /**
   * Runs Maven goals concurrently against multiple deployments using Java 21 virtual threads.
   *
   * @param deployments list of Maven deployments to run against
   * @param goals the Maven goals to execute on each deployment
   * @throws VerificationException if any Maven execution fails
   */
  public void runConcurrently(final List<MavenDeployment> deployments, final String... goals) throws VerificationException {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<?>[] futures = deployments.stream()
          .map(deployment -> CompletableFuture.runAsync(() -> {
            try {
              doRun(deployment, goals);
            }
            catch (VerificationException e) {
              throw new RuntimeException("Maven execution failed for " + deployment, e);
            }
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      CompletableFuture.allOf(futures).join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof VerificationException) {
        throw (VerificationException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Internal method to execute Maven goals.
   */
  private void doRun(final MavenDeployment deployment, final String... goals) throws VerificationException {
    log.debug("Deploying: {}", deployment);
    Verifier verifier = new Verifier(deployment.getProjectDir().getAbsolutePath());
    verifier.addCliOption("-s " + deployment.settingsFile().getAbsolutePath());
    verifier.addCliOption(
        ("-DaltDeploymentRepository=local-nexus-admin::default::"+ deployment.getDeployUrl()).replace("//", "////"));
    verifier.addCliOption("-Dmaven.compiler.source=" + deployment.getJavaVersion());
    verifier.addCliOption("-Dmaven.compiler.target=" + deployment.getJavaVersion());

    log.info("Executing maven goals {}", Arrays.asList(goals));
    verifier.executeGoals(Arrays.asList(goals));

    verifier.verifyErrorFreeLog();
    log.debug("Finished running maven from {}", deployment.getProjectDir());
  }
}