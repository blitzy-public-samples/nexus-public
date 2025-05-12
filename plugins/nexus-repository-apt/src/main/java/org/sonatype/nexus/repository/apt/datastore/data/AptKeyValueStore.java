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
package org.sonatype.nexus.repository.apt.datastore.data;

import javax.inject.Inject;

import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.content.kv.KeyValueStore;

import com.google.inject.assistedinject.Assisted;

/**
 * APT key-value store implementation for the datastore.
 * <p>
 * This implementation is compatible with Java 21 and uses Guice 7.0.0 for dependency injection.
 * It extends the base KeyValueStore to provide APT-specific key-value storage functionality.
 *
 * @since 3.38
 */
public class AptKeyValueStore
    extends KeyValueStore<AptKeyValueDAO>
{
  /**
   * Creates a new APT key-value store instance.
   *
   * @param sessionSupplier   the data session supplier
   * @param contentStoreName  the content store name
   */
  @Inject
  public AptKeyValueStore(final DataSessionSupplier sessionSupplier, @Assisted final String contentStoreName) {
    super(sessionSupplier, contentStoreName, AptKeyValueDAO.class);
  }
}