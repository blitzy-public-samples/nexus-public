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
package org.sonatype.nexus.internal.security.secrets.tasks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.sonatype.nexus.common.entity.Continuations;
import org.sonatype.nexus.crypto.secrets.SecretData;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.crypto.secrets.SecretsStore;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.logging.task.TaskLogType;
import org.sonatype.nexus.logging.task.TaskLogging;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

@Named(ReEncryptTaskDescriptor.TYPE_ID)
@TaskLogging(TaskLogType.TASK_LOG_ONLY)
public class ReEncryptTask
    extends TaskSupport
    implements Cancelable
{
  private final SecretsService secretsService;

  private final SecretsStore secretsStore;

  private final Duration delayTime;

  @Inject
  public ReEncryptTask(
      final SecretsService secretsService,
      final SecretsStore secretsStore,
      @Named("${nexus.distributed.events.fetch.interval.seconds:-5}") int pollInterval)
  {
    this.secretsService = checkNotNull(secretsService);
    this.secretsStore = checkNotNull(secretsStore);
    this.delayTime = Duration.ofSeconds(pollInterval).multiply(2);
  }

  @Override
  public String getMessage() {
    return "Re-encrypting secrets with specified key id";
  }

  @Override
  protected Object execute() throws Exception {
    waitActiveKeyProcessing();
    log.info(STR."Started re-encrypting secrets with provided keyId");
    String secretKeyId = taskConfiguration().getString("keyId");
    int processed = reEncrypt(secretKeyId);
    log.info(STR."Completed re-encryption of secrets with keyId '\{secretKeyId}'. Processed \{processed} secrets");
    return processed;
  }

  private int reEncrypt(String keyId) {
    AtomicInteger processedCount = new AtomicInteger(0);
    List<SecretData> page = secretsStore.fetchWithDifferentKeyId(keyId, Continuations.BROWSE_LIMIT);
    
    try (ProgressLogIntervalHelper progress = new ProgressLogIntervalHelper(log, 60)) {
      while (!page.isEmpty()) {
        checkCancellation();
        
        // Process batch of secrets in parallel using virtual threads
        CountDownLatch latch = new CountDownLatch(page.size());
        ConcurrentLinkedQueue<Exception> exceptions = new ConcurrentLinkedQueue<>();
        
        for (SecretData secret : page) {
          Thread.startVirtualThread(() -> {
            try {
              // Use pattern matching for SecretData
              if (secret instanceof SecretData data) {
                secretsService.reEncrypt(data, keyId);
              }
              processedCount.incrementAndGet();
            } 
            catch (Exception e) {
              exceptions.add(e);
            }
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all virtual threads to complete
        latch.await();
        
        // Check if any exceptions occurred during processing
        if (!exceptions.isEmpty()) {
          Exception firstException = exceptions.poll();
          throw new RuntimeException(STR."Error during secret re-encryption: \{firstException.getMessage()}", firstException);
        }
        
        int currentCount = processedCount.get();
        progress.info(STR."Processed \{currentCount} secrets.");
        
        // Get next batch of secrets
        page = secretsStore.fetchWithDifferentKeyId(keyId, Continuations.BROWSE_LIMIT);
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(STR."Re-encryption task was interrupted after processing \{processedCount.get()} secrets");
    }
    
    return processedCount.get();
  }

  /**
   * Delay to wait for other nodes to process the event that sets the new active key
   */
  private void waitActiveKeyProcessing() {
    try {
      // Use Java 21's more efficient time handling for sleep
      Thread.sleep(delayTime);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug(STR."Wait for active key processing was interrupted after \{delayTime}");
    }
  }
}