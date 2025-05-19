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
 * <p>
 * With Java 21 support, this annotation can also indicate whether an operation is compatible with
 * Virtual Threads for improved I/O-bound operation performance. Operations that are primarily I/O-bound
 * (such as file system operations, network transfers, and database access) are good candidates for
 * Virtual Thread execution. CPU-intensive operations should continue to use platform threads.
 *
 * @since 3.38
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface MonitoringBlobStoreMetrics
{
  OperationType operationType();
  
  /**
   * Indicates if the operation is compatible with Java 21 Virtual Threads.
   * 
   * @return true if the operation can be executed on a Virtual Thread, false otherwise
   * @since 3.60
   */
  boolean virtualThreadCompatible() default false;
}