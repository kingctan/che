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
package org.eclipse.che.multiuser.machine.authentication.ide;

import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.user.shared.dto.UserDto;
import org.eclipse.che.multiuser.machine.authentication.shared.dto.MachineTokenDto;

/**
 * GWT Client for MachineToken Service.
 *
 * @author Anton Korneta
 */
public interface MachineTokenServiceClient {

  Promise<MachineTokenDto> getMachineToken();

  Promise<UserDto> getUserByToken(String token);
}
