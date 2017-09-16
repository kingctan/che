/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.selenium.core.client;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static org.eclipse.che.api.core.model.workspace.WorkspaceStatus.RUNNING;
import static org.eclipse.che.api.core.model.workspace.WorkspaceStatus.STOPPED;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.model.machine.Server;
import org.eclipse.che.api.core.model.workspace.Workspace;
import org.eclipse.che.api.core.model.workspace.WorkspaceStatus;
import org.eclipse.che.api.core.rest.HttpJsonRequest;
import org.eclipse.che.api.core.rest.HttpJsonRequestFactory;
import org.eclipse.che.api.workspace.shared.dto.EnvironmentDto;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceConfigDto;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceDto;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.dto.server.DtoFactory;
import org.eclipse.che.selenium.core.provider.TestApiEndpointUrlProvider;
import org.eclipse.che.selenium.core.user.TestUser;
import org.eclipse.che.selenium.core.user.TestUserNamespaceResolver;
import org.eclipse.che.selenium.core.utils.WaitUtils;
import org.eclipse.che.selenium.core.workspace.MemoryMeasure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author Musienko Maxim */
@Singleton
public class TestWorkspaceServiceClient {

  private static final Logger LOG = LoggerFactory.getLogger(TestWorkspaceServiceClient.class);

  private final TestApiEndpointUrlProvider apiEndpointProvider;
  private final HttpJsonRequestFactory requestFactory;
  private final TestUserNamespaceResolver testUserNamespaceResolver;

  @Inject
  public TestWorkspaceServiceClient(
      TestApiEndpointUrlProvider apiEndpointProvider,
      HttpJsonRequestFactory requestFactory,
      TestUserNamespaceResolver testUserNamespaceResolver) {
    this.apiEndpointProvider = apiEndpointProvider;
    this.requestFactory = requestFactory;
    this.testUserNamespaceResolver = testUserNamespaceResolver;
  }

  private String getBaseUrl() {
    return apiEndpointProvider.get() + "workspace";
  }

  /** Returns the list of workspaces names that belongs to the user. */
  public List<String> getAll(TestUser owner) throws Exception {
    List<WorkspaceDto> workspaces = getHttpJsonRequest(owner).request().asList(WorkspaceDto.class);

    return workspaces.stream().map(ws -> ws.getConfig().getName()).collect(Collectors.toList());
  }

  /** Sends stop workspace request. */
  private void sendStopRequest(String workspaceName, TestUser owner, boolean createSnapshot)
      throws Exception {
    if (!exists(workspaceName, owner)) {
      return;
    }

    Workspace workspace = getByName(workspaceName, owner);
    String apiUrl =
        getIdBasedUrl(workspace.getId()) + "/runtime/?create-snapshot=" + valueOf(createSnapshot);

    getHttpJsonRequest(apiUrl, owner).useDeleteMethod().request();
  }

  /** Stops workspace. */
  public void stop(String workspaceName, boolean createSnapshot, TestUser owner) throws Exception {
    sendStopRequest(workspaceName, owner, createSnapshot);
    waitStatus(workspaceName, STOPPED, owner);
  }

  /** Returns workspace by its name. */
  public Workspace getByName(String workspace, TestUser owner) throws Exception {
    return getHttpJsonRequest(getNameBasedUrl(workspace, owner), owner)
        .request()
        .asDto(WorkspaceDto.class);
  }

  /** Indicates if workspace exists. */
  public boolean exists(String workspace, TestUser owner) throws Exception {
    try {
      getHttpJsonRequest(getNameBasedUrl(workspace, owner), owner).request();
    } catch (NotFoundException e) {
      return false;
    }

    return true;
  }

  /** Deletes workspace of default user. */
  public void delete(String workspaceName, TestUser owner) throws Exception {
    if (!exists(workspaceName, owner)) {
      return;
    }

    Workspace workspace = getByName(workspaceName, owner);
    if (workspace.getStatus() != STOPPED) {
      stop(workspaceName, false, owner);
    }

    getHttpJsonRequest(getIdBasedUrl(workspace.getId()), owner).useDeleteMethod().request();

    LOG.info(
        "Workspace name='{}', id='{}', username='{}' is removed",
        workspaceName,
        workspace.getId(),
        owner.getName());
  }

  /** Waits needed status. */
  public void waitStatus(String workspaceName, WorkspaceStatus expectedStatus, TestUser owner)
      throws Exception {

    WorkspaceStatus status = null;
    for (int i = 0; i < 120; i++) {
      status = getByName(workspaceName, owner).getStatus();
      if (status == expectedStatus) {
        return;
      } else {
        WaitUtils.sleepQuietly(5);
      }
    }

    throw new IllegalStateException(
        format(
            "Workspace %s, status=%s, expected status=%s", workspaceName, status, expectedStatus));
  }

  /** Creates a new workspace for default user. */
  public Workspace createWorkspace(
      String workspaceName, int memory, MemoryMeasure memoryUnit, String pathToPattern)
      throws Exception {
    return createWorkspace(workspaceName, memory, memoryUnit, pathToPattern, null);
  }

