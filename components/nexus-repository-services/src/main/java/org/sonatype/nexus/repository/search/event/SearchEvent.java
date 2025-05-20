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
package org.sonatype.nexus.repository.search.event;

import java.util.Collection;

import org.sonatype.nexus.repository.search.query.SearchFilter;

/**
 * Fired to denote a search has occurred.
 */
public record SearchEvent(
    /**
     * The search criteria used in the search. Note, this is not the values that are searched, just the criteria.
     * repository, format, keyword, etc
     * */
    Collection<SearchFilter> searchFilters,
    
    SearchEventSource source)
{
  /**
   * Returns a string representation of this record using String Templates for improved debugging.
   */
  @Override
  public String toString() {
    return STR."SearchEvent[searchFilters=\{searchFilters}, source=\{source}]";
  }
}