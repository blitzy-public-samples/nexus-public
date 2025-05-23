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
package org.sonatype.nexus.blobstore.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.sonatype.nexus.blobstore.api.OperationType;

/**
 * Marks a blob store method with {@link OperationType} type to collect metrics.
 * 
 * <p>This annotation also provides information about the method's compatibility with Java 21 Virtual Threads.
 * Methods that perform I/O operations (such as reading from or writing to blob stores) are typically good
 * candidates for Virtual Thread execution, as they can yield the carrier thread during blocking I/O operations,
 * improving overall system throughput.</p>
 *
 * <p>When a method is marked as {@code virtualThreadCompatible=true}, it indicates that the method:
 * <ul>
 *   <li>Does not use synchronized blocks or methods that would cause thread pinning</li>
 *   <li>Does not use thread-local variables in ways incompatible with Virtual Threads</li>
 *   <li>Is safe to execute on a Virtual Thread without performance degradation</li>
 * </ul>
 * </p>
 *
 * @since 3.38
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface MonitoringBlobStoreMetrics
{
  /**
   * The type of operation being performed.
   *
   * @return the operation type
   */
  OperationType operationType();
  
  /**
   * Indicates whether this operation is compatible with Java 21 Virtual Threads.
   * 
   * <p>Operations that are I/O-bound and don't use constructs that cause thread pinning
   * (such as synchronized blocks) should be marked as compatible with Virtual Threads.</p>
   * 
   * <p>When set to {@code true}, the BlobStore implementation may choose to execute this
   * operation on a Virtual Thread for improved throughput, especially during blocking I/O
   * operations.</p>
   *
   * @return true if the operation is compatible with Virtual Threads, false otherwise
   */
  boolean virtualThreadCompatible() default false;
  
  /**
   * Indicates whether the thread type (platform or virtual) should be tracked in metrics.
   * 
   * <p>When set to {@code true}, the metrics collected for this operation will include
   * information about whether it was executed on a platform thread or a virtual thread.</p>
   *
   * @return true if thread type should be tracked in metrics, false otherwise
   */
  boolean trackThreadType() default false;
}