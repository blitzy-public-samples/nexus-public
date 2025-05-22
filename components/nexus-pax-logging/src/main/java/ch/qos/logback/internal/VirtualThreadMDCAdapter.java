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
package ch.qos.logback.internal;

import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.helpers.ThreadLocalMapOfStacks;
import org.slf4j.spi.MDCAdapter;

/**
 * A specialized MDC adapter implementation that properly handles context propagation for virtual threads.
 * <p>
 * This adapter ensures that MDC context is properly maintained when virtual threads are unmounted and remounted
 * on different carrier threads, which is essential for maintaining diagnostic context in highly concurrent
 * applications using Java 21 virtual threads.
 * <p>
 * Key features:
 * <ul>
 *   <li>Proper MDC inheritance when virtual threads are spawned from carrier threads</li>
 *   <li>Context preservation during thread unmounting/remounting</li>
 *   <li>Detection of structured field values for compatibility with virtual thread scheduler</li>
 * </ul>
 *
 * @since 3.60
 */
public class VirtualThreadMDCAdapter
    implements MDCAdapter
{
  // Thread-local storage for MDC context maps
  private final ThreadLocal<Map<String, String>> readWriteThreadLocalMap = new ThreadLocal<>();
  private final ThreadLocal<Map<String, String>> readOnlyThreadLocalMap = new ThreadLocal<>();
  
  // For MDC stack operations
  private final ThreadLocalMapOfStacks threadLocalMapOfDeques = new ThreadLocalMapOfStacks();
  
  // Cache to store MDC context for virtual threads by thread ID
  // This helps maintain context when virtual threads are unmounted/remounted
  private final Map<Long, Map<String, String>> virtualThreadContextCache = new ConcurrentHashMap<>();

  /**
   * Puts a context value as identified by key into the current thread's context map.
   * <p>
   * For virtual threads, this also updates the virtual thread context cache to ensure
   * the context is preserved when the thread is unmounted and remounted.
   *
   * @param key   The key for the context value
   * @param val   The context value (can be null)
   */
  @Override
  public void put(String key, String val) {
    if (key == null) {
      throw new IllegalArgumentException("key cannot be null");
    }

    Map<String, String> map = readWriteThreadLocalMap.get();
    if (map == null) {
      map = new HashMap<>();
      readWriteThreadLocalMap.set(map);
    }
    
    if (val != null) {
      map.put(key, val);
    } else {
      map.remove(key);
    }
    
    // If this is a virtual thread, update the context cache
    if (isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      if (val != null) {
        virtualThreadContextCache.computeIfAbsent(threadId, k -> new ConcurrentHashMap<>()).put(key, val);
      } else {
        Map<String, String> cachedMap = virtualThreadContextCache.get(threadId);
        if (cachedMap != null) {
          cachedMap.remove(key);
          if (cachedMap.isEmpty()) {
            virtualThreadContextCache.remove(threadId);
          }
        }
      }
    }
    
    // Clear the read-only copy since it's now stale
    readOnlyThreadLocalMap.remove();
  }

  /**
   * Gets the context value identified by the key parameter.
   * <p>
   * For virtual threads, this checks both the thread-local storage and the virtual thread
   * context cache to ensure context is maintained across unmounting/remounting.
   *
   * @param key The key for the desired context value
   * @return The context value or null if no value is found for the given key
   */
  @Override
  public String get(String key) {
    Map<String, String> map = readWriteThreadLocalMap.get();
    if (map != null && key != null) {
      return map.get(key);
    }
    
    // For virtual threads, check the context cache if thread-local is empty
    if (map == null && isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      Map<String, String> cachedMap = virtualThreadContextCache.get(threadId);
      if (cachedMap != null && key != null) {
        // Restore the context from cache to thread-local
        map = new HashMap<>(cachedMap);
        readWriteThreadLocalMap.set(map);
        readOnlyThreadLocalMap.remove();
        return map.get(key);
      }
    }
    
    return null;
  }

  /**
   * Removes the context identified by the key parameter.
   * <p>
   * For virtual threads, this also updates the virtual thread context cache.
   *
   * @param key The key for the context value to be removed
   */
  @Override
  public void remove(String key) {
    if (key == null) {
      return;
    }
    
    Map<String, String> map = readWriteThreadLocalMap.get();
    if (map != null) {
      map.remove(key);
    }
    
    // Update the virtual thread context cache
    if (isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      Map<String, String> cachedMap = virtualThreadContextCache.get(threadId);
      if (cachedMap != null) {
        cachedMap.remove(key);
        if (cachedMap.isEmpty()) {
          virtualThreadContextCache.remove(threadId);
        }
      }
    }
    
    // Clear the read-only copy since it's now stale
    readOnlyThreadLocalMap.remove();
  }

  /**
   * Clears the entire context map.
   * <p>
   * For virtual threads, this also clears the entry in the virtual thread context cache.
   */
  @Override
  public void clear() {
    readWriteThreadLocalMap.remove();
    readOnlyThreadLocalMap.remove();
    
    // Clear the virtual thread context cache for this thread
    if (isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      virtualThreadContextCache.remove(threadId);
    }
  }

  /**
   * Gets the keys in the MDC as a Set. The returned value can be null.
   *
   * @return The Set of keys in the MDC or null if the MDC is empty
   */
  @Override
  public Set<String> getKeys() {
    Map<String, String> map = readWriteThreadLocalMap.get();
    
    // For virtual threads, check the context cache if thread-local is empty
    if (map == null && isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      map = virtualThreadContextCache.get(threadId);
      if (map != null) {
        // Restore the context from cache to thread-local
        readWriteThreadLocalMap.set(new HashMap<>(map));
        readOnlyThreadLocalMap.remove();
      }
    }
    
    if (map != null) {
      return map.keySet();
    }
    return null;
  }

  /**
   * Returns a copy of the current thread's context map.
   * <p>
   * For virtual threads, this ensures the returned map includes any context from the cache.
   *
   * @return A copy of the current thread's context map or null if the context is empty
   */
  @Override
  public Map<String, String> getCopyOfContextMap() {
    Map<String, String> map = readWriteThreadLocalMap.get();
    
    // For virtual threads, check the context cache if thread-local is empty
    if (map == null && isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      map = virtualThreadContextCache.get(threadId);
      if (map != null) {
        // Restore the context from cache to thread-local
        readWriteThreadLocalMap.set(new HashMap<>(map));
        readOnlyThreadLocalMap.remove();
      }
    }
    
    if (map == null) {
      return null;
    }
    return new HashMap<>(map);
  }

  /**
   * Sets the current thread's context map by first clearing any existing map and then
   * copying the map passed as parameter.
   * <p>
   * For virtual threads, this also updates the virtual thread context cache.
   *
   * @param contextMap The map to initialize the context with
   */
  @Override
  public void setContextMap(Map<String, String> contextMap) {
    if (contextMap == null) {
      clear();
      return;
    }
    
    Map<String, String> copy = new HashMap<>(contextMap);
    readWriteThreadLocalMap.set(copy);
    readOnlyThreadLocalMap.remove();
    
    // Update the virtual thread context cache
    if (isVirtualThread()) {
      long threadId = Thread.currentThread().threadId();
      if (!copy.isEmpty()) {
        virtualThreadContextCache.put(threadId, new ConcurrentHashMap<>(copy));
      } else {
        virtualThreadContextCache.remove(threadId);
      }
    }
  }

  /**
   * Gets a read-only copy of the current thread's context map.
   * <p>
   * Returned map is read-only to prevent modification outside of the adapter's control.
   *
   * @return A read-only copy of the current thread's context map
   */
  public Map<String, String> getPropertyMap() {
    Map<String, String> readOnlyMap = readOnlyThreadLocalMap.get();
    if (readOnlyMap == null) {
      Map<String, String> map = readWriteThreadLocalMap.get();
      
      // For virtual threads, check the context cache if thread-local is empty
      if (map == null && isVirtualThread()) {
        long threadId = Thread.currentThread().threadId();
        map = virtualThreadContextCache.get(threadId);
        if (map != null) {
          // Restore the context from cache to thread-local
          readWriteThreadLocalMap.set(new HashMap<>(map));
        }
      }
      
      if (map != null && !map.isEmpty()) {
        readOnlyMap = Collections.unmodifiableMap(new HashMap<>(map));
        readOnlyThreadLocalMap.set(readOnlyMap);
      }
    }
    return readOnlyMap;
  }

  /**
   * Pushes a value onto the stack associated with the specified key.
   *
   * @param key   The key for the stack
   * @param value The value to push onto the stack
   */
  @Override
  public void pushByKey(String key, String value) {
    threadLocalMapOfDeques.pushByKey(key, value);
  }

  /**
   * Pops the value at the top of the stack associated with the specified key.
   *
   * @param key The key for the stack
   * @return The value at the top of the stack or null if the stack is empty
   */
  @Override
  public String popByKey(String key) {
    return threadLocalMapOfDeques.popByKey(key);
  }

  /**
   * Cleans up resources when the adapter is no longer needed.
   * <p>
   * This method should be called when the application is shutting down to prevent memory leaks.
   */
  public void close() {
    virtualThreadContextCache.clear();
  }

  /**
   * Checks if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    try {
      // Use reflection to avoid direct dependency on Java 21 API
      return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    } catch (Exception e) {
      // If the method doesn't exist or fails, assume it's not a virtual thread
      return false;
    }
  }
}