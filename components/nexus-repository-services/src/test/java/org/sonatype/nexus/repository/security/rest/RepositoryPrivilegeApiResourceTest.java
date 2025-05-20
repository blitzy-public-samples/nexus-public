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
package org.sonatype.nexus.repository.security.rest;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryAdminPrivilegeDescriptor;
import org.sonatype.nexus.repository.security.RepositoryContentSelectorPrivilegeDescriptor;
import org.sonatype.nexus.repository.security.RepositoryViewPrivilegeDescriptor;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.security.rest.ApiPrivilegeRepositoryContentSelector.CSEL_KEY;
import static org.sonatype.nexus.repository.security.rest.ApiPrivilegeWithRepository.FORMAT_KEY;
import static org.sonatype.nexus.repository.security.rest.ApiPrivilegeWithRepository.REPOSITORY_KEY;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions.ACTIONS_KEY;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21")
@org.junit.experimental.categories.Category(Java21TestGroup.class)
public class RepositoryPrivilegeApiResourceTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private SelectorManager selectorManager;

  @Mock
  private Format format1;

  @Mock
  private Format format2;

  @Mock
  private Repository repository1;

  @Mock
  private Repository repository2;

  private RepositoryPrivilegeApiResource underTest;

  @BeforeEach
  public void setup() throws Exception {
    when(securitySystem.getAuthorizationManager("default")).thenReturn(authorizationManager);
    when(repository1.getFormat()).thenReturn(format1);
    when(repository2.getFormat()).thenReturn(format2);
    when(repositoryManager.get("repository1")).thenReturn(repository1);
    when(repositoryManager.get("repository2")).thenReturn(repository2);
    when(repositoryManager.get("invalid")).thenReturn(null);
    when(selectorManager.findByName(any())).thenReturn(Optional.of(mock(SelectorConfiguration.class)));
    when(selectorManager.findByName("invalid")).thenReturn(Optional.empty());
    when(format1.getValue()).thenReturn("format1");
    when(format2.getValue()).thenReturn("format2");

    List<Format> formats = Arrays.asList(format1, format2);

    Map<String, PrivilegeDescriptor> privilegeDescriptors = new HashMap<>();
    privilegeDescriptors.put(ApplicationPrivilegeDescriptor.TYPE, new ApplicationPrivilegeDescriptor(false));
    privilegeDescriptors.put(WildcardPrivilegeDescriptor.TYPE, new WildcardPrivilegeDescriptor());
    privilegeDescriptors.put(RepositoryAdminPrivilegeDescriptor.TYPE,
        new RepositoryAdminPrivilegeDescriptor(repositoryManager, formats, false));
    privilegeDescriptors
        .put(RepositoryViewPrivilegeDescriptor.TYPE, new RepositoryViewPrivilegeDescriptor(repositoryManager, formats, false));
    privilegeDescriptors.put(RepositoryContentSelectorPrivilegeDescriptor.TYPE,
        new RepositoryContentSelectorPrivilegeDescriptor(repositoryManager, selectorManager, formats, false));

    underTest = new RepositoryPrivilegeApiResource(securitySystem, privilegeDescriptors);
  }

  @Test
  public void createPrivilegeRepositoryView() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("name", "description", "format1",
        "repository1", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    underTest.createPrivilege(apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).addPrivilege(argument.capture());
    assertPrivilege(argument.getValue(), "name", "description", FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", ACTIONS_KEY, "browse,read,delete,edit,add");
  }

  @Test
  public void createPrivilegeRepositoryViewInvalidRepository() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("name",
        "description", "format1", "invalid", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void createPrivilegeRepositoryViewInvalidFormat() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("name",
        "description", "invalid", "*", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as format is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid format 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void createPrivilegeRepositoryViewMismatchRepositoryAndFormat() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("name",
        "description", "invalid", "repository1", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as repository format mismatch.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid format 'invalid' supplied for repository 'repository1'.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void createPrivilegeRepositoryAdmin() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryAdminRequest apiPrivilege = new ApiPrivilegeRepositoryAdminRequest("name", "description", "format1",
        "repository1", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    underTest.createPrivilege(apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).addPrivilege(argument.capture());
    assertPrivilege(argument.getValue(), "name", "description", FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", ACTIONS_KEY, "browse,read,delete,edit,add");
  }

  @Test
  public void createPrivilegeRepositoryAdminInvalidRepository() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryAdminRequest apiPrivilege = new ApiPrivilegeRepositoryAdminRequest("name",
        "description", "format1", "invalid", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void createPrivilegeRepositoryContentSelector() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("name",
        "description", "format1", "repository1", "contentSelector", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    underTest.createPrivilege(apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).addPrivilege(argument.capture());
    assertPrivilege(argument.getValue(), "name", "description", FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", CSEL_KEY, "contentSelector", ACTIONS_KEY, "browse,read,delete,edit,add");
  }

  @Test
  public void createPrivilegeRepositoryContentSelectorInvalidRepository() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("name",
        "description", "format1", "invalid", "contentSelector", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void createPrivilegeRepositoryContentSelectorInvalidContentSelector() {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("repo",
        "description", "format1", "repository1", "invalid", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.createPrivilege(apiPrivilege);
    }, "create should have failed as content selector is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid selector 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void updatePrivilegeRepositoryView() {
    Privilege priv = createPrivilege("repository-view", "priv", "privdesc", false, FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("priv", "newdescription",
        "format2", "repository2", Collections.singletonList(PrivilegeAction.DELETE));

    underTest.updatePrivilege("priv", apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).updatePrivilegeByName(argument.capture());
    assertPrivilege(argument.getValue(), "priv", "newdescription", FORMAT_KEY, "format2", REPOSITORY_KEY,
        "repository2", ACTIONS_KEY, "delete");
  }

  @Test
  public void updatePrivilegeRepositoryViewInvalidRepository() {
    Privilege priv = createPrivilege("repository-view", "priv", "privdesc", false, FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilege("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryViewRequest apiPrivilege = new ApiPrivilegeRepositoryViewRequest("priv", "newdescription",
        "format2", "invalid", Collections.singletonList(PrivilegeAction.DELETE));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.updatePrivilege("priv", apiPrivilege);
    }, "update should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void updatePrivilegeRepositoryAdmin() {
    Privilege priv = createPrivilege("repository-admin", "priv", "privdesc", false, FORMAT_KEY, "format1",
        REPOSITORY_KEY, "repository1", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryAdminRequest apiPrivilege = new ApiPrivilegeRepositoryAdminRequest("priv", "newdescription",
        "format2", "repository2", Collections.singletonList(PrivilegeAction.DELETE));

    underTest.updatePrivilege("priv", apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).updatePrivilegeByName(argument.capture());
    assertPrivilege(argument.getValue(), "priv", "newdescription", FORMAT_KEY, "format2", REPOSITORY_KEY,
        "repository2", ACTIONS_KEY, "delete");
  }

  @Test
  public void updatePrivilegeRepositoryAdminInvalidRepository() {
    Privilege priv = createPrivilege("repository-view", "priv", "privdesc", false, FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilege("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryAdminRequest apiPrivilege = new ApiPrivilegeRepositoryAdminRequest("priv", "newdescription",
        "format2", "invalid", Collections.singletonList(PrivilegeAction.DELETE));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.updatePrivilege("priv", apiPrivilege);
    }, "update should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void updatePrivilegeRepositoryContentSelector() {
    Privilege priv = createPrivilege("repository-content-selector", "priv", "privdesc", false, FORMAT_KEY, "format1",
        REPOSITORY_KEY, "repository1", CSEL_KEY, "contentSelector", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("priv",
        "newdescription", "format2", "repository2", "newcontentSelector",
        Collections.singletonList(PrivilegeAction.DELETE));

    underTest.updatePrivilege("priv", apiPrivilege);

    ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
    verify(authorizationManager).updatePrivilegeByName(argument.capture());
    assertPrivilege(argument.getValue(), "priv", "newdescription", FORMAT_KEY, "format2", REPOSITORY_KEY,
        "repository2", CSEL_KEY, "newcontentSelector", ACTIONS_KEY, "delete");
  }

  @Test
  public void updatePrivilegeRepositoryContentSelectorInvalidRepository() {
    Privilege priv = createPrivilege("repository-view", "priv", "privdesc", false, FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", CSEL_KEY, "contentSelector", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilege("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("priv", "newdescription",
        "format2", "invalid", "newContentSelector", Collections.singletonList(PrivilegeAction.DELETE));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.updatePrivilege("priv", apiPrivilege);
    }, "update should have failed as repository is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid repository 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  @Test
  public void updatePrivilegeRepositoryContentSelectorInvalidContentSelector() {
    Privilege priv = createPrivilege("repository-view", "priv", "privdesc", false, FORMAT_KEY, "format1", REPOSITORY_KEY,
        "repository1", CSEL_KEY, "contentSelector", ACTIONS_KEY, "read,delete");
    when(authorizationManager.getPrivilege("priv")).thenReturn(priv);

    ApiPrivilegeRepositoryContentSelectorRequest apiPrivilege = new ApiPrivilegeRepositoryContentSelectorRequest("priv", "newdescription",
        "format2", "repository2", "invalid", Collections.singletonList(PrivilegeAction.DELETE));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, () -> {
      underTest.updatePrivilege("priv", apiPrivilege);
    }, "update should have failed as content selector is invalid.");
    
    assertEquals(400, exception.getResponse().getStatus());
    assertEquals(MediaType.APPLICATION_JSON_TYPE, exception.getResponse().getMediaType());
    assertEquals("ValidationErrorXO{id='*', message='\"Invalid selector 'invalid' supplied.\"'}", 
        exception.getResponse().getEntity().toString());
  }

  private void assertPrivilege(Privilege privilege,
                               String name,
                               String description,
                               String... properties)
  {
    assertNotNull(privilege);
    assertEquals(name, privilege.getName());
    assertEquals(name, privilege.getId());
    assertEquals(description, privilege.getDescription());
    assertEquals(false, privilege.isReadOnly());

    for (int i = 0; i < properties.length; i += 2) {
      assertEquals(properties[i + 1], privilege.getPrivilegeProperty(properties[i]));
    }
  }

  private Privilege createPrivilege(String type,
                                    String name,
                                    String description,
                                    boolean readOnly,
                                    String... properties)
  {
    Privilege privilege = new Privilege();
    privilege.setType(type);
    privilege.setId(name);
    privilege.setName(name);
    privilege.setDescription(description);
    privilege.setReadOnly(readOnly);

    for (int i = 0; i < properties.length; i += 2) {
      privilege.addProperty(properties[i], properties[i + 1]);
    }

    return privilege;
  }
}