  /** Creates a new workspace. */
  public Workspace createWorkspace(
      String workspaceName,
      int memory,
      MemoryMeasure memoryUnit,
      String pathToPattern,
      TestUser owner)
      throws Exception {
    String json = FileUtils.readFileToString(new File(pathToPattern), Charset.forName("UTF-8"));
    WorkspaceConfigDto workspace =
        DtoFactory.getInstance().createDtoFromJson(json, WorkspaceConfigDto.class);

    EnvironmentDto environment = workspace.getEnvironments().get("replaced_name");
    environment
        .getMachines()
        .get("dev-machine")
        .getAttributes()
        .put("memoryLimitBytes", Long.toString(convertToByte(memory, memoryUnit)));
    workspace.getEnvironments().remove("replaced_name");
    workspace.getEnvironments().put(workspaceName, environment);
    workspace.setName(workspaceName);
    workspace.setDefaultEnv(workspaceName);

    WorkspaceDto workspaceDto =
        getHttpJsonRequest(getBaseUrl(), owner)
            .usePostMethod()
            .setBody(workspace)
            .request()
            .asDto(WorkspaceDto.class);

    LOG.info(
        "Workspace name='{}', id='{}' for {} created",
        workspaceName,
        workspaceDto.getId(),
        owner != null ? String.format("username=%s", owner.getName()) : "default user");

    return workspaceDto;
  }

  private HttpJsonRequest getHttpJsonRequest(String url, @Nullable TestUser user) {
    HttpJsonRequest httpJsonRequest = requestFactory.fromUrl(url);
    if (user != null) {
      return httpJsonRequest.setAuthorizationHeader(user.getAuthToken());
    }

    return httpJsonRequest;
  }

  /** @return HttpJsonRequest class instance for default user. */
  private HttpJsonRequest getHttpJsonRequest(String url) {
    return getHttpJsonRequest(url, null);
  }

  /** @return HttpJsonRequest class instance for certain user. */
  private HttpJsonRequest getHttpJsonRequest(TestUser owner) {
    return getHttpJsonRequest(getBaseUrl(), owner);
  }

  /** Sends start workspace request. */
  public void sendStartRequest(String workspaceId, String workspaceName, TestUser owner)
      throws Exception {
    getHttpJsonRequest(getIdBasedUrl(workspaceId) + "/runtime", owner)
        .addQueryParam("environment", workspaceName)
        .usePostMethod()
        .request();
  }

  /** Starts workspace. */
  public void start(String workspaceId, String workspaceName, TestUser workspaceOwner)
      throws Exception {
    sendStartRequest(workspaceId, workspaceName, workspaceOwner);
    waitStatus(workspaceName, RUNNING, workspaceOwner);
  }

  /** Gets workspace by its id. */
  public Workspace getById(String workspaceId) throws Exception {
    return getHttpJsonRequest(getIdBasedUrl(workspaceId)).request().asDto(WorkspaceDto.class);
  }

  /** Return server URL related with defined port */
  public String getServerAddressByPort(String workspaceId, int port) throws Exception {
    Workspace workspace = getById(workspaceId);
    ensureRunningStatus(workspace);

    return getById(workspaceId)
        .getRuntime()
        .getMachines()
        .get(0)
        .getRuntime()
        .getServers()
        .get(valueOf(port) + "/tcp")
        .getAddress();
  }

  /**
   * Return ServerDto object by exposed port
   *
   * @param workspaceId workspace id of current user
   * @param exposedPort exposed port of server
   * @return ServerDto object
   */
  public Server getServerByExposedPort(String workspaceId, String exposedPort) throws Exception {
    Workspace workspace =
        getHttpJsonRequest(getIdBasedUrl(workspaceId)).request().asDto(WorkspaceDto.class);

    ensureRunningStatus(workspace);

    return workspace.getRuntime().getDevMachine().getRuntime().getServers().get(exposedPort);
  }

  /**
   * Ensure workspace has running status, or throw IllegalStateException.
   *
   * @param workspace workspace description to get status and id.
   * @throws IllegalStateException if workspace with certain workspaceId doesn't have RUNNING
   *     status.
   */
  public void ensureRunningStatus(Workspace workspace) throws IllegalStateException {
    if (workspace.getStatus() != WorkspaceStatus.RUNNING) {
      throw new IllegalStateException(
          format(
              "Workspace with id='%s' should has '%s' status, but its actual state='%s'",
              workspace.getId(), WorkspaceStatus.RUNNING, workspace.getStatus()));
    }
  }

  private String getNameBasedUrl(String workspaceName, TestUser owner) {
    return getBaseUrl()
        + "/"
        + testUserNamespaceResolver.resolve(owner.getName())
        + "/"
        + workspaceName;
  }

  private String getIdBasedUrl(String workspaceId) {
    return getBaseUrl() + "/" + workspaceId;
  }

  private long convertToByte(int numberOfMemValue, MemoryMeasure desiredMeasureMemory) {
    long calculatedValue = 0;
    //represents values of bytes in 1 megabyte (2x20)
    final long MEGABYTES_CONST = 1048576;

    // represents values of bytes in 1 gygabyte (2x30)
    final long GYGABYTES_CONST = 1073741824;

    switch (desiredMeasureMemory) {
      case MB:
        calculatedValue = numberOfMemValue * MEGABYTES_CONST;
        break;
      case GB:
        calculatedValue = numberOfMemValue * GYGABYTES_CONST;
        break;
    }
    return calculatedValue;
  }
}
