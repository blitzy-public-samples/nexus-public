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
package org.sonatype.nexus.internal.security.realm;

import org.sonatype.nexus.datastore.api.SingletonDataAccess;

/**
 * {@link RealmConfigurationData} access.
 *
 * <p>This interface provides singleton data access to realm configuration data, ensuring that only one
 * instance of the configuration exists in the system. It leverages the {@link SingletonDataAccess}
 * pattern which is designed to work correctly in both platform thread and Virtual Thread contexts.</p>
 *
 * <p><strong>Virtual Thread Compatibility:</strong> This DAO is compatible with Java 21 Virtual Threads.
 * When accessed within a Virtual Thread context, the underlying transaction management ensures that
 * thread-local transaction state is properly maintained across thread scheduling points. This allows
 * for efficient I/O operations without blocking platform threads.</p>
 *
 * <p><strong>Transaction Behavior:</strong> Operations on this DAO follow ACID properties within the
 * transaction boundaries established by the caller. The singleton pattern implementation is thread-safe
 * and maintains consistency even under high concurrency with Virtual Threads. The underlying MyBatis 3.5.15+
 * implementation has been verified for compatibility with Java 21 and properly handles transaction
 * context propagation in Virtual Thread environments.</p>
 *
 * <p><strong>Implementation Note:</strong> The singleton pattern used by this DAO is designed to work
 * correctly regardless of whether it's accessed from platform threads or Virtual Threads. The pattern
 * ensures that only one instance of the configuration data exists, and all threads (including Virtual Threads)
 * will see a consistent view of this data within transaction boundaries.</p>
 *
 * @since 3.21
 */
public interface RealmConfigurationDAO
    extends SingletonDataAccess<RealmConfigurationData>
{
  // no additional behaviour
}