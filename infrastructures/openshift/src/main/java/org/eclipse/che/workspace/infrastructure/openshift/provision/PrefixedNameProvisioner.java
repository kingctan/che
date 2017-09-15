package org.eclipse.che.workspace.infrastructure.openshift.provision;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.openshift.api.model.Route;
import javax.inject.Singleton;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.InternalEnvironment;
import org.eclipse.che.workspace.infrastructure.openshift.environment.OpenShiftEnvironment;

/**
 * Changes the names of OpenShift objects by adding the workspace identifier to the prefix. Note
 * that names of services and PVCs won't be changed.
 *
 * @author Anton Korneta
 */
@Singleton
public class PrefixedNameProvisioner implements ConfigurationProvisioner {

  @Override
  public void provision(
      InternalEnvironment environment, OpenShiftEnvironment osEnv, RuntimeIdentity identity)
      throws InfrastructureException {
    final String workspaceId = identity.getWorkspaceId();
    for (Pod pod : osEnv.getPods().values()) {
      final ObjectMeta podMetadata = pod.getMetadata();
      podMetadata.setName(workspaceId + '_' + podMetadata.getName());
    }
    for (Route route : osEnv.getRoutes().values()) {
      final ObjectMeta routeMetadata = route.getMetadata();
      routeMetadata.setName(workspaceId + '_' + routeMetadata.getName());
    }
  }
}
