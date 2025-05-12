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
package org.sonatype.nexus.repository.apt.datastore.internal.hosted;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Named;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.snapshot.AptSnapshotFacetSupport;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.ContentSpecifier;
import org.sonatype.nexus.repository.view.Content;

/**
 * Implementation of snapshots for apt hosted repository.
 * 
 * This implementation leverages Java 21 features including Virtual Threads for improved
 * I/O operations performance when fetching snapshot items. The parallel processing of
 * content retrieval operations enhances throughput, especially for repositories with
 * many snapshot items.
 *
 * @since 3.31
 */
@Facet.Exposed
@Named
public class AptHostedSnapshotFacet
    extends AptSnapshotFacetSupport
{
  @Override
  protected List<SnapshotItem> fetchSnapshotItems(final List<ContentSpecifier> specs) {
    AptContentFacet apt = getRepository().facet(AptContentFacet.class);
    List<SnapshotItem> list = new ArrayList<>();
    
    // For small lists, process sequentially to avoid overhead of thread creation
    if (specs.size() <= 3) {
      for (ContentSpecifier spec : specs) {
        apt.get(spec.path).map(value -> new SnapshotItem(spec, value)).ifPresent(list::add);
      }
      return list;
    }
    
    // For larger lists, use Virtual Threads for parallel processing to improve I/O throughput
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<SnapshotItem>> futures = new ArrayList<>();
      
      // Submit each content retrieval task to the virtual thread executor
      for (ContentSpecifier spec : specs) {
        futures.add(executor.submit(() -> {
          return apt.get(spec.path)
              .map(value -> new SnapshotItem(spec, value))
              .orElse(null);
        }));
      }
      
      // Collect results from all futures
      for (Future<SnapshotItem> future : futures) {
        try {
          SnapshotItem item = future.get();
          if (item != null) {
            list.add(item);
          }
        } catch (Exception e) {
          log.warn("Error fetching snapshot item", e);
        }
      }
    }
    
    return list;
  }
}
