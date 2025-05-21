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
package org.sonatype.nexus.freeze.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Base class for all freeze-related events in the system.
 * <p>
 * This class is sealed to ensure type safety when using pattern matching with its subclasses.
 * The permitted subclasses represent the complete set of freeze lifecycle events:
 * <ul>
 *   <li>{@link FreezeRequestEvent} - Request to freeze the system</li>
 *   <li>{@link FreezeReleaseEvent} - Normal release of a system freeze</li>
 *   <li>{@link FreezeForceReleaseEvent} - Forced release of a system freeze</li>
 * </ul>
 * <p>
 * With Java 21's pattern matching, you can efficiently handle different event types using
 * switch expressions:
 * <pre>{@code
 * void handleFreezeEvent(FreezeEvent event) {
 *   switch (event) {
 *     case FreezeRequestEvent e -> {
 *       log.info("System freeze requested: {}", e.getReason());
 *       // Handle freeze request
 *     }
 *     case FreezeReleaseEvent e -> {
 *       log.info("System freeze released normally");
 *       // Handle normal release
 *     }
 *     case FreezeForceReleaseEvent e -> {
 *       log.warn("System freeze force-released");
 *       // Handle forced release
 *     }
 *   }
 * }
 * }</pre>
 * <p>
 * Alternatively, you can use the {@link #getEventCase()} method with traditional switch statements:
 * <pre>{@code
 * void handleFreezeEvent(FreezeEvent event) {
 *   switch (event.getEventCase()) {
 *     case REQUEST:
 *       FreezeRequestEvent requestEvent = (FreezeRequestEvent) event;
 *       log.info("System freeze requested: {}", requestEvent.getReason());
 *       break;
 *     case RELEASE:
 *       log.info("System freeze released normally");
 *       break;
 *     case FORCE_RELEASE:
 *       log.warn("System freeze force-released");
 *       break;
 *   }
 * }
 * }</pre>
 * <p>
 * Event handlers for freeze events can be safely executed on virtual threads, as indicated by
 * the {@link VirtualThreadCompatible} annotation. This allows for more efficient processing
 * of freeze events in high-concurrency scenarios.
 *
 * @since 3.0
 */
public sealed abstract class FreezeEvent
    permits FreezeRequestEvent, FreezeReleaseEvent, FreezeForceReleaseEvent
{
  /**
   * Annotation indicating that a method or class is compatible with Java 21 Virtual Threads.
   * <p>
   * When applied to event handlers or processors, this annotation indicates that the
   * implementation is safe to execute on virtual threads without blocking thread-per-task
   * execution models.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  public @interface VirtualThreadCompatible {
    /**
     * Optional description of any constraints or considerations for virtual thread execution.
     */
    String value() default "";
  }

  /**
   * Enumeration of freeze event types for categorizing events.
   */
  public enum FreezeEventTypes
  {
    FREEZE("Freeze"), RELEASE("Release"), FORCE_RELEASE("Force Release");

    private final String type;

    FreezeEventTypes(final String type) {
      this.type = type;
    }

    public String getType() {
      return this.type;
    }
  }

  /**
   * Enumeration of freeze event cases for use with traditional switch statements.
   * <p>
   * This enum provides an alternative to direct pattern matching when working with
   * older Java versions or when pattern matching is not suitable.
   */
  public enum EventCase {
    REQUEST, RELEASE, FORCE_RELEASE
  }

  private final FreezeEventTypes eventType;

  /**
   * Creates a new freeze event with the specified event type.
   *
   * @param eventType the type of freeze event
   */
  protected FreezeEvent(final FreezeEventTypes eventType) {
    this.eventType = eventType;
  }

  /**
   * Returns the type of this freeze event.
   *
   * @return the event type
   */
  public FreezeEventTypes getEventType() {
    return eventType;
  }

  /**
   * Returns the case of this freeze event for use with traditional switch statements.
   * <p>
   * This method provides an alternative to direct pattern matching when working with
   * older Java versions or when pattern matching is not suitable.
   *
   * @return the event case corresponding to this event's concrete type
   */
  @VirtualThreadCompatible
  public EventCase getEventCase() {
    return switch (this) {
      case FreezeRequestEvent ignored -> EventCase.REQUEST;
      case FreezeReleaseEvent ignored -> EventCase.RELEASE;
      case FreezeForceReleaseEvent ignored -> EventCase.FORCE_RELEASE;
    };
  }
}
