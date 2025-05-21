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

/**
 * Audit framework.
 * <p>
 * This module provides a comprehensive audit trail of security-relevant events, with support for external log forwarding
 * and webhook integration. It is implemented as a capability with a dedicated audit log, JSON event serialization, and
 * webhook integration for external event processing.
 * <p>
 * Java 21 Compatibility:
 * <ul>
 *   <li>Fully compatible with Java 21 runtime environment</li>
 *   <li>Leverages Virtual Threads for improved concurrency in audit event processing and webhook delivery</li>
 *   <li>Utilizes String Templates for more efficient and readable audit message formatting</li>
 *   <li>Implements Pattern Matching for more elegant handling of different audit event types</li>
 *   <li>Uses Record Patterns for efficient data extraction from audit event objects</li>
 * </ul>
 * <p>
 * The audit framework benefits from Java 21's performance improvements, particularly in high-volume logging scenarios
 * where Virtual Threads significantly reduce resource consumption while maintaining throughput.
 *
 * @since 3.1
 */
package org.sonatype.nexus.audit;