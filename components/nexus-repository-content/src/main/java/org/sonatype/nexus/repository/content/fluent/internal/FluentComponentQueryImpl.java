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
package org.sonatype.nexus.repository.content.fluent.internal;

import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.content.fluent.constraints.FluentQueryConstraint;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;

/**
 * {@link FluentQuery} implementation for {@link FluentComponent}s.
 * Enhanced with Java 21 features including Virtual Threads, Pattern Matching, and String Templates.
 *
 * @since 3.26
 */
public class FluentComponentQueryImpl
    implements FluentQuery<FluentComponent>
{
  private static final Logger log = Logger.getLogger(FluentComponentQueryImpl.class.getName());
  
  private final FluentComponentsImpl components;

  private final String kind;

  private final String filter;

  private final Map<String, Object> filterParams;

  private final List<FluentQueryConstraint> constraints;

  FluentComponentQueryImpl(final FluentComponentsImpl components, final List<FluentQueryConstraint> constraints) {
    this.components = checkNotNull(components);
    this.constraints = checkNotNull(constraints);
    this.filter = null;
    this.filterParams = null;
    this.kind = null;
    
    if (log.isLoggable(java.util.logging.Level.FINE)) {
      log.fine(STR."Created FluentComponentQueryImpl with \{constraints.size()} constraints");
    }
  }

  FluentComponentQueryImpl(final FluentComponentsImpl components, final String kind) {
    this.components = checkNotNull(components);
    this.kind = checkNotNull(kind);
    this.filter = null;
    this.filterParams = null;
    this.constraints = emptyList();
    
    if (log.isLoggable(java.util.logging.Level.FINE)) {
      log.fine(STR."Created FluentComponentQueryImpl with kind: \{kind}");
    }
  }

  FluentComponentQueryImpl(final FluentComponentsImpl components,
                           final String filter,
                           final Map<String, Object> filterParams)
  {
    this.components = checkNotNull(components);
    this.kind = null;
    this.filter = checkNotNull(filter);
    this.filterParams = checkNotNull(filterParams);
    this.constraints = emptyList();
    
    if (log.isLoggable(java.util.logging.Level.FINE)) {
      log.fine(STR."Created FluentComponentQueryImpl with filter: \{filter}");
    }
  }

  @Override
  public int count() {
    return components.doCount(kind, filter, filterParams);
  }

  @Override
  public Continuation<FluentComponent> browse(final int limit, final String continuationToken) {
    if (log.isLoggable(java.util.logging.Level.FINE)) {
      log.fine(STR."Browsing components with limit: \{limit}, token: \{continuationToken != null ? continuationToken : "<null>"}");
    }
    
    // Use Virtual Threads for improved concurrency performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Continuation<FluentComponent>> future = CompletableFuture.supplyAsync(
          () -> components.doBrowse(limit, continuationToken, kind, filter, filterParams, constraints),
          executor
      );
      return future.join();
    }
  }

  @Override
  public Continuation<FluentComponent> browseEager(final int limit, @Nullable final String continuationToken) {
    if (log.isLoggable(java.util.logging.Level.FINE)) {
      log.fine(STR."Browsing components eagerly with limit: \{limit}, token: \{continuationToken != null ? continuationToken : "<null>"}");
    }
    
    // Use Virtual Threads for improved concurrency performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Continuation<FluentComponent>> future = CompletableFuture.supplyAsync(
          () -> components.doBrowseEager(limit, continuationToken, kind, filter, filterParams),
          executor
      );
      return future.join();
    }
  }
  
  /**
   * Process constraints using pattern matching to handle different constraint types more elegantly.
   * 
   * @param constraint the constraint to process
   * @return true if the constraint was processed successfully
   */
  private boolean processConstraint(FluentQueryConstraint constraint) {
    return switch (constraint) {
      case FluentQueryConstraint c when c.getClass().getSimpleName().contains("Kind") -> {
        if (log.isLoggable(java.util.logging.Level.FINE)) {
          log.fine(STR."Processing kind constraint: \{c}");
        }
        yield true;
      }
      case FluentQueryConstraint c when c.getClass().getSimpleName().contains("Filter") -> {
        if (log.isLoggable(java.util.logging.Level.FINE)) {
          log.fine(STR."Processing filter constraint: \{c}");
        }
        yield true;
      }
      case FluentQueryConstraint c -> {
        if (log.isLoggable(java.util.logging.Level.FINE)) {
          log.fine(STR."Processing generic constraint: \{c}");
        }
        yield false;
      }
    };
  }
  
  /**
   * Get a sequenced view of the constraints for easier manipulation.
   * 
   * @return a sequenced collection of the constraints
   */
  public SequencedCollection<FluentQueryConstraint> getConstraintsSequence() {
    return List.copyOf(constraints);
  }
}