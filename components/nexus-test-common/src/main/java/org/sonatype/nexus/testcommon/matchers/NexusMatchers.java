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
package org.sonatype.nexus.testcommon.matchers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedCollection;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.TypeSafeMatcher;
import org.joda.time.DateTime;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.Matchers.contains;

/**
 * Custom Hamcrest matchers for Nexus testing.
 */
public class NexusMatchers
{
  /**
   * Matches the DateTime specified ignoring chronology
   */
  public static Matcher<DateTime> time(final DateTime dateTime) {
    return new TypeSafeMatcher<DateTime>()
    {
      @Override
      public void describeTo(final Description description) {
        description.appendText(STR."a DateTime \{dateTime}");
      }

      @Override
      protected boolean matchesSafely(final DateTime item) {
        return dateTime.isEqual(item);
      }
    };
  }

  /**
   * Matches the OffsetDateTime specified ignoring chronology
   */
  public static Matcher<OffsetDateTime> time(final OffsetDateTime dateTime) {
    return new TypeSafeMatcher<OffsetDateTime>()
    {
      @Override
      public void describeTo(final Description description) {
        description.appendText(STR."an OffsetDateTime \{dateTime}");
      }

      @Override
      protected boolean matchesSafely(final OffsetDateTime item) {
        return dateTime.isEqual(item);
      }
    };
  }

  /**
   * Matches the Instant specified
   */
  public static Matcher<Instant> time(final Instant instant) {
    return new TypeSafeMatcher<Instant>()
    {
      @Override
      public void describeTo(final Description description) {
        description.appendText(STR."an Instant \{instant}");
      }

      @Override
      protected boolean matchesSafely(final Instant item) {
        return instant.equals(item);
      }
    };
  }

  /**
   * Matches the LocalDateTime specified
   */
  public static Matcher<LocalDateTime> time(final LocalDateTime dateTime) {
    return new TypeSafeMatcher<LocalDateTime>()
    {
      @Override
      public void describeTo(final Description description) {
        description.appendText(STR."a LocalDateTime \{dateTime}");
      }

      @Override
      protected boolean matchesSafely(final LocalDateTime item) {
        return dateTime.isEqual(item);
      }
    };
  }

  /**
   * Matches the ZonedDateTime specified ignoring chronology
   */
  public static Matcher<ZonedDateTime> time(final ZonedDateTime dateTime) {
    return new TypeSafeMatcher<ZonedDateTime>()
    {
      @Override
      public void describeTo(final Description description) {
        description.appendText(STR."a ZonedDateTime \{dateTime}");
      }

      @Override
      protected boolean matchesSafely(final ZonedDateTime item) {
        return dateTime.isEqual(item);
      }
    };
  }

  /**
   * Creates a {@link Matcher} for {@link Stream}. Note that the stream cannot have been consumed,
   * and that the matcher is a terminal operation for the Stream.
   * 
   * This matcher also supports Java 21's {@link SequencedCollection}.
   */
  @SafeVarargs
  public static <E> Matcher<Stream<E>> streamContains(final E... items) {
    List<E> actual = new ArrayList<>();
    Matcher<Iterable<? extends E>> iterableMatcher = contains(items);

    return new TypeSafeDiagnosingMatcher<Stream<E>>(Stream.class)
    {
      @Override
      public void describeTo(final Description description) {
        iterableMatcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(final Stream<E> item, final Description mismatchDescription) {
        if (actual.isEmpty()) {
          item.collect(Collectors.toCollection(() -> actual));
        }

        return iterableMatcher.matches(actual);
      }
    };
  }

  /**
   * Creates a {@link Matcher} for {@link SequencedCollection} or {@link Stream}.
   * This matcher works with both Java 21's Sequenced Collections and Streams.
   */
  @SafeVarargs
  public static <E> Matcher<Object> sequenceContains(final E... items) {
    Matcher<Iterable<? extends E>> iterableMatcher = contains(items);

    return new TypeSafeDiagnosingMatcher<Object>(Object.class)
    {
      @Override
      public void describeTo(final Description description) {
        iterableMatcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(final Object item, final Description mismatchDescription) {
        if (item instanceof Stream<?> stream) {
          List<E> actual = new ArrayList<>();
          stream.forEach(e -> actual.add((E) e));
          return iterableMatcher.matches(actual);
        }
        else if (item instanceof SequencedCollection<?> collection) {
          return iterableMatcher.matches(collection);
        }
        else {
          mismatchDescription.appendText(STR."expected a Stream or SequencedCollection but got \{item.getClass().getName()}");
          return false;
        }
      }
    };
  }

  /**
   * Creates a {@link Matcher} for record types using Record Patterns.
   * 
   * @param recordClass the class of the record to match
   * @param componentMatchers matchers for each component of the record
   * @return a matcher that matches records of the specified type with components matching the provided matchers
   */
  @SafeVarargs
  public static <R extends Record> Matcher<R> recordMatches(Class<R> recordClass, Matcher<?>... componentMatchers) {
    return new TypeSafeDiagnosingMatcher<R>(recordClass) {
      @Override
      protected boolean matchesSafely(R item, Description mismatchDescription) {
        Object[] components = item.getClass().getRecordComponents();
        if (components.length != componentMatchers.length) {
          mismatchDescription.appendText(STR."Record has \{components.length} components but \{componentMatchers.length} matchers were provided");
          return false;
        }
        
        try {
          for (int i = 0; i < componentMatchers.length; i++) {
            String componentName = item.getClass().getRecordComponents()[i].getName();
            Object componentValue = item.getClass().getMethod(componentName).invoke(item);
            Matcher<?> matcher = componentMatchers[i];
            
            if (!matcher.matches(componentValue)) {
              mismatchDescription.appendText(STR."Component '\{componentName}' ");
              matcher.describeMismatch(componentValue, mismatchDescription);
              return false;
            }
          }
          return true;
        }
        catch (Exception e) {
          mismatchDescription.appendText(STR."Error accessing record components: \{e.getMessage()}");
          return false;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText(STR."a record of type \{recordClass.getSimpleName()} with components matching ");
        description.appendList("[", ", ", "]", List.of(componentMatchers));
      }
    };
  }
}