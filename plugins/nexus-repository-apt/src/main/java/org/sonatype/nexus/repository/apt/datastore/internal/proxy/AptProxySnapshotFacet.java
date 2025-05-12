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
package org.sonatype.nexus.repository.apt.datastore.internal.proxy;

import java.io.IOException;
import java.util.List;

import javax.inject.Named;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.apt.datastore.internal.snapshot.AptSnapshotFacetSupport;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem.ContentSpecifier;

/**
 * Implementation of snapshots for apt proxy repository.
 * 
 * This implementation leverages Java 21 features through its parent class,
 * including Virtual Threads for improved I/O operations when fetching and
 * processing snapshot items from remote repositories.
 *
 * @since 3.31
 */
@Facet.Exposed
@Named
public class AptProxySnapshotFacet
    extends AptSnapshotFacetSupport
{
  /**
   * Fetches snapshot items from the remote repository using the proxy facet.
   * 
   * This implementation delegates to {@link AptProxyFacet#getSnapshotItems},
   * which handles the remote retrieval of snapshot content. The parent class
   * {@link AptSnapshotFacetSupport} uses Virtual Threads for parallel processing
   * of these items to improve I/O performance.
   *
   * @param specs the specifications for items to fetch
   * @return a list of snapshot items retrieved from the remote repository
   * @throws IOException if an error occurs during fetching
   */
  @Override
  protected List<SnapshotItem> fetchSnapshotItems(final List<ContentSpecifier> specs) throws IOException {
    AptProxyFacet proxyFacet = getRepository().facet(AptProxyFacet.class);
    return proxyFacet.getSnapshotItems(specs);
  }
}
