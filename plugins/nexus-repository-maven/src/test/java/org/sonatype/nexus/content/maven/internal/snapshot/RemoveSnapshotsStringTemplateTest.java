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
package org.sonatype.nexus.content.maven.internal.snapshot;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.types.GroupType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for validating Java 21 string template usage in {@link RemoveSnapshotsFacetImpl}.
 * 
 * This test class specifically focuses on verifying that string templates are used correctly
 * in logging statements and produce the expected output.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
class RemoveSnapshotsStringTemplateTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  @Mock
  private MavenContentFacet facet;

  @Mock
  private Appender<ILoggingEvent> mockAppender;

  @Captor
  private ArgumentCaptor<ILoggingEvent> loggingEventCaptor;

  private RemoveSnapshotsFacetImpl removeSnapshotsFacet;

  private Logger logger;

  @BeforeEach
  void setup() {
    removeSnapshotsFacet = spy(new RemoveSnapshotsFacetImpl(new GroupType()));

    when(repository.getName()).thenReturn("test-repo");
    when(repository.facet(MavenContentFacet.class)).thenReturn(facet);
    removeSnapshotsFacet.attach(repository);

    // Set up logger capture
    logger = (Logger) org.slf4j.LoggerFactory.getLogger(RemoveSnapshotsFacetImpl.class);
    logger.addAppender(mockAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(mockAppender);
  }

  /**
   * Test that string templates are used correctly in the beginning log message of removeSnapshots method.
   */
  @Test
  void testBeginningSnapshotRemovalStringTemplate() {
    // Create a config with specific values to test string interpolation
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(5, 30, true, 10);
    
    // Call the method that uses string templates
    removeSnapshotsFacet.removeSnapshots(config);
    
    // Capture and verify the log message
    verify(mockAppender).doAppend(loggingEventCaptor.capture());
    List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
    
    // Verify the first log message contains the expected interpolated values
    String expectedMessage = STR."Beginning snapshot removal on repository 'test-repo' with configuration: \{config}";
    assertThat(loggingEvents.get(0).getFormattedMessage(), containsString("Beginning snapshot removal on repository 'test-repo'"));
    assertThat(loggingEvents.get(0).getFormattedMessage(), containsString("minimumRetained=5"));
    assertThat(loggingEvents.get(0).getFormattedMessage(), containsString("snapshotRetentionDays=30"));
    assertThat(loggingEvents.get(0).getFormattedMessage(), containsString("removeIfReleased=true"));
    assertThat(loggingEvents.get(0).getFormattedMessage(), containsString("gracePeriod=10"));
  }

  /**
   * Test that string templates are used correctly in the completion log message of removeSnapshots method.
   */
  @Test
  void testCompletionSnapshotRemovalStringTemplate() {
    // Create a config
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(5, 30, true, 10);
    
    // Call the method that uses string templates
    removeSnapshotsFacet.removeSnapshots(config);
    
    // Capture and verify the log message
    verify(mockAppender).doAppend(loggingEventCaptor.capture());
    List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
    
    // Find the completion message (should be the last one)
    String expectedMessage = STR."Completed snapshot removal on repository 'test-repo'";
    boolean foundCompletionMessage = false;
    for (ILoggingEvent event : loggingEvents) {
      if (event.getFormattedMessage().contains("Completed snapshot removal on repository")) {
        assertThat(event.getFormattedMessage(), containsString("Completed snapshot removal on repository 'test-repo'"));
        foundCompletionMessage = true;
        break;
      }
    }
    
    // Ensure we found the completion message
    assertThat("Completion message should be logged", foundCompletionMessage);
  }

  /**
   * Test that string templates are used correctly in the processRepository method.
   */
  @Test
  void testProcessRepositoryStringTemplate() {
    // Create a config
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(5, 30, true, 10);
    
    // Mock the necessary methods to avoid NullPointerException
    doReturn(Collections.emptySet()).when(removeSnapshotsFacet).findSnapshotCandidates(repository, 5);
    
    // Call the method that uses string templates
    removeSnapshotsFacet.processRepository(repository, config);
    
    // Capture and verify the log message
    verify(mockAppender).doAppend(loggingEventCaptor.capture());
    List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
    
    // Verify the log messages contain the expected interpolated values
    boolean foundBeginMessage = false;
    boolean foundFinishedMessage = false;
    
    for (ILoggingEvent event : loggingEvents) {
      String message = event.getFormattedMessage();
      if (message.contains("Begin processing snapshots in repository")) {
        assertThat(message, containsString("Begin processing snapshots in repository 'test-repo'"));
        foundBeginMessage = true;
      }
      else if (message.contains("Finished processing snapshots with more than")) {
        assertThat(message, containsString("Finished processing snapshots with more than 5 versions"));
        foundFinishedMessage = true;
      }
    }
    
    // Ensure we found both messages
    assertThat("Begin processing message should be logged", foundBeginMessage);
    assertThat("Finished processing message should be logged", foundFinishedMessage);
  }

  /**
   * Test that string templates are used correctly with OffsetDateTime in log messages.
   */
  @Test
  void testOffsetDateTimeInStringTemplate() {
    // Create a config with specific values
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(5, 30, true, 10);
    
    // Mock the necessary methods to avoid NullPointerException
    doReturn(Collections.emptySet()).when(removeSnapshotsFacet).findSnapshotCandidates(repository, 5);
    
    // Call the method that uses string templates with OffsetDateTime
    removeSnapshotsFacet.processRepository(repository, config);
    
    // Capture and verify the log message
    verify(mockAppender).doAppend(loggingEventCaptor.capture());
    List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
    
    // Find the message with OffsetDateTime
    boolean foundDateTimeMessage = false;
    for (ILoggingEvent event : loggingEvents) {
      String message = event.getFormattedMessage();
      if (message.contains("Finished processing snapshots with more than")) {
        // The message should contain a formatted OffsetDateTime
        assertThat(message, containsString("created before"));
        // We can't check the exact date/time as it's dynamic, but we can verify it contains date components
        assertThat(message, containsString("-"));
        assertThat(message, containsString(":"));
        foundDateTimeMessage = true;
        break;
      }
    }
    
    // Ensure we found the message with OffsetDateTime
    assertThat("Message with OffsetDateTime should be logged", foundDateTimeMessage);
  }

  /**
   * Test that string templates are used correctly in the deleteSnapshotsForReleasedComponents method.
   */
  @Test
  void testDeleteSnapshotsForReleasedComponentsStringTemplate() {
    // Create a config with removeIfReleased=true
    RemoveSnapshotsConfig config = new RemoveSnapshotsConfig(5, 30, true, 10);
    
    // Mock the necessary methods
    int[] snapshotsToDelete = new int[] {1, 2, 3};
    when(facet.selectSnapshotsAfterRelease(10)).thenReturn(snapshotsToDelete);
    
    // Call the method that uses string templates
    removeSnapshotsFacet.deleteSnapshotsForReleasedComponents(repository, config);
    
    // Capture and verify the log message
    verify(mockAppender).doAppend(loggingEventCaptor.capture());
    List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
    
    // Find the message about deleted snapshots
    boolean foundDeletedMessage = false;
    for (ILoggingEvent event : loggingEvents) {
      String message = event.getFormattedMessage();
      if (message.contains("Deleted")) {
        assertThat(message, containsString("Deleted 3 snapshots for released components"));
        foundDeletedMessage = true;
        break;
      }
    }
    
    // Ensure we found the message about deleted snapshots
    assertThat("Message about deleted snapshots should be logged", foundDeletedMessage);
  }
}