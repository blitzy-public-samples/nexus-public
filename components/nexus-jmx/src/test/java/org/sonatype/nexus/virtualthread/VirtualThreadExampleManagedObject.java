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
package org.sonatype.nexus.virtualthread;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.jmx.ObjectNameEntry;
import org.sonatype.nexus.jmx.reflect.ManagedAttribute;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ManagedOperation;

/**
 * Example managed object for testing JMX operations under Java 21 Virtual Threads.
 * This class is designed to validate that JMX operations work correctly in high-concurrency
 * scenarios using Virtual Threads, particularly for I/O-bound operations that benefit from
 * Virtual Thread optimization.
 *
 * @since 3.60
 */
@Named
@Singleton
@ManagedObject(
    domain = "org.sonatype.nexus.jmx.virtualthread",
    entries = {
        @ObjectNameEntry(name="type", value="VirtualThreadTest")
    },
    description = "Virtual Thread JMX test object"
)
public class VirtualThreadExampleManagedObject
{
  private String name;
  private int operationDelay = 100; // Default delay in ms
  private int ioSimulationDelay = 250; // Default I/O simulation delay in ms
  private final AtomicInteger operationCounter = new AtomicInteger(0);

  /**
   * Get the name attribute.
   */
  @ManagedAttribute(description = "Get name attribute")
  public String getName() {
    return name;
  }

  /**
   * Set the name attribute.
   */
  @ManagedAttribute(description = "Set name attribute")
  public void setName(final String name) {
    simulateOperation();
    this.name = name;
  }

  /**
   * Get the operation delay in milliseconds.
   */
  @ManagedAttribute(description = "Get operation delay in milliseconds")
  public int getOperationDelay() {
    return operationDelay;
  }

  /**
   * Set the operation delay in milliseconds.
   */
  @ManagedAttribute(description = "Set operation delay in milliseconds")
  public void setOperationDelay(final int operationDelay) {
    this.operationDelay = operationDelay;
  }

  /**
   * Get the I/O simulation delay in milliseconds.
   */
  @ManagedAttribute(description = "Get I/O simulation delay in milliseconds")
  public int getIoSimulationDelay() {
    return ioSimulationDelay;
  }

  /**
   * Set the I/O simulation delay in milliseconds.
   */
  @ManagedAttribute(description = "Set I/O simulation delay in milliseconds")
  public void setIoSimulationDelay(final int ioSimulationDelay) {
    this.ioSimulationDelay = ioSimulationDelay;
  }

  /**
   * Get the number of operations performed.
   */
  @ManagedAttribute(description = "Get number of operations performed")
  public int getOperationCount() {
    return operationCounter.get();
  }

  /**
   * Reset the name attribute.
   */
  @ManagedOperation(description = "Reset name attribute")
  public void resetName() {
    simulateOperation();
    this.name = null;
  }

  /**
   * Reset the operation counter.
   */
  @ManagedOperation(description = "Reset operation counter")
  public void resetOperationCounter() {
    operationCounter.set(0);
  }

  /**
   * Perform a simulated I/O-bound operation.
   * This operation is designed to benefit from Virtual Thread optimization.
   */
  @ManagedOperation(description = "Perform simulated I/O operation")
  public String performIoOperation(final String input) {
    simulateOperation();
    simulateIoOperation();
    return "Processed: " + input;
  }

  /**
   * Perform multiple simulated I/O-bound operations in sequence.
   * This operation demonstrates the benefits of Virtual Threads for sequential I/O operations.
   */
  @ManagedOperation(description = "Perform multiple simulated I/O operations")
  public String performMultipleIoOperations(final String input, final int count) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      simulateOperation();
      simulateIoOperation();
      result.append("Operation ").append(i + 1).append(": Processed \'").append(input).append("\'");
      if (i < count - 1) {
        result.append(", ");
      }
    }
    return result.toString();
  }

  /**
   * Simulate a basic operation with configurable delay.
   */
  private void simulateOperation() {
    operationCounter.incrementAndGet();
    if (operationDelay > 0) {
      try {
        Thread.sleep(operationDelay);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Simulate an I/O-bound operation with configurable delay.
   * This method is designed to mimic the behavior of I/O operations that would benefit
   * from Virtual Thread optimization.
   */
  private void simulateIoOperation() {
    if (ioSimulationDelay > 0) {
      try {
        // Use TimeUnit to make it clear this is simulating I/O wait time
        TimeUnit.MILLISECONDS.sleep(ioSimulationDelay);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}