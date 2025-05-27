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
package org.sonatype.nexus.repository.content.fluent;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;

/**
 * Fluent API to count/browse elements in a repository.
 *
 * @since 3.26
 * @java21.update This interface is compatible with Java 21 features including Sequenced Collections
 */
public interface FluentQuery<T>
{
  /**
   * Count the elements in the repository that match the current query.
   */
  int count();

  /**
   * Browse through elements in the repository that match the current query.
   * 
   * <p>Returns a {@link Continuation} which is a {@link java.util.Collection} with a defined encounter order,
   * compatible with Java 21 Sequenced Collections. The elements in the returned collection maintain
   * a well-defined order from first to last, allowing for consistent iteration and access patterns.</p>
   * 
   * <p>When using with Java 21, the returned collection can be processed with Sequenced Collection
   * operations through appropriate casting or adaptation.</p>
   */
  Continuation<T> browse(int limit, @Nullable String continuationToken);

  /**
   * Browse through elements in the repository that match the current query, eagerly loading related entities.
   * 
   * <p>Returns a {@link Continuation} which is a {@link java.util.Collection} with a defined encounter order,
   * compatible with Java 21 Sequenced Collections. The elements in the returned collection maintain
   * a well-defined order from first to last, allowing for consistent iteration and access patterns.</p>
   * 
   * <p>When using with Java 21, the returned collection can be processed with Sequenced Collection
   * operations through appropriate casting or adaptation.</p>
   */
  Continuation<T> browseEager(int limit, @Nullable String continuationToken);
}
