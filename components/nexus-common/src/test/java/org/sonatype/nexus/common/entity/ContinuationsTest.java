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
package org.sonatype.nexus.common.entity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiFunction;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.ForwardingCollection;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.DisplayName;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.sonatype.nexus.common.entity.Continuations.BROWSE_LIMIT;
import static org.sonatype.nexus.common.entity.Continuations.iterableOf;
import static org.sonatype.nexus.common.entity.Continuations.iteratorOf;
import static org.sonatype.nexus.common.entity.Continuations.streamOf;

@Category(Java21TestGroup.class)
public class ContinuationsTest
    extends TestSupport
{
  private static final String[] STRINGS =
      new String[]{"one", "two", "three", "four", "five", "six", "seven", "eight"};

  private BrowseMock browseMock = spy(new BrowseMock(STRINGS));
  
  /**
   * Simple record for testing Record Patterns with Continuations
   */
  private record DataRecord(String id, String value, Collection<String> items) implements Continuation<String> {
    @Override
    public String nextContinuationToken() {
      return items.isEmpty() ? null : id;
    }
    
    @Override
    protected Collection<String> delegate() {
      return items;
    }
  }

  @Test
  @DisplayName("Test streamOf with default limit")
  public void streamOfLimitDefault() {
    int limit = BROWSE_LIMIT;
    assertThat(streamOf(browseMock::browse).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 9")
  public void streamOfLimit9() {
    int limit = 9;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 8")
  public void streamOfLimit8() {
    int limit = 8;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 7")
  public void streamOfLimit7() {
    int limit = 7;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "seven");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 6")
  public void streamOfLimit6() {
    int limit = 6;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "six");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 5")
  public void streamOfLimit5() {
    int limit = 5;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "five");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 4")
  public void streamOfLimit4() {
    int limit = 4;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "four");
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 3")
  public void streamOfLimit3() {
    int limit = 3;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "three");
    verify(browseMock).browse(limit, "six");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 2")
  public void streamOfLimit2() {
    int limit = 2;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "two");
    verify(browseMock).browse(limit, "four");
    verify(browseMock).browse(limit, "six");
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 1")
  public void streamOfLimit1() {
    int limit = 1;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "one");
    verify(browseMock).browse(limit, "two");
    verify(browseMock).browse(limit, "three");
    verify(browseMock).browse(limit, "four");
    verify(browseMock).browse(limit, "five");
    verify(browseMock).browse(limit, "six");
    verify(browseMock).browse(limit, "seven");
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with limit 0")
  public void streamOfLimit0() {
    int limit = 0;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), empty());
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with empty list")
  public void streamOfEmptyList() {
    browseMock = spy(new BrowseMock());
    assertThat(streamOf(browseMock::browse).collect(toList()), empty());
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with singleton")
  public void streamOfSingleton() {
    browseMock = spy(new BrowseMock("one"));
    List<String> result = streamOf(browseMock::browse).collect(toList());
    assertThat(result, contains("one"));
    assertThat(result, hasSize(1));
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test streamOf with singleton with null")
  public void streamOfSingletonWithNull() {
    browseMock = spy(new BrowseMock(true, "one"));
    List<String> result = streamOf(browseMock::browse).collect(toList());
    assertThat(result, contains("one"));
    assertThat(result, hasSize(1));
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  @DisplayName("Test iterator reuse")
  public void iteratorReuse() {
    Iterable<String> it = iterableOf(browseMock::browse, 3);

    assertThat(stream(it.spliterator(), false).collect(toList()), contains(STRINGS));
    assertThat(stream(it.spliterator(), false).collect(toList()), contains(STRINGS));
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test iterator with null function")
  public void iteratorNullFunction() {
    iteratorOf(null);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test iterator with negative limit")
  public void iteratorNegativeLimit() {
    iteratorOf(browseMock::browse, -1);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test iterable with null function")
  public void iterableNullFunction() {
    iterableOf(null);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test iterable with negative limit")
  public void iterableNegativeLimit() {
    iterableOf(browseMock::browse, -1);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test stream with null iterable")
  public void streamNullIterable() {
    streamOf((Iterable<?>) null);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test stream with null function")
  public void streamNullFunction() {
    streamOf((BiFunction<Integer, String, Continuation<Object>>) null);
  }

  @Test(expected = IllegalArgumentException.class)
  @DisplayName("Test stream with negative limit")
  public void streamNegativeLimit() {
    streamOf(browseMock::browse, -1);
  }

  private static class BrowseMock
  {
    private final boolean isLastTokenNull;

    private final LinkedList<String> strings;

    public BrowseMock(final String... strings) {
      this(false, strings);
    }

    public BrowseMock(final boolean isLastTokenNull, final String... strings) {
      this.isLastTokenNull = isLastTokenNull;
      this.strings = new LinkedList<>(Arrays.asList(strings));
    }

    public Continuation<String> browse(final int limit, final String continuationToken) {
      Iterator<String> it = strings.iterator();
      if (continuationToken != null) {
        while (it.hasNext()) {
          if (continuationToken.equals(it.next())) {
            break;
          }
        }
      }
      LinkedList<String> result = new LinkedList<>();
      for (int i = 0; i < limit; i++) {
        if (it.hasNext()) {
          result.add(it.next());
        }
      }
      return new ContinuationMock<>(result, result.isEmpty() ? null : nextToken(result));
    }

    private String nextToken(final LinkedList<String> result) {
      if (isLastTokenNull && strings.getLast().equals(result.getLast())) {
        return null;
      }
      else {
        return result.getLast();
      }
    }
  }

  private static class ContinuationMock<E>
      extends ForwardingCollection<E>
      implements Continuation<E>
  {
    private final Collection<E> collection;

    private final String continuationToken;

    public ContinuationMock(final Collection<E> collection, final String continuationToken) {
      this.collection = collection;
      this.continuationToken = continuationToken;
    }

    @Override
    protected Collection<E> delegate() {
      return collection;
    }

    @Override
    public String nextContinuationToken() {
      return continuationToken;
    }
  }
  
  @Test
  @DisplayName("Test record patterns with Continuations")
  public void recordPatternsWithContinuations() {
    // Create a test record with some data
    List<String> items = Arrays.asList("item1", "item2", "item3");
    DataRecord record = new DataRecord("test-id", "test-value", items);
    
    // Use record pattern to extract components
    if (record instanceof DataRecord(String id, String value, var dataItems)) {
      // Verify extracted components
      assertThat(id, is("test-id"));
      assertThat(value, is("test-value"));
      assertThat(dataItems, contains("item1", "item2", "item3"));
      
      // Test that the record works with Continuations methods
      List<String> result = streamOf(record).collect(toList());
      assertThat(result, contains("item1", "item2", "item3"));
    } else {
      // This should never happen
      throw new AssertionError("Record pattern matching failed");
    }
  }
  
  @Test
  @DisplayName("Test nested record patterns with Continuations")
  public void nestedRecordPatternsWithContinuations() {
    // Create nested records for testing
    List<String> innerItems = Arrays.asList("inner1", "inner2");
    DataRecord innerRecord = new DataRecord("inner-id", "inner-value", innerItems);
    
    List<String> outerItems = Arrays.asList("outer1", "outer2");
    DataRecord outerRecord = new DataRecord("outer-id", innerRecord.value(), outerItems);
    
    // Use nested record patterns with switch expression
    Object result = switch (outerRecord) {
      case DataRecord(String id, String value, var items) when "inner-value".equals(value) -> {
        // This branch should be taken
        yield streamOf(outerRecord).collect(toList());
      }
      default -> null;
    };
    
    // Verify the result
    assertThat(result, hasSize(2));
    assertThat((List<String>) result, contains("outer1", "outer2"));
  }
}
