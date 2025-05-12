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
package org.sonatype.nexus.content.raw.internal.store;

import org.sonatype.nexus.repository.content.store.AssetDAO;

/**
 * Raw format-specific {@link AssetDAO}.
 * 
 * This is a marker interface that extends the base {@link AssetDAO} without adding additional methods.
 * It's sealed to restrict which classes can implement it, following Java 21 best practices.
 *
 * @since 3.24
 */
public sealed interface RawAssetDAO
    extends AssetDAO
    permits org.sonatype.nexus.content.raw.internal.store.RawAssetDAO.FromDb
{
  /**
   * Marker interface for MyBatis implementation.
   */
  non-sealed interface FromDb extends RawAssetDAO {
    // no additional methods
  }
}