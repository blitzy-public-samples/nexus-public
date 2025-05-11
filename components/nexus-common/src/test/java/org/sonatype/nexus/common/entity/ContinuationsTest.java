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

import com.google.common.collect.ForwardingCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.sonatype.nexus.common.entity.Continuations.BROWSE_LIMIT;
import static org.sonatype.nexus.common.entity.Continuations.iterableOf;
import static org.sonatype.nexus.common.entity.Continuations.iteratorOf;
import static org.sonatype.nexus.common.entity.Continuations.streamOf;

@ExtendWith(MockitoExtension.class)
public class ContinuationsTest
    extends TestSupport
{
  private static final String[] STRINGS =
      new String[]{"one", "two", "three", "four", "five", "six", "seven", "eight"};

  private BrowseMock browseMock;

  @BeforeEach
  void setUp() {
    browseMock = spy(new BrowseMock(STRINGS));
  }

  @Test
  void testStreamOfLimit_Default() {
    int limit = BROWSE_LIMIT;
    assertThat(streamOf(browseMock::browse).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_9() {
    int limit = 9;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_8() {
    int limit = 8;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_7() {
    int limit = 7;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "seven");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_6() {
    int limit = 6;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "six");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_5() {
    int limit = 5;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "five");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_4() {
    int limit = 4;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "four");
    verify(browseMock).browse(limit, "eight");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_3() {
    int limit = 3;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), contains(STRINGS));
    verify(browseMock).browse(limit, null);
    verify(browseMock).browse(limit, "three");
    verify(browseMock).browse(limit, "six");
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfLimit_2() {
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
  void testStreamOfLimit_1() {
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
  void testStreamOfLimit_0() {
    int limit = 0;
    assertThat(streamOf(browseMock::browse, limit).collect(toList()), empty());
    verify(browseMock).browse(limit, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfEmptyList() {
    browseMock = spy(new BrowseMock());
    assertThat(streamOf(browseMock::browse).collect(toList()), empty());
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfSingleton() {
    browseMock = spy(new BrowseMock("one"));
    List<String> result = streamOf(browseMock::browse).collect(toList());
    assertThat(result, contains("one"));
    assertThat(result, hasSize(1));
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testStreamOfSingletonWithNull() {
    browseMock = spy(new BrowseMock(true, "one"));
    List<String> result = streamOf(browseMock::browse).collect(toList());
    assertThat(result, contains("one"));
    assertThat(result, hasSize(1));
    verify(browseMock).browse(BROWSE_LIMIT, null);
    verifyNoMoreInteractions(browseMock);
  }

  @Test
  void testIteratorReuse() {
    Iterable<String> it = iterableOf(browseMock::browse, 3);

    assertThat(stream(it.spliterator(), false).collect(toList()), contains(STRINGS));
    assertThat(stream(it.spliterator(), false).collect(toList()), contains(STRINGS));
  }

  @Test
  void testIteratorNullFunction() {
    assertThrows(IllegalArgumentException.class, () -> iteratorOf(null));
  }

  @Test
  void testIteratorNegativeLimit() {
    assertThrows(IllegalArgumentException.class, () -> iteratorOf(browseMock::browse, -1));
  }

  @Test
  void testIterableNullFunction() {
    assertThrows(IllegalArgumentException.class, () -> iterableOf(null));
  }

  @Test
  void testIterableNegativeLimit() {
    assertThrows(IllegalArgumentException.class, () -> iterableOf(browseMock::browse, -1));
  }

  @Test
  void testStreamNullIterable() {
    assertThrows(IllegalArgumentException.class, () -> streamOf((Iterable<?>) null));
  }

  @Test
  void testStreamNullFunction() {
    assertThrows(IllegalArgumentException.class, () -> streamOf((BiFunction<Integer, String, Continuation<Object>>) null));
  }

  @Test
  void testStreamNegativeLimit() {
    assertThrows(IllegalArgumentException.class, () -> streamOf(browseMock::browse, -1));
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
}