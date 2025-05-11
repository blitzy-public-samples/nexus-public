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

package org.sonatype.nexus.content.maven.store;

import org.sonatype.nexus.repository.content.store.AssetBlobDAO;

/**
 * Maven 2 specific extension of {@link AssetBlobDAO}.
 * 
 * <p>Implementations of this interface can leverage Java 21 features such as Virtual Threads 
 * for improved I/O performance when handling blob operations. This is particularly beneficial 
 * for Maven repositories which often deal with large artifacts and high concurrency.</p>
 *
 * @since 3.25
 * @see AssetBlobDAO
 */
public interface Maven2AssetBlobDAO
    extends AssetBlobDAO
{
  // nothing to add...
}
