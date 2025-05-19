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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test Serialization/Deserialization {@link UpgradeEventSupport} sub-classes.
 */
@Tag("Java21TestGroup")
public class UpgradeEventTest
{
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .registerModule(new Jdk8Module())
      .findAndRegisterModules(); // Enable automatic discovery of modules for Java 21 features

  @Test
  public void upgradeStartedEventDeserialization() throws JsonProcessingException {
    UpgradeStartedEvent upgradeStartedEvent = new UpgradeStartedEvent("admin", "1.0", "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeStartedEvent);
    UpgradeStartedEvent result = OBJECT_MAPPER.readValue(event, UpgradeStartedEvent.class);
    assertEquals(Optional.of("admin"), result.getUser());
    assertEquals(Optional.of("1.0"), result.getSchemaVersion());
    assertArrayEquals(new String[]{"1.1", "1.2", "1.3"}, result.getMigrations());
  }

  @Test
  public void upgradeCompletedEventDeserialization() throws JsonProcessingException {
    List<String> nodeIds = ImmutableList.of("node_1", "node_2");
    UpgradeCompletedEvent upgradeCompletedEvent = new UpgradeCompletedEvent("admin", "1.0", nodeIds, "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeCompletedEvent);
    UpgradeCompletedEvent result = OBJECT_MAPPER.readValue(event, UpgradeCompletedEvent.class);
    assertEquals(Optional.of("admin"), result.getUser());
    assertEquals(nodeIds, result.getNodeIds());
    assertEquals(Optional.of("1.0"), result.getSchemaVersion());
    assertArrayEquals(new String[]{"1.1", "1.2", "1.3"}, result.getMigrations());
  }

  @Test
  public void upgradeFailedEventDeserialization() throws JsonProcessingException {
    UpgradeFailedEvent upgradeFailedEvent = new UpgradeFailedEvent("admin", "1.0", "Error", "1.1", "1.2", "1.3");
    String event = OBJECT_MAPPER.writeValueAsString(upgradeFailedEvent);
    UpgradeFailedEvent result = OBJECT_MAPPER.readValue(event, UpgradeFailedEvent.class);
    assertEquals(Optional.of("admin"), result.getUser());
    assertEquals(Optional.of("1.0"), result.getSchemaVersion());
    assertEquals("Error", result.getErrorMessage());
    assertArrayEquals(new String[]{"1.1", "1.2", "1.3"}, result.getMigrations());
  }
}
