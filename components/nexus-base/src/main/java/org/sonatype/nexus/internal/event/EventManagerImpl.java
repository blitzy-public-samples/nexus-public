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
package org.sonatype.nexus.internal.event;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventAware.Asynchronous;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.event.HasAffinity;
import org.sonatype.nexus.jmx.reflect.ManagedAttribute;
import org.sonatype.nexus.jmx.reflect.ManagedObject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.EventBus;
import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.EVENTS;
import static org.sonatype.nexus.common.event.EventBusFactory.reentrantEventBus;
import static org.sonatype.nexus.common.event.EventBusFactory.reentrantVirtualThreadEventBus;

/**
 * Default {@link EventManager} implementation.
 * 
 * This implementation uses Java 21 Virtual Threads for asynchronous event processing,
 * providing significant improvements in concurrency and resource utilization:
 * 
 * - Lightweight threads that consume minimal resources compared to platform threads
 * - Automatic yielding during blocking I/O operations, freeing carrier threads for other work
 * - Ability to handle many more concurrent operations without thread pool exhaustion
 * - Simplified programming model compared to reactive approaches
 * 
 * The event manager maintains two event buses:
 * 1. A synchronous bus for immediate event delivery on the calling thread
 * 2. An asynchronous bus using Virtual Threads for concurrent event processing
 */
@Named
@ManagedLifecycle(phase = EVENTS)
@ManagedObject(typeClass = EventManager.class)
@Singleton
public class EventManagerImpl
    extends LifecycleSupport
    implements EventManager
{
  private final BeanLocator beanLocator;

  private final EventExecutor eventExecutor;

  private final EventBus eventBus;

  private final EventBus asyncBus;

  @Inject
  public EventManagerImpl(final BeanLocator beanLocator, final EventExecutor eventExecutor) {
    this.beanLocator = checkNotNull(beanLocator);
    this.eventExecutor = checkNotNull(eventExecutor);

    this.eventBus = reentrantEventBus("nexus");
    this.asyncBus = reentrantVirtualThreadEventBus("nexus.async");
  }

  /**
   * Mediator to register and unregister {@link EventAware} components.
   */
  private static class EventAwareMediator
      implements Mediator<Named, EventAware, EventManagerImpl>
  {
    @Override
    public void add(final BeanEntry<Named, EventAware> entry, final EventManagerImpl watcher) {
      watcher.register(entry.getValue());
    }

    @Override
    public void remove(final BeanEntry<Named, EventAware> entry, final EventManagerImpl watcher) {
      watcher.unregister(entry.getValue());
    }
  }

  @Override
  protected void doStart() throws Exception {
    // watch for EventSubscriber components and register/unregister them
    beanLocator.watch(Key.get(EventAware.class, Named.class), new EventAwareMediator(), this);
  }

  @Override
  public void register(final Object object) {
    boolean async = object instanceof Asynchronous;

    if (async) {
      asyncBus.register(object);
    }
    else {
      eventBus.register(object);
    }

    log.trace(STR."Registered \{async ? "ASYNC " : ""}\{object}");
  }

  @Override
  public void unregister(final Object object) {
    boolean async = object instanceof Asynchronous;

    if (async) {
      asyncBus.unregister(object);
    }
    else {
      eventBus.unregister(object);
    }

    log.trace(STR."Unregistered \{async ? "ASYNC " : ""}\{object}");
  }

  @Override
  public void post(final Object event) {
    // notify synchronous subscribers before going asynchronous
    eventBus.post(event);

    if (isAffinityEnabled() && event instanceof HasAffinity) {
      String affinity = ((HasAffinity) event).getAffinity();
      if (affinity != null) {
        eventExecutor.executeWithAffinity(affinity, () -> asyncBus.post(event));
      }
      else {
        // unexpected state, fall back to previous behaviour
        log.warn(STR."Event \{event} requested 'null' affinity");
        asyncBus.post(event);
      }
    }
    else {
      asyncBus.post(event);
    }
  }

  @Override
  @VisibleForTesting
  @ManagedAttribute
  public boolean isCalmPeriod() {
    return eventExecutor.isCalmPeriod();
  }

  @Override
  public boolean isAffinityEnabled() {
    return eventExecutor.isAffinityEnabled();
  }
}
