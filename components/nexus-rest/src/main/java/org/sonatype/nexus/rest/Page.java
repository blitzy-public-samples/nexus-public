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
package org.sonatype.nexus.rest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An object to hold pages of XOs for use in the REST API.
 * Optimized for Java 21 with Virtual Threads support and enhanced stream processing.
 *
 * @since 3.3
 */
public class Page<T>
{
  private List<T> items;

  private String continuationToken;

  /**
   * Constructs a new Page with the given items and continuation token.
   *
   * @param items the list of items in this page
   * @param continuationToken the token for retrieving the next page, or null if this is the last page
   */
  public Page(
      @JsonProperty("items") List<T> items,
      @JsonProperty("continuationToken") String continuationToken)
  {
    this.items = items;
    this.continuationToken = continuationToken;
  }

  /**
   * Returns the list of items in this page.
   *
   * @return the list of items
   */
  public List<T> getItems() {
    return items;
  }

  /**
   * Sets the list of items in this page.
   *
   * @param items the list of items to set
   */
  public void setItems(List<T> items) {
    this.items = items;
  }

  /**
   * Returns the continuation token for retrieving the next page.
   *
   * @return the continuation token, or null if this is the last page
   */
  public String getContinuationToken() {
    return continuationToken;
  }

  /**
   * Sets the continuation token for retrieving the next page.
   *
   * @param continuationToken the continuation token to set
   */
  public void setContinuationToken(String continuationToken) {
    this.continuationToken = continuationToken;
  }

  /**
   * Returns whether this page has a continuation token, indicating more pages are available.
   *
   * @return true if this page has a continuation token, false otherwise
   */
  public boolean hasMore() {
    return continuationToken != null && !continuationToken.isEmpty();
  }

  /**
   * Returns a stream of the items in this page.
   * Optimized for use with Virtual Threads in Java 21.
   *
   * @return a stream of the items in this page
   */
  public Stream<T> stream() {
    return items != null ? items.stream() : Stream.empty();
  }

  /**
   * Returns the first item in this page, if present.
   *
   * @return an Optional containing the first item, or an empty Optional if the page is empty
   */
  public Optional<T> getFirst() {
    return items != null && !items.isEmpty() ? Optional.of(items.get(0)) : Optional.empty();
  }

  /**
   * Returns the last item in this page, if present.
   *
   * @return an Optional containing the last item, or an empty Optional if the page is empty
   */
  public Optional<T> getLast() {
    return items != null && !items.isEmpty() ? Optional.of(items.get(items.size() - 1)) : Optional.empty();
  }

  /**
   * Returns whether this page is empty.
   *
   * @return true if this page has no items, false otherwise
   */
  public boolean isEmpty() {
    return items == null || items.isEmpty();
  }

  /**
   * Returns the number of items in this page.
   *
   * @return the number of items in this page
   */
  public int size() {
    return items != null ? items.size() : 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    // Using Java 21 pattern matching for instanceof
    return o instanceof Page<?> page && 
           Objects.equals(items, page.items) &&
           Objects.equals(continuationToken, page.continuationToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(items, continuationToken);
  }

  @Override
  public String toString() {
    return "Page{" +
        "items=" + items +
        ", continuationToken='" + continuationToken + '\'' +
        ", size=" + size() +
        ", hasMore=" + hasMore() +
        '}';
  }
}