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
package org.sonatype.nexus.testsuite.testsupport.cleanup;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;

/**
 * Under SQL Cleanup uses the component/assets table thus rarely needs to wait on changes once REST endpoints have
 * returned.
 * <p>
 * Uses Java 21 virtual threads for I/O-bound operations to improve resource utilization and scalability.
 */
@Named
@Singleton
@FeatureFlag(name = DATASTORE_ENABLED)
public class DatastoreCleanupTestHelper
    implements CleanupTestHelper
{
  @Override
  public void waitForMixedSearch() {
    // noop
  }

  @Override
  public void waitForComponentsIndexed(final int count) {
    // noop
  }

  @Override
  public void waitForLastDownloadSet(final int count) {
    // noop
  }

  @Override
  public void awaitLastBlobUpdatedTimePassed(final int time) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the sleep operation to a virtual thread
      executor.submit(() -> {
        try {
          Thread.sleep(time * 1000L);
          return null; // Required for Callable interface
        }
        catch (InterruptedException e) {
          // Preserve interrupt status
          Thread.currentThread().interrupt();
          throw new RuntimeException("Sleep interrupted", e);
        }
      }).get(); // Wait for the virtual thread to complete
    }
    catch (InterruptedException e) {
      // Preserve interrupt status
      Thread.currentThread().interrupt();
      throw new RuntimeException("Virtual thread execution interrupted", e);
    }
    catch (ExecutionException e) {
      throw new RuntimeException("Error during virtual thread execution", e.getCause());
    }
  }

  @Override
  public void waitForIndex() {
    // noop
  }
}