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
package org.sonatype.nexus.common.upgrade.events;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test Serialization/Deserialization {@link UpgradeEventSupport} sub-classes.
 * 
 * This test class verifies that the JSON serialization and deserialization of upgrade events
 * works correctly, including proper handling of Optional fields, node ID collections,
 * error messages, and migrations arrays.
 * 
 * Updated for Java 21 and JUnit Jupiter 5.10.1 compatibility with Hamcrest 2.2 assertions.
 * Uses Jackson 2.16.1 with Jdk8Module for proper Optional handling.
 */
public class UpgradeEventTest
{
  /**
   * ObjectMapper configured with Jdk8Module to properly handle Optional fields during
   * serialization and deserialization. Jackson 2.16.1 is fully compatible with Java 21.
   */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

  /**
   * Tests that UpgradeStartedEvent can be properly serialized to JSON and deserialized back
   * with all fields intact, including Optional user and schema version.
   */
  @Test
  void testUpgradeStartedEvent_Deserialization() throws JsonProcessingException {
    UpgradeStartedEvent upgradeStartedEvent = new UpgradeStartedEvent("admin", "1.0", "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeStartedEvent);
    UpgradeStartedEvent result = OBJECT_MAPPER.readValue(event, UpgradeStartedEvent.class);
    assertThat(result.getUser(), is(Optional.of("admin")));
    assertThat(result.getSchemaVersion(), is(Optional.of("1.0")));
    assertThat(result.getMigrations(), is(new String[]{"1.1", "1.2", "1.3"}));
  }

  /**
   * Tests that UpgradeCompletedEvent can be properly serialized to JSON and deserialized back
   * with all fields intact, including Optional user, schema version, and the collection of node IDs.
   * Uses Guava's ImmutableList which is compatible with Java 21.
   */
  @Test
  void testUpgradeCompletedEvent_Deserialization() throws JsonProcessingException {
    List<String> nodeIds = ImmutableList.of("node_1", "node_2");
    UpgradeCompletedEvent upgradeStartedEvent = new UpgradeCompletedEvent("admin", "1.0", nodeIds, "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeStartedEvent);
    UpgradeCompletedEvent result = OBJECT_MAPPER.readValue(event, UpgradeCompletedEvent.class);
    assertThat(result.getUser(), is(Optional.of("admin")));
    assertThat(result.getNodeIds(), is(nodeIds));
    assertThat(result.getSchemaVersion(), is(Optional.of("1.0")));
    assertThat(result.getMigrations(), is(new String[]{"1.1", "1.2", "1.3"}));
  }

  /**
   * Tests that UpgradeFailedEvent can be properly serialized to JSON and deserialized back
   * with all fields intact, including Optional user, schema version, and the error message.
   */
  @Test
  void testUpgradeFailedEvent_Deserialization() throws JsonProcessingException {
    UpgradeFailedEvent upgradeStartedEvent = new UpgradeFailedEvent("admin", "1.0", "Error", "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeStartedEvent);
    UpgradeFailedEvent result = OBJECT_MAPPER.readValue(event, UpgradeFailedEvent.class);
    assertThat(result.getUser(), is(Optional.of("admin")));
    assertThat(result.getSchemaVersion(), is(Optional.of("1.0")));
    assertThat(result.getErrorMessage(), is("Error"));
    assertThat(result.getMigrations(), is(new String[]{"1.1", "1.2", "1.3"}));
  }