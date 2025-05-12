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
package org.sonatype.nexus.repository.apt.datastore.internal.browse;

import org.sonatype.nexus.repository.content.browse.store.BrowseNodeDAO;

/**
 * APT repository browse node DAO interface.
 *
 * @since 3.31
 * @since 3.60 Verified compatibility with Java 21 as part of the platform migration from Java 17.
 */
public interface AptBrowseNodeDAO
    extends BrowseNodeDAO
{
  // No additional methods required beyond those provided by BrowseNodeDAO
  // This interface serves as a repository-specific extension point for APT format
}