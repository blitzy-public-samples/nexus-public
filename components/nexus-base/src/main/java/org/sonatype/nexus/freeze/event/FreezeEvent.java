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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Base class for all freeze-related events in the system.
 * <p>
 * This sealed class hierarchy forms the foundation of the freeze event system, ensuring
 * type safety and enabling efficient pattern matching in Java 21 environments. The class
 * is sealed to permit only the three concrete event types that represent the complete
 * lifecycle of freeze operations:
 * <ul>
 *   <li>{@link FreezeRequestEvent} - Signals a request to freeze the system</li>
 *   <li>{@link FreezeReleaseEvent} - Signals a normal release of a system freeze</li>
 *   <li>{@link FreezeForceReleaseEvent} - Signals a forced release of a system freeze</li>
 * </ul>
 * <p>
 * With Java 21's pattern matching for switch, event handlers can efficiently process
 * these events using type patterns:
 * <pre>
 * {@code
 * void handleFreezeEvent(FreezeEvent event) {
 *   switch (event) {
 *     case FreezeRequestEvent e -> {
 *       String reason = e.getReason();
 *       log.info(STR."System freeze requested: \{reason}");
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
 * Alternatively, the {@link #getEventCase()} method can be used with traditional switch statements:
 * <pre>
 * {@code
 * void handleFreezeEvent(FreezeEvent event) {
 *   switch (event.getEventCase()) {
 *     case REQUEST:
 *       FreezeRequestEvent requestEvent = (FreezeRequestEvent) event;
 *       // Handle freeze request
 *       break;
 *     case RELEASE:
 *       // Handle normal release
 *       break;
 *     case FORCE_RELEASE:
 *       // Handle forced release
 *       break;
 *   }
 * }
 * }</pre>
 * <p>
 * All freeze events are designed to be compatible with Java 21's virtual threads.
 * Methods that can be safely executed on virtual threads are annotated with
 * {@link VirtualThreadCompatible}.
 *
 * @since 3.0
 */
public abstract sealed class FreezeEvent
    permits FreezeRequestEvent, FreezeReleaseEvent, FreezeForceReleaseEvent
{
  /**
   * Enumeration of freeze event types.
   * <p>
   * These types represent the different states in the freeze lifecycle:
   * <ul>
   *   <li>FREEZE - The system is being frozen</li>
   *   <li>RELEASE - The system is being released from a freeze normally</li>
   *   <li>FORCE_RELEASE - The system is being forcibly released from a freeze</li>
   * </ul>
   */
  public enum FreezeEventTypes
  {
    FREEZE("Freeze"), RELEASE("Release"), FORCE_RELEASE("Force Release");

    private final String type;

    FreezeEventTypes(final String type) {
      this.type = type;
    }

    /**
     * Returns the human-readable type name for this event type.
     *
     * @return the type name
     */
    @VirtualThreadCompatible
    public String getType() {
      return this.type;
    }
  }

  /**
   * Enumeration of freeze event cases for use with traditional switch statements.
   * <p>
   * This enum provides an alternative to Java 21's pattern matching for switch when
   * working with freeze events in environments where pattern matching is not available
   * or preferred.
   */
  public enum EventCase
  {
    /**
     * Represents a {@link FreezeRequestEvent}.
     */
    REQUEST,

    /**
     * Represents a {@link FreezeReleaseEvent}.
     */
    RELEASE,

    /**
     * Represents a {@link FreezeForceReleaseEvent}.
     */
    FORCE_RELEASE
  }

  /**
   * Annotation indicating that a method or class is compatible with Java 21's virtual threads.
   * <p>
   * Methods or classes marked with this annotation can be safely executed on virtual threads
   * without causing thread starvation or other concurrency issues. This is particularly
   * important for event handlers that process freeze events, as they may be executed in
   * high-concurrency scenarios.
   */
  @Documented
  @Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface VirtualThreadCompatible {
    /**
     * Optional description of virtual thread compatibility considerations.
     *
     * @return description of virtual thread compatibility
     */
    String value() default "";
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
   * <p>
   * This method returns the enum value representing the specific type of freeze event,
   * which can be used to determine the appropriate action to take in response to the event.
   *
   * @return the freeze event type
   */
  @VirtualThreadCompatible
  public FreezeEventTypes getEventType() {
    return eventType;
  }

  /**
   * Returns the event case for this freeze event.
   * <p>
   * This method facilitates traditional switch statements when pattern matching is not
   * available or preferred. It returns an enum value that corresponds to the concrete
   * type of this event.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * switch (event.getEventCase()) {
   *   case REQUEST:
   *     FreezeRequestEvent requestEvent = (FreezeRequestEvent) event;
   *     // Handle freeze request
   *     break;
   *   case RELEASE:
   *     // Handle normal release
   *     break;
   *   case FORCE_RELEASE:
   *     // Handle forced release
   *     break;
   * }
   * }</pre>
   *
   * @return the event case corresponding to this event's concrete type
   */
  @VirtualThreadCompatible
  public EventCase getEventCase() {
    if (this instanceof FreezeRequestEvent) {
      return EventCase.REQUEST;
    }
    else if (this instanceof FreezeReleaseEvent) {
      return EventCase.RELEASE;
    }
    else if (this instanceof FreezeForceReleaseEvent) {
      return EventCase.FORCE_RELEASE;
    }
    else {
      // This should never happen due to the sealed class, but included for robustness
      throw new IllegalStateException("Unknown freeze event type: " + this.getClass().getName());
    }
  }
}