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
 * @see java.util.SequencedCollection Java 21 compatible with Sequenced Collections
 */
public interface FluentQuery<T>
{
  /**
   * Count the elements in the repository that match the current query.
   */
  int count();

  /**
   * Browse through elements in the repository that match the current query.
   * <p>
   * The returned {@link Continuation} is a {@link java.util.Collection} with a defined encounter order,
   * making it compatible with Java 21's {@code SequencedCollection} operations. This allows for
   * accessing the first and last elements, as well as processing elements in reverse order when
   * implementations support these features.
   *
   * @param limit maximum number of elements to return
   * @param continuationToken optional token from a previous browse request
   * @return continuation of elements that can be used with Java 21 Sequenced Collections features
   */
  Continuation<T> browse(int limit, @Nullable String continuationToken);

  /**
   * Browse through elements in the repository that match the current query, eagerly fetching related data.
   * <p>
   * The returned {@link Continuation} is a {@link java.util.Collection} with a defined encounter order,
   * making it compatible with Java 21's {@code SequencedCollection} operations. This allows for
   * accessing the first and last elements, as well as processing elements in reverse order when
   * implementations support these features.
   *
   * @param limit maximum number of elements to return
   * @param continuationToken optional token from a previous browse request
   * @return continuation of elements that can be used with Java 21 Sequenced Collections features
   */
  Continuation<T> browseEager(int limit, @Nullable String continuationToken);
}