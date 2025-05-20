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
package org.sonatype.nexus.security;

import java.util.Enumeration;
import java.util.List;
import java.util.SequencedCollection;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;

import org.sonatype.goodies.common.Loggers;

import com.google.inject.Key;
import org.apache.shiro.web.filter.mgt.DefaultFilterChainManager;
import org.apache.shiro.web.filter.mgt.FilterChainManager;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Dynamic {@link FilterChainManager} that reacts to {@link Filter}s and {@link FilterChain}s as they come and go.
 * Optimized for Java 21 with modern collection handling and pattern matching.
 *
 * @since 3.0
 */
@Singleton
class DynamicFilterChainManager
    extends DefaultFilterChainManager
{
  private static final Logger log = Loggers.getLogger(DynamicFilterChainManager.class);

  private final SequencedCollection<FilterChain> filterChains;

  private volatile boolean refreshChains;

  @Inject
  public DynamicFilterChainManager(@Named("SHIRO") final ServletContext servletContext,
      final List<FilterChain> filterChains, final BeanLocator locator)
  {
    super(new DelegatingFilterConfig("SHIRO", checkNotNull(servletContext)));
    this.filterChains = List.copyOf(checkNotNull(filterChains));

    // install the watchers for dynamic components contributed by other bundles
    locator.watch(Key.get(Filter.class, Named.class), new FilterInstaller(), this);
    locator.watch(Key.get(FilterChain.class), new FilterChainRefresher(), this);
  }

  @Override
  public boolean hasChains() {
    refreshChains();
    return super.hasChains();
  }

  /**
   * Regenerates the cached chain data based on the latest list of {@link FilterChain}s.
   * Uses Java 21 pattern matching for improved readability and error handling.
   */
  private void refreshChains() {
    if (refreshChains) { // only refresh once for the first request after any change
      synchronized (this) {
        if (refreshChains) {
          // Clear existing chains and rebuild from the latest list
          getChainNames().clear();

          // Process each filter chain with pattern matching for better error handling
          for (var chain : filterChains) {
            try {
              switch (chain) {
                case FilterChain fc when fc.getPathPattern() != null && fc.getFilterExpression() != null -> 
                  createChain(fc.getPathPattern(), fc.getFilterExpression());
                case FilterChain fc when fc.getPathPattern() == null -> 
                  log.warn(STR."Filter chain missing path pattern: \{fc}");
                case FilterChain fc when fc.getFilterExpression() == null -> 
                  log.warn(STR."Filter chain missing filter expression: \{fc}");
                default -> 
                  log.warn(STR."Invalid filter chain configuration: \{chain}");
              }
            } catch (IllegalArgumentException e) {
              log.warn(STR."Problem registering filter chain: \{chain}", e);
            }
          }

          refreshChains = false;
        }
      }
    }
  }

  /**
   * Simple {@link FilterConfig} that delegates to the surrounding {@link ServletContext}.
   */
  private static class DelegatingFilterConfig
      implements FilterConfig
  {
    private final String filterName;
    private final ServletContext servletContext;

    DelegatingFilterConfig(final String filterName, final ServletContext servletContext) {
      this.filterName = filterName;
      this.servletContext = servletContext;
    }

    @Override
    public String getFilterName() {
      return filterName;
    }

    @Override
    public ServletContext getServletContext() {
      return servletContext;
    }

    @Override
    public String getInitParameter(String name) {
      return servletContext.getInitParameter(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
      return servletContext.getInitParameterNames();
    }
  }

  /**
   * Watches for {@link Filter}s and registers them with the manager to be initialized.
   * Updated for Java 21 with improved error handling.
   */
  private static class FilterInstaller
      implements Mediator<Named, Filter, DynamicFilterChainManager>
  {
    @Override
    public void add(BeanEntry<Named, Filter> entry, DynamicFilterChainManager manager) {
      var filterName = entry.getKey().value();
      var filter = entry.getValue();
      
      if (filterName != null && filter != null) {
        manager.addFilter(filterName, filter, true);
        log.debug(STR."Added filter: \{filterName}");
      } else {
        log.warn(STR."Invalid filter registration attempt: name=\{filterName}, filter=\{filter}");
      }
    }

    @Override
    public void remove(BeanEntry<Named, Filter> entry, DynamicFilterChainManager manager) {
      var filterName = entry.getKey().value();
      if (filterName != null) {
        manager.getFilters().remove(filterName);
        log.debug(STR."Removed filter: \{filterName}");
      }
    }
  }

  /**
   * Watches for {@link FilterChain}s and flags when the cached data needs refreshing.
   * Updated for Java 21 with improved logging using String Templates.
   */
  private static class FilterChainRefresher
      implements Mediator<Named, FilterChain, DynamicFilterChainManager>
  {
    @Override
    public void add(BeanEntry<Named, FilterChain> entry, DynamicFilterChainManager manager) {
      manager.refreshChains = true;
      log.debug(STR."Filter chain added: \{entry.getKey().value()}");
    }

    @Override
    public void remove(BeanEntry<Named, FilterChain> entry, DynamicFilterChainManager manager) {
      manager.refreshChains = true;
      log.debug(STR."Filter chain removed: \{entry.getKey().value()}");
    }
  }
}