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
package org.sonatype.nexus.repository.apt.datastore.internal.store;

import org.sonatype.nexus.repository.content.store.AssetBlobDAO;

/**
 * APT format-specific extension of {@link AssetBlobDAO}.
 * 
 * @since 3.31
 * @see AssetBlobDAO
 */
public interface AptAssetBlobDAO
    extends AssetBlobDAO
{
  // This is a marker interface that extends AssetBlobDAO without adding additional methods.
  // Implementations of this interface are compatible with Java 21 and can benefit from
  // Virtual Threads for I/O-bound operations when used with the appropriate executor service.
}