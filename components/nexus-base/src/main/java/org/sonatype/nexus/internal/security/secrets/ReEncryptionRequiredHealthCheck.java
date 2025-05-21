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
package org.sonatype.nexus.internal.security.secrets;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.internal.security.secrets.tasks.ReEncryptTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskScheduler;

import com.codahale.metrics.health.HealthCheck;

import static java.lang.StringTemplate.STR;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Health check to determine if re-encryption is required.
 */
@Named("Re-encryption required")
@Singleton
public class ReEncryptionRequiredHealthCheck
    extends HealthCheck
{
  private final SecretsService secretsService;

  private final TaskScheduler taskScheduler;

  @Inject
  public ReEncryptionRequiredHealthCheck(final SecretsService secretsService, final TaskScheduler taskScheduler) {
    this.secretsService = checkNotNull(secretsService);
    this.taskScheduler = checkNotNull(taskScheduler);
  }

  @Override
  protected Result check() throws Exception {
    // Use CompletableFuture with Virtual Threads to check task status asynchronously
    CompletableFuture<Boolean> taskRunningFuture = supplyAsync(this::isReEncryptTaskRunning, Thread.ofVirtual().factory());
    
    // Use pattern matching to determine the health status
    return switch (taskRunningFuture.get()) {
      case true -> Result.healthy(STR."Re-encryption in progress. Check task logs for more information.");
      case false when secretsService.isReEncryptRequired() -> 
          Result.unhealthy(STR."Detected more than one encryption key in use. Re-encryption is required. See help documentation for information on how to start re-encryption.");
      default -> Result.healthy(STR."All secrets using same encryption key. Re-encryption is not required.");
    };
  }

  private boolean isReEncryptTaskRunning() {
    return taskScheduler.getTaskByTypeId(ReEncryptTaskDescriptor.TYPE_ID) != null;
  }
}