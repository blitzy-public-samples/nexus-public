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
package org.sonatype.nexus.content.example.internal.store;

import org.sonatype.nexus.repository.content.store.ComponentDAO;

/**
 * Example component DAO interface for the example repository format.
 * <p>
 * This interface extends the base {@link ComponentDAO} without adding additional methods,
 * serving as a format-specific extension point for the example repository format.
 * <p>
 * Implementations of this interface can leverage Java 21 features such as:
 * <ul>
 *   <li>Virtual Threads for improved I/O operations performance</li>
 *   <li>Pattern Matching for type-safe data handling</li>
 *   <li>Record Patterns for simplified data extraction</li>
 * </ul>
 *
 * @since 3.24
 */
public interface ExampleComponentDAO
    extends ComponentDAO
{
  // nothing to add...
}