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
package org.sonatype.nexus.extdirect.model;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Ext Store load parameters.
 *
 * @see <a href="http://docs.sencha.com/extjs/4.2.2/#!/api/Ext.toolbar.Paging">Ext.toolbar.Paging</a>
 * @since 3.0
 */
public class StoreLoadParameters
{
  private Integer page;

  private Integer start;

  private Integer limit;

  private List<Sort> sort;

  private List<Filter> filter;

  private String query;

  private boolean formatSearch;

  public Integer getPage() {
    return page;
  }

  public void setPage(final Integer page) {
    this.page = page;
  }

  public StoreLoadParameters filters(final List<Filter> filter) {
    this.filter = filter;
    return this;
  }

  public StoreLoadParameters limit(final Integer limit) {
    this.limit = limit;
    return this;
  }

  public StoreLoadParameters page(final Integer page) {
    this.page = page;
    return this;
  }

  public StoreLoadParameters sort(final List<Sort> sort) {
    this.sort = sort;
    return this;
  }

  public StoreLoadParameters start(final Integer start) {
    this.start = start;
    return this;
  }

  public Integer getStart() {
    return start;
  }

  public void setStart(final Integer start) {
    this.start = start;
  }

  public Integer getLimit() {
    return limit;
  }

  public void setLimit(final Integer limit) {
    this.limit = limit;
  }

  public List<Filter> getFilters() {
    return filter;
  }

  public void setFilter(final List<Filter> filter) {
    this.filter = filter;
  }

  /**
   * Get filter value for the specified property using pattern matching.
   * 
   * @param property The property to find a filter for
   * @return The filter value or null if not found
   */
  public String getFilter(String property) {
    checkNotNull(property, "property");
    if (filter == null || filter.isEmpty()) {
      return null;
    }
    
    for (Filter item : filter) {
      switch (item) {
        case Filter(var prop, var val) when property.equals(prop) -> {
          return val;
        }
        default -> {}
      }
    }
    return null;
  }

  public List<Sort> getSort() {
    return sort;
  }

  public void setSort(final List<Sort> sort) {
    this.sort = sort;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(final String query) {
    this.query = query;
  }

  public List<Filter> getFilter() {
    return filter;
  }

  public boolean isFormatSearch() {
    return formatSearch;
  }

  public void setFormatSearch(final boolean formatSearch) {
    this.formatSearch = formatSearch;
  }

  @Override
  public String toString() {
    return STR."StoreLoadParameters{page=\{page}, start=\{start}, limit=\{limit}, sort=\{sort}, filter=\{filter}, formatSearch=\{formatSearch}}";
  }

  /**
   * Filter record for store load parameters.
   * 
   * @param property The property name to filter on
   * @param value The value to filter with
   */
  public static record Filter(String property, String value) {
    /**
     * Builder-style method for property setting.
     */
    public Filter property(final String property) {
      return new Filter(property, this.value);
    }

    /**
     * Builder-style method for value setting.
     */
    public Filter value(final String value) {
      return new Filter(this.property, value);
    }

    /**
     * Default constructor for deserialization.
     */
    public Filter() {
      this(null, null);
    }

    @Override
    public String toString() {
      return STR."Filter{property='\{property}', value='\{value}'}";
    }
  }

  /**
   * Sort record for store load parameters.
   * 
   * @param property The property name to sort on
   * @param direction The direction to sort in
   */
  public static record Sort(String property, String direction) {
    /**
     * Default constructor for deserialization.
     */
    public Sort() {
      this(null, null);
    }

    @Override
    public String toString() {
      return STR."Sort{property='\{property}', direction='\{direction}'}";
    }
  }
}