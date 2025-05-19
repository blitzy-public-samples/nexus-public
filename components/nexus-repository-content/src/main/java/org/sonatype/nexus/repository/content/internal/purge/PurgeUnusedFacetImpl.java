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
package org.sonatype.nexus.repository.content.internal.purge;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;

import org.slf4j.MDC;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;

import static com.google.common.base.Preconditions.checkArgument;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;
import static org.sonatype.nexus.repository.FacetSupport.State.STARTED;

/**
 * Implementation of {@link PurgeUnusedFacet} that uses Virtual Threads for I/O-bound database operations.
 * 
 * @since 3.24
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named
public class PurgeUnusedFacetImpl
    extends FacetSupport
    implements PurgeUnusedFacet
{
  @Override
  @Guarded(by = STARTED)
  public void purgeUnused(final int numberOfDays) {
    checkArgument(numberOfDays > 0, "Number of days must be greater then zero");
    log.info(STR."Purging unused components from repository \{getRepository().getName()}");

    ContentFacetSupport contentFacet = (ContentFacetSupport) getRepository().facet(ContentFacet.class);
    ComponentStore<?> componentStore = contentFacet.stores().componentStore;
    AssetStore<?> assetStore = contentFacet.stores().assetStore;
    int contentRepositoryId = contentFacet.contentRepositoryId();
    
    // Capture current MDC context to propagate to virtual threads
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    // Create atomic counters to track purged items
    AtomicInteger purgedAssets = new AtomicInteger(0);
    AtomicInteger purgedComponents = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit asset purge task to virtual thread
      Future<?> assetFuture = executor.submit(() -> {
        try {
          // Restore MDC context in virtual thread
          if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
          }
          
          // Purge unused assets
          int count = assetStore.purgeNotRecentlyDownloaded(contentRepositoryId, numberOfDays);
          purgedAssets.set(count);
          log.debug(STR."Deleted \{count} unused assets without components");
        } catch (Exception e) {
          log.error(STR."Error purging unused assets: \{e.getMessage()}", e);
        } finally {
          // Clear MDC context
          MDC.clear();
        }
      });
      
      // Submit component purge task to virtual thread
      Future<?> componentFuture = executor.submit(() -> {
        try {
          // Restore MDC context in virtual thread
          if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
          }
          
          // Purge unused components
          int count = componentStore.purgeNotRecentlyDownloaded(contentRepositoryId, numberOfDays);
          purgedComponents.set(count);
          log.debug(STR."Deleted \{count} unused components and their assets");
        } catch (Exception e) {
          log.error(STR."Error purging unused components: \{e.getMessage()}", e);
        } finally {
          // Clear MDC context
          MDC.clear();
        }
      });
      
      // Wait for both tasks to complete
      try {
        assetFuture.get();
        componentFuture.get();
      } catch (Exception e) {
        log.error(STR."Error waiting for purge tasks to complete: \{e.getMessage()}", e);
      }
    }
    
    log.info(STR."Purged \{purgedComponents.get()} unused components from repository \{getRepository().getName()}");
  }
}