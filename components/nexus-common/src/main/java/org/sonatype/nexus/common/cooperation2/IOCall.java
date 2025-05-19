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
package org.sonatype.nexus.common.cooperation2;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark operations that are I/O-bound and suitable for execution on Virtual Threads.
 * 
 * @since 3.41
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IOBound {
  /**
   * Optional description of the I/O operation being performed.
   */
  String value() default "";
}

/**
 * A function encapsulating the work to be done by a Cooperation.
 * <p>
 * This interface is designed for I/O-bound operations that may block while waiting for external resources.
 * Implementations are well-suited for execution on Java 21 Virtual Threads, which provide efficient
 * concurrency for I/O-bound tasks without the overhead of traditional platform threads.
 * <p>
 * When using this interface with Java 21, consider executing implementations on Virtual Threads using:
 * <pre>
 * {@code
 * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
 *   Future<Result> future = executor.submit(() -> ioCall.call());
 *   // Process result
 * }
 * }
 * </pre>
 * 
 * @param <T> the type of result returned by this call
 * @since 3.41
 */
@IOBound
@FunctionalInterface
public interface IOCall<T>
{
  /**
   * Executes the I/O operation and returns a result.
   * <p>
   * This method is designed for I/O-bound operations that may block while waiting for external resources.
   * When executed on a Java 21 Virtual Thread, the runtime can efficiently manage thread resources by
   * unmounting blocked Virtual Threads from carrier platform threads, allowing high concurrency with
   * minimal resource consumption.
   *
   * @return the result of the operation
   * @throws IOException if an I/O error occurs during execution
   */
  T call() throws IOException;
}